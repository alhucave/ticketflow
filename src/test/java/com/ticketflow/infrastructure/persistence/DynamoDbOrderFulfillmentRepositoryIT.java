package com.ticketflow.infrastructure.persistence;

import com.ticketflow.testsupport.TestTimeouts;
import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.exception.OrderNotFoundException;
import com.ticketflow.domain.exception.OrderStatusConflictException;
import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/** Runs against DynamoDB Local. Enabled with INCLUDE_INTEGRATION=true. */
@Tag("integration")
class DynamoDbOrderFulfillmentRepositoryIT {

    private static final Duration WAIT = TestTimeouts.WAIT;
    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    private static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000)
            .withStartupTimeout(TestTimeouts.CONTAINER_STARTUP);

    private static DynamoDbAsyncClient client;
    private static DynamoDbEventRepository events;
    private static DynamoDbInventoryRepository inventories;
    private static DynamoDbOrderRepository orders;
    private static DynamoDbOrderPlacementRepository placement;
    private static DynamoDbOrderFulfillmentRepository fulfillment;

    @BeforeAll
    static void start() {
        DYNAMO.start();
        client = DynamoDbAsyncClient.builder()
                .endpointOverride(URI.create("http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        new DynamoDbTableProvisioner(client, 10, Duration.ofMillis(100)).provision().block(WAIT);
        events = new DynamoDbEventRepository(client);
        inventories = new DynamoDbInventoryRepository(client);
        orders = new DynamoDbOrderRepository(client);
        placement = new DynamoDbOrderPlacementRepository(client);
        fulfillment = new DynamoDbOrderFulfillmentRepository(client);
    }

    @AfterAll
    static void stop() {
        client.close();
        DYNAMO.stop();
    }

    private static Order reservedOrder(int capacity, int qty) {
        var event = new Event(EventId.generate(), "Concert", Instant.parse("2031-01-01T00:00:00Z"), "Arena", capacity);
        events.save(event).block(WAIT);
        var key = new IdempotencyKey(UUID.randomUUID().toString());
        var order = new Order(OrderId.fromIdempotencyKey(key), event.id(), new Quantity(qty),
                TicketStatus.RESERVED, key, NOW.plusSeconds(600), NOW);
        return placement.placeReservation(order, "test").block(WAIT);
    }

    @Test
    void markPendingConfirmationThenConfirmSale_movesOrderAuditAndCountersAtomically() {
        var order = reservedOrder(10, 3);

        fulfillment.markPendingConfirmation(order, "worker", NOW).block(WAIT);
        var pending = inventories.findByEventId(order.eventId()).block(WAIT);
        assertThat(pending.reserved()).isZero();
        assertThat(pending.pendingConfirmation()).isEqualTo(3);
        assertThat(pending.version()).isEqualTo(2);
        assertThat(orders.findById(order.id()).block(WAIT).status()).isEqualTo(TicketStatus.PENDING_CONFIRMATION);

        fulfillment.confirmSale(order, "worker", NOW).block(WAIT);
        var sold = inventories.findByEventId(order.eventId()).block(WAIT);
        assertThat(sold.pendingConfirmation()).isZero();
        assertThat(sold.sold()).isEqualTo(3);
        assertThat(sold.available()).isEqualTo(7);
        assertThat(sold.version()).isEqualTo(3);
        assertThat(orders.findById(order.id()).block(WAIT).status()).isEqualTo(TicketStatus.SOLD);
        assertThat(orders.findAuditTrail(order.id()).collectList().block(WAIT)).hasSize(3);
    }

    @Test
    void steps_wrongStoredStatus_conflictAndNothingChanges() {
        var order = reservedOrder(10, 2);

        try {
            fulfillment.confirmSale(order, "worker", NOW).block(WAIT);
            org.junit.jupiter.api.Assertions.fail("expected conflict");
        } catch (OrderStatusConflictException expected) {
            assertThat(expected.actual()).isEqualTo(TicketStatus.RESERVED);
        }
        fulfillment.markPendingConfirmation(order, "worker", NOW).block(WAIT);
        var before = inventories.findByEventId(order.eventId()).block(WAIT);
        try {
            fulfillment.markPendingConfirmation(order, "worker", NOW).block(WAIT);
            org.junit.jupiter.api.Assertions.fail("expected conflict");
        } catch (OrderStatusConflictException expected) {
            assertThat(expected.actual()).isEqualTo(TicketStatus.PENDING_CONFIRMATION);
        }

        assertThat(inventories.findByEventId(order.eventId()).block(WAIT)).isEqualTo(before);
        assertThat(orders.findAuditTrail(order.id()).collectList().block(WAIT)).hasSize(2);
    }

    @Test
    void steps_missingOrder_failsWithNotFound() {
        var event = new Event(EventId.generate(), "Concert", Instant.parse("2031-01-01T00:00:00Z"), "Arena", 5);
        events.save(event).block(WAIT);
        var ghost = new Order(new OrderId(UUID.randomUUID().toString()), event.id(), new Quantity(1),
                TicketStatus.RESERVED, new IdempotencyKey("ghost"), NOW.plusSeconds(60), NOW);

        try {
            fulfillment.markPendingConfirmation(ghost, "worker", NOW).block(WAIT);
            org.junit.jupiter.api.Assertions.fail("expected not found");
        } catch (OrderNotFoundException expected) {
            assertThat(expected.orderId()).isEqualTo(ghost.id());
        }
    }

    @Test
    void markPendingConfirmation_reservedCounterTooLow_failsAndNothingChanges() {
        var order = reservedOrder(10, 2);
        // Corrupt the counters on purpose: drain the reserved counter behind the order's back.
        inventories.release(order.eventId(), new Quantity(2)).block(WAIT);
        var before = inventories.findByEventId(order.eventId()).block(WAIT);

        try {
            fulfillment.markPendingConfirmation(order, "worker", NOW).block(WAIT);
            org.junit.jupiter.api.Assertions.fail("expected insufficient inventory");
        } catch (InsufficientInventoryException expected) {
            assertThat(expected.eventId()).isEqualTo(order.eventId());
        }

        assertThat(orders.findById(order.id()).block(WAIT).status()).isEqualTo(TicketStatus.RESERVED);
        assertThat(inventories.findByEventId(order.eventId()).block(WAIT)).isEqualTo(before);
        assertThat(orders.findAuditTrail(order.id()).collectList().block(WAIT)).hasSize(1);
    }
}
