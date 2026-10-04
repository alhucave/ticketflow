package com.ticketflow.usecase;

import com.ticketflow.testsupport.TestTimeouts;
import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.domain.exception.OrderNotFoundException;
import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderQueuePublisher;
import com.ticketflow.infrastructure.persistence.DynamoDbEventRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbOrderPlacementRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbOrderRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbTableProvisioner;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Order status query over the REAL DynamoDB adapters (DynamoDB Local).
 * Enabled with INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
class GetOrderStatusUseCaseIT {

    private static final Duration WAIT = TestTimeouts.WAIT;
    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    private static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000)
            .withStartupTimeout(TestTimeouts.CONTAINER_STARTUP);

    private static DynamoDbAsyncClient client;
    private static DynamoDbEventRepository events;
    private static DynamoDbOrderRepository orders;
    private static RequestPurchaseUseCase purchase;
    private static GetOrderStatusUseCase getStatus;

    @BeforeAll
    static void start() {
        DYNAMO.start();
        client = DynamoDbAsyncClient.builder()
                .endpointOverride(URI.create("http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        StepVerifier.create(new DynamoDbTableProvisioner(client, 10, Duration.ofMillis(100)).provision())
                .verifyComplete();
        events = new DynamoDbEventRepository(client);
        orders = new DynamoDbOrderRepository(client);
        OrderQueuePublisher fakeQueue = order -> Mono.empty();
        purchase = new RequestPurchaseUseCase(new DynamoDbOrderPlacementRepository(client), orders, fakeQueue,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10));
        getStatus = new GetOrderStatusUseCase(orders);
    }

    @AfterAll
    static void stop() {
        client.close();
        DYNAMO.stop();
    }

    private static EventId newEvent(int capacity) {
        var event = new Event(EventId.generate(), "Concert", Instant.parse("2031-01-01T00:00:00Z"), "Arena",
                capacity);
        events.save(event).block(WAIT);
        return event.id();
    }

    private static OrderId purchaseOrder(EventId event, int qty) {
        return purchase.execute(new RequestPurchaseCommand(event, new Quantity(qty),
                new IdempotencyKey(UUID.randomUUID().toString()))).block(WAIT).orderId();
    }

    private static void assertStatus(OrderId id, EventId event, int qty, TicketStatus expected) {
        StepVerifier.create(getStatus.execute(id))
                .assertNext(view -> {
                    assertThat(view.orderId()).isEqualTo(id);
                    assertThat(view.eventId()).isEqualTo(event);
                    assertThat(view.quantity()).isEqualTo(new Quantity(qty));
                    assertThat(view.status()).isEqualTo(expected);
                    assertThat(view.createdAt()).isEqualTo(NOW);
                    assertThat(view.reservationExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
                })
                .verifyComplete();
    }

    @Test
    void execute_afterPurchase_returnsReservedWithExpiry() {
        var event = newEvent(20);
        var id = purchaseOrder(event, 4);

        assertStatus(id, event, 4, TicketStatus.RESERVED);
    }

    @Test
    void execute_afterRealTransitions_reflectsEachState() {
        var event = newEvent(20);
        var id = purchaseOrder(event, 2);

        orders.transition(id, TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION, "consumer", NOW)
                .block(WAIT);
        assertStatus(id, event, 2, TicketStatus.PENDING_CONFIRMATION);

        orders.transition(id, TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD, "consumer", NOW).block(WAIT);
        assertStatus(id, event, 2, TicketStatus.SOLD);
    }

    @Test
    void execute_afterRelease_returnsAvailable() {
        var event = newEvent(20);
        var id = purchaseOrder(event, 2);

        orders.transition(id, TicketStatus.RESERVED, TicketStatus.AVAILABLE, "scheduler", NOW).block(WAIT);

        assertStatus(id, event, 2, TicketStatus.AVAILABLE);
    }

    @Test
    void execute_complimentaryOrder_returnsComplimentary() {
        var event = newEvent(20);
        var id = new OrderId(UUID.randomUUID().toString());
        orders.save(new Order(id, event, new Quantity(1), TicketStatus.AVAILABLE,
                new IdempotencyKey(UUID.randomUUID().toString()), NOW.plus(Duration.ofMinutes(10)), NOW))
                .block(WAIT);

        orders.transition(id, TicketStatus.AVAILABLE, TicketStatus.COMPLIMENTARY, "admin", NOW).block(WAIT);

        assertStatus(id, event, 1, TicketStatus.COMPLIMENTARY);
    }

    @Test
    void execute_unknownId_failsWithOrderNotFound() {
        var id = new OrderId("missing-" + UUID.randomUUID());

        StepVerifier.create(getStatus.execute(id)).expectError(OrderNotFoundException.class).verify();
    }
}
