package com.ticketflow.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Inventory;
import com.ticketflow.domain.model.OrderId;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * C12: the whole asynchronous purchase flow with REAL adapters: {@link RequestPurchaseUseCase} ->
 * real SQS publisher (LocalStack) -> real SQS consumer -> {@link ProcessOrderUseCase} over the real
 * DynamoDB adapters (DynamoDB Local). Nothing is faked and no test waits with fixed sleeps.
 */
@Tag("integration")
class OrderProcessingSqsEndToEndIT {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

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

    private SqsTestQueues.Queues newQueues() {
        return SqsTestQueues.create(sqs, "orders-" + UUID.randomUUID());
    }

    private RequestPurchaseUseCase purchaseUseCase(SqsTestQueues.Queues queues) {
        return new RequestPurchaseUseCase(placement, orders, SqsOrderQueuePublisher.forQueueName(sqs, queues.name()),
                Clock.systemUTC(), Duration.ofMinutes(10));
    }

    private void startConsumer(SqsTestQueues.Queues queues) {
        var process = new ProcessOrderUseCase(orders, fulfillment, placement, Clock.systemUTC());
        var props = new SqsConsumerProperties(true, 10, Duration.ofSeconds(2), Duration.ofSeconds(30), 8,
                Duration.ofSeconds(20), Duration.ofSeconds(1), Duration.ofSeconds(5));
        consumer = new SqsOrderConsumer(sqs, SqsQueueUrlResolver.byName(sqs, queues.name()), process, props);
        consumer.start();
    }

    private static EventId newEvent(int capacity) {
        var event = new Event(EventId.generate(), "Concert", Instant.parse("2031-01-01T00:00:00Z"), "Arena",
                capacity);
        events.save(event).block(WAIT);
        return event.id();
    }

    private static Mono<RequestPurchaseResult> purchase(RequestPurchaseUseCase useCase, EventId event, int qty,
                                                        IdempotencyKey key) {
        return useCase.execute(new RequestPurchaseCommand(event, new Quantity(qty), key));
    }

    private static IdempotencyKey newKey() {
        return new IdempotencyKey(UUID.randomUUID().toString());
    }

    private static void assertInvariant(Inventory inv) {
        assertThat(inv.available() + inv.reserved() + inv.pendingConfirmation() + inv.sold() + inv.complimentary())
                .isEqualTo(inv.capacity());
    }

    private static void awaitSold(List<OrderId> ids) {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            for (var id : ids) {
                assertThat(orders.findById(id).block(WAIT).status()).isEqualTo(TicketStatus.SOLD);
            }
        });
    }

    private static void awaitQueueEmpty(SqsTestQueues.Queues queues) {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            assertThat(SqsTestQueues.total(sqs, queues.url())).isZero();
            assertThat(SqsTestQueues.total(sqs, queues.dlqUrl())).isZero();
        });
    }

    @Test
    void purchase_thenConsumer_ordersEndsSoldInventoryMovedAndQueueEmpty() {
        var queues = newQueues();
        var event = newEvent(20);
        var result = purchase(purchaseUseCase(queues), event, 3, newKey()).block(WAIT);
        assertThat(result.status()).isEqualTo(TicketStatus.RESERVED);

        startConsumer(queues);

        awaitSold(List.of(result.orderId()));
        awaitQueueEmpty(queues);
        var inv = inventories.findByEventId(event).block(WAIT);
        assertThat(inv.sold()).isEqualTo(3);
        assertThat(inv.reserved()).isZero();
        assertThat(inv.pendingConfirmation()).isZero();
        assertThat(inv.available()).isEqualTo(17);
        assertInvariant(inv);
        var trail = orders.findAuditTrail(result.orderId()).collectList().block(WAIT);
        assertThat(trail).extracting(entry -> entry.from() + "->" + entry.to()).containsExactly(
                "AVAILABLE->RESERVED", "RESERVED->PENDING_CONFIRMATION", "PENDING_CONFIRMATION->SOLD");
    }

    @Test
    void purchases_thirtyConcurrentOverCapacity_allAcceptedAreSoldWithoutOversell() {
        var queues = newQueues();
        var event = newEvent(40);
        var useCase = purchaseUseCase(queues);
        startConsumer(queues);
        var accepted = new CopyOnWriteArrayList<OrderId>();
        var rejected = new CopyOnWriteArrayList<Throwable>();

        // 30 purchases of 2 tickets compete for 40: exactly 20 fit, the rest is rejected up front.
        Flux.range(0, 30)
                .flatMap(i -> purchase(useCase, event, 2, newKey())
                        .doOnNext(r -> accepted.add(r.orderId()))
                        .onErrorResume(error -> {
                            rejected.add(error);
                            return Mono.empty();
                        }), 30)
                .then().block(TIMEOUT);

        assertThat(rejected).allSatisfy(e -> assertThat(e).isInstanceOf(InsufficientInventoryException.class));
        assertThat(accepted).hasSize(20);
        assertThat(rejected).hasSize(10);
        awaitSold(accepted);
        awaitQueueEmpty(queues);
        var inv = inventories.findByEventId(event).block(WAIT);
        assertThat(inv.sold()).isEqualTo(40);
        assertThat(inv.available()).isZero();
        assertThat(inv.reserved()).isZero();
        assertThat(inv.pendingConfirmation()).isZero();
        assertInvariant(inv);
    }

    @Test
    void consumer_sameMessageDeliveredTwice_sellsOnlyOnce() {
        var queues = newQueues();
        var event = newEvent(10);
        var result = purchase(purchaseUseCase(queues), event, 4, newKey()).block(WAIT);
        // Manual duplicate (at-least-once): the very same message body arrives a second time.
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(queues.url())
                .messageBody("{\"version\":1,\"orderId\":\"" + result.orderId().value() + "\"}").build()).join();
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(queues.url())
                .messageBody("{\"version\":1,\"orderId\":\"" + result.orderId().value() + "\"}").build()).join();

        startConsumer(queues);

        awaitSold(List.of(result.orderId()));
        awaitQueueEmpty(queues);
        var inv = inventories.findByEventId(event).block(WAIT);
        assertThat(inv.sold()).isEqualTo(4);
        assertThat(inv.available()).isEqualTo(6);
        assertThat(inv.reserved()).isZero();
        assertInvariant(inv);
        assertThat(orders.findAuditTrail(result.orderId()).collectList().block(WAIT)).hasSize(3);
    }
}
