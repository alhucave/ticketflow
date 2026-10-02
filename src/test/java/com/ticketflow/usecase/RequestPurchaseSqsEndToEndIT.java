package com.ticketflow.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.domain.exception.OrderEnqueueFailedException;
import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.infrastructure.messaging.SqsOrderQueuePublisher;
import com.ticketflow.infrastructure.persistence.DynamoDbEventRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbInventoryRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbOrderPlacementRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbOrderRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbTableProvisioner;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * C12: {@link RequestPurchaseUseCase} over the REAL DynamoDB adapters (DynamoDB Local) and the REAL
 * SQS publisher (LocalStack): purchase -> SQS message, and publish failure -> atomic compensation.
 */
@Tag("integration")
class RequestPurchaseSqsEndToEndIT {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

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

    @BeforeAll
    static void start() {
        DYNAMO.start();
        LOCALSTACK.start();
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        dynamo = DynamoDbAsyncClient.builder()
                .endpointOverride(URI.create("http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000)))
                .region(Region.US_EAST_1).credentialsProvider(credentials).build();
        sqs = sqsClient(LOCALSTACK.getEndpoint());
        StepVerifier.create(new DynamoDbTableProvisioner(dynamo, 10, Duration.ofMillis(100)).provision())
                .verifyComplete();
        events = new DynamoDbEventRepository(dynamo);
        inventories = new DynamoDbInventoryRepository(dynamo);
        orders = new DynamoDbOrderRepository(dynamo);
        placement = new DynamoDbOrderPlacementRepository(dynamo);
    }

    @AfterAll
    static void stop() {
        sqs.close();
        dynamo.close();
        LOCALSTACK.stop();
        DYNAMO.stop();
    }

    private static SqsAsyncClient sqsClient(URI endpoint) {
        return SqsAsyncClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private static RequestPurchaseUseCase useCase(SqsOrderQueuePublisher publisher) {
        return new RequestPurchaseUseCase(placement, orders, publisher, new TickingClock(), Duration.ofMinutes(10));
    }

    /** Moves one second per call: the audit sort key is the timestamp, so the trail order is assertable. */
    private static final class TickingClock extends Clock {
        private final AtomicLong ticks = new AtomicLong();

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return NOW.plusSeconds(ticks.getAndIncrement());
        }
    }

    private static EventId newEvent(int capacity) {
        var event = new Event(EventId.generate(), "Concert", Instant.parse("2031-01-01T00:00:00Z"), "Arena",
                capacity);
        events.save(event).block(WAIT);
        return event.id();
    }

    private static RequestPurchaseCommand command(EventId event, int qty, IdempotencyKey key) {
        return new RequestPurchaseCommand(event, new Quantity(qty), key);
    }

    @Test
    void execute_purchase_enqueuesMessageWithOrderIdAndKeepsReservation() {
        var queueName = "orders-" + UUID.randomUUID();
        var queueUrl = sqs.createQueue(CreateQueueRequest.builder().queueName(queueName).build()).join().queueUrl();
        var event = newEvent(20);
        var key = new IdempotencyKey(UUID.randomUUID().toString());

        var result = useCase(SqsOrderQueuePublisher.forQueueName(sqs, queueName))
                .execute(command(event, 4, key)).block(WAIT);

        assertThat(result.orderId()).isEqualTo(OrderId.fromIdempotencyKey(key));
        var messages = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl)
                .waitTimeSeconds(10).maxNumberOfMessages(10).messageAttributeNames("All").build()).join().messages();
        assertThat(messages).hasSize(1);
        var body = JsonMapper.builder().build().readTree(messages.get(0).body());
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("orderId").asString()).isEqualTo(result.orderId().value());
        assertThat(messages.get(0).messageAttributes().get("eventId").stringValue()).isEqualTo(event.value());
        var inv = inventories.findByEventId(event).block(WAIT);
        assertThat(inv.reserved()).isEqualTo(4);
        assertThat(inv.available()).isEqualTo(16);
        assertThat(orders.findById(result.orderId()).block(WAIT).status()).isEqualTo(TicketStatus.RESERVED);
    }

    @Test
    void execute_queueMissing_compensatesAtomicallyAndSurfacesFailure() {
        var event = newEvent(20);
        var key = new IdempotencyKey(UUID.randomUUID().toString());

        StepVerifier.create(useCase(SqsOrderQueuePublisher.forQueueName(sqs, "missing-" + UUID.randomUUID()))
                        .execute(command(event, 5, key)))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(OrderEnqueueFailedException.class);
                    assertThat(((OrderEnqueueFailedException) e).reservationReleased()).isTrue();
                })
                .verify(WAIT);

        assertReleased(event, key);
    }

    @Test
    void execute_sqsUnreachable_compensatesAtomicallyAndSurfacesFailure() {
        var event = newEvent(20);
        var key = new IdempotencyKey(UUID.randomUUID().toString());
        // Nothing listens on port 1: connection refused, retried a bounded number of times.
        try (var unreachable = sqsClient(URI.create("http://127.0.0.1:1"))) {
            var publisher = SqsOrderQueuePublisher.forQueueUrl(unreachable, "http://127.0.0.1:1/000000000000/orders");

            StepVerifier.create(useCase(publisher).execute(command(event, 5, key)))
                    .expectErrorSatisfies(e -> {
                        assertThat(e).isInstanceOf(OrderEnqueueFailedException.class);
                        assertThat(((OrderEnqueueFailedException) e).reservationReleased()).isTrue();
                    })
                    .verify(WAIT);
        }

        assertReleased(event, key);
    }

    private static void assertReleased(EventId event, IdempotencyKey key) {
        var inv = inventories.findByEventId(event).block(WAIT);
        assertThat(inv.available()).isEqualTo(20);
        assertThat(inv.reserved()).isZero();
        assertThat(inv.version()).isEqualTo(2);
        assertThat(inv.available() + inv.reserved() + inv.pendingConfirmation() + inv.sold() + inv.complimentary())
                .isEqualTo(inv.capacity());
        var id = OrderId.fromIdempotencyKey(key);
        assertThat(orders.findById(id).block(WAIT).status()).isEqualTo(TicketStatus.AVAILABLE);
        var trail = orders.findAuditTrail(id).collectList().block(WAIT);
        assertThat(trail).hasSize(2);
        assertThat(trail.get(1).from()).isEqualTo(TicketStatus.RESERVED);
        assertThat(trail.get(1).to()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(trail.get(1).reason()).isEqualTo(RequestPurchaseUseCase.PUBLISH_FAILED_REASON);
    }
}
