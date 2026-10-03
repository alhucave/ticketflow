package com.ticketflow.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.infrastructure.config.SqsConsumerProperties;
import com.ticketflow.infrastructure.messaging.SqsOrderConsumer;
import com.ticketflow.infrastructure.messaging.SqsOrderQueuePublisher;
import com.ticketflow.infrastructure.messaging.SqsQueueUrlResolver;
import com.ticketflow.infrastructure.messaging.SqsTestQueues;
import com.ticketflow.infrastructure.persistence.DynamoDbEventRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbInventoryRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbOrderFulfillmentRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * C12: an order whose message is never consumed in time is reclaimed by the sweeper (real SQS publisher,
 * real DynamoDB adapters); the consumer that finally gets the stale message finds the order already
 * AVAILABLE, acknowledges it and changes nothing. Deterministic: the consumer starts only after the
 * sweep, and the wait is on observable state (queue empty), never on fixed sleeps.
 */
@Tag("integration")
class ReservationExpirationSqsEndToEndIT {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final Instant NOW = Instant.parse("2030-03-01T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);

    private static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000);
    private static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.14.0")).withServices("sqs");

    private static DynamoDbAsyncClient dynamo;
    private static SqsAsyncClient sqs;
    private static DynamoDbEventRepository events;
    private static DynamoDbInventoryRepository inventories;
    private static DynamoDbOrderRepository orders;
    private static DynamoDbOrderPlacementRepository placement;
    private static DynamoDbOrderFulfillmentRepository fulfillment;

    private SqsOrderConsumer consumer;

    @BeforeAll
    static void start() {
        DYNAMO.start();
        LOCALSTACK.start();
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        dynamo = DynamoDbAsyncClient.builder()
                .endpointOverride(URI.create("http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000)))
                .region(Region.US_EAST_1).credentialsProvider(credentials).build();
        sqs = SqsAsyncClient.builder().endpointOverride(LOCALSTACK.getEndpoint()).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
        new DynamoDbTableProvisioner(dynamo, 10, Duration.ofMillis(100)).provision().block(TIMEOUT);
        events = new DynamoDbEventRepository(dynamo);
        inventories = new DynamoDbInventoryRepository(dynamo);
        orders = new DynamoDbOrderRepository(dynamo);
        placement = new DynamoDbOrderPlacementRepository(dynamo);
        fulfillment = new DynamoDbOrderFulfillmentRepository(dynamo);
    }

    @AfterAll
    static void stop() {
        sqs.close();
        dynamo.close();
        LOCALSTACK.stop();
        DYNAMO.stop();
    }

    @AfterEach
    void stopConsumer() {
        if (consumer != null) {
            consumer.stop();
        }
    }

    @Test
    void sweep_thenLateConsumer_orderAvailableAndMessageAcknowledgedWithoutSale() {
        var queues = SqsTestQueues.create(sqs, "orders-" + UUID.randomUUID());
        var event = new Event(EventId.generate(), "Concert", Instant.parse("2031-01-01T00:00:00Z"), "Arena", 10);
        events.save(event).block(WAIT);
        var purchase = new RequestPurchaseUseCase(placement, orders,
                SqsOrderQueuePublisher.forQueueName(sqs, queues.name()), Clock.fixed(NOW, ZoneOffset.UTC), TTL);
        var result = purchase.execute(new RequestPurchaseCommand(event.id(), new Quantity(4),
                new IdempotencyKey(UUID.randomUUID().toString()))).block(WAIT);
        assertThat(inventories.findByEventId(event.id()).block(WAIT).reserved()).isEqualTo(4);

        // nobody consumed the message and the time limit passed: the sweeper reclaims the tickets
        var later = Clock.fixed(NOW.plus(TTL).plusSeconds(1), ZoneOffset.UTC);
        var summary = new ReleaseExpiredReservationsUseCase(orders, placement, later, 4, 100).execute().block(WAIT);
        assertThat(summary.released()).isEqualTo(1);
        assertThat(orders.findById(result.orderId()).block(WAIT).status()).isEqualTo(TicketStatus.AVAILABLE);
        var afterSweep = inventories.findByEventId(event.id()).block(WAIT);
        assertThat(afterSweep.available()).isEqualTo(10);
        assertThat(afterSweep.reserved()).isZero();

        // the stale message is finally consumed: AlreadyProcessed -> acknowledged, nothing changes
        var process = new ProcessOrderUseCase(orders, fulfillment, placement, later);
        var props = new SqsConsumerProperties(true, 10, Duration.ofSeconds(2), Duration.ofSeconds(30), 4,
                Duration.ofSeconds(20), Duration.ofSeconds(1), Duration.ofSeconds(5));
        consumer = new SqsOrderConsumer(sqs, SqsQueueUrlResolver.byName(sqs, queues.name()), process, props);
        consumer.start();

        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            assertThat(SqsTestQueues.total(sqs, queues.url())).isZero();
            assertThat(SqsTestQueues.total(sqs, queues.dlqUrl())).isZero();
        });
        assertThat(orders.findById(result.orderId()).block(WAIT).status()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(inventories.findByEventId(event.id()).block(WAIT)).isEqualTo(afterSweep);
        assertThat(orders.findAuditTrail(result.orderId()).collectList().block(WAIT)).hasSize(2);
    }
}
