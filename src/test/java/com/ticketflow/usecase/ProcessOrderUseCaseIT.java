package com.ticketflow.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Inventory;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderAuditEntry;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderQueuePublisher;
import com.ticketflow.infrastructure.persistence.DynamoDbEventRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbInventoryRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbOrderFulfillmentRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbOrderPlacementRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbOrderRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbTableProvisioner;
import com.ticketflow.usecase.ProcessOrderResult.AlreadyProcessed;
import com.ticketflow.usecase.ProcessOrderResult.OrderMissing;
import com.ticketflow.usecase.ProcessOrderResult.ReleasedAsExpired;
import com.ticketflow.usecase.ProcessOrderResult.Sold;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Purchase -> queue -> process over the REAL DynamoDB adapters (DynamoDB Local) with a fake queue.
 * Enabled with INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
class ProcessOrderUseCaseIT {

    private static final Duration WAIT = Duration.ofSeconds(120);
    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);

    private static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000);

    private static DynamoDbAsyncClient client;
    private static DynamoDbEventRepository events;
    private static DynamoDbInventoryRepository inventories;
    private static DynamoDbOrderRepository orders;
    private static DynamoDbOrderPlacementRepository placement;
    private static DynamoDbOrderFulfillmentRepository fulfillment;

    /** In-memory queue standing in for SQS: the "consumer" reads the published orders from it. */
    private static final class FakeQueue implements OrderQueuePublisher {
        final List<Order> published = new CopyOnWriteArrayList<>();

        @Override
        public Mono<Void> publish(Order order) {
            return Mono.fromRunnable(() -> published.add(order));
        }
    }

    private FakeQueue queue;
    private RequestPurchaseUseCase purchase;
    private ProcessOrderUseCase process;

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

    @BeforeEach
    void setUp() {
        queue = new FakeQueue();
        purchase = new RequestPurchaseUseCase(placement, orders, queue, at(NOW), TTL);
        process = processorAt(NOW.plusSeconds(5));
    }

    private static Clock at(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    /**
     * Clock that moves forward 1 ms on every reading, so consecutive audit entries get distinct,
     * increasing timestamps (the audit sort key only breaks same-instant ties randomly).
     */
    private static Clock ticking(Instant start) {
        var next = new java.util.concurrent.atomic.AtomicLong(start.toEpochMilli());
        return new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(next.getAndIncrement());
            }
        };
    }

    private static ProcessOrderUseCase processorAt(Instant instant) {
        return new ProcessOrderUseCase(orders, fulfillment, placement, ticking(instant));
    }

    private static EventId newEvent(int capacity) {
        var event = new Event(EventId.generate(), "Concert", Instant.parse("2031-01-01T00:00:00Z"), "Arena",
                capacity);
        events.save(event).block(WAIT);
        return event.id();
    }

    private static Inventory inventory(EventId id) {
        return inventories.findByEventId(id).block(WAIT);
    }

    private static List<OrderAuditEntry> trail(OrderId id) {
        return orders.findAuditTrail(id).collectList().block(WAIT);
    }

    private static TicketStatus status(OrderId id) {
        return orders.findById(id).block(WAIT).status();
    }

    private OrderId buy(EventId event, int qty) {
        var result = purchase.execute(new RequestPurchaseCommand(
                event, new Quantity(qty), new IdempotencyKey(UUID.randomUUID().toString()))).block(WAIT);
        return result.orderId();
    }

    private static void assertInvariant(Inventory inv) {
        assertThat(inv.available() + inv.reserved() + inv.pendingConfirmation() + inv.sold() + inv.complimentary())
                .isEqualTo(inv.capacity());
    }

    private static void assertCounters(Inventory inv, int available, int reserved, int pending, int sold) {
        assertThat(inv.available()).as("available").isEqualTo(available);
        assertThat(inv.reserved()).as("reserved").isEqualTo(reserved);
        assertThat(inv.pendingConfirmation()).as("pendingConfirmation").isEqualTo(pending);
        assertThat(inv.sold()).as("sold").isEqualTo(sold);
        assertInvariant(inv);
    }

    private static List<TicketStatus> path(List<OrderAuditEntry> entries) {
        var states = new java.util.ArrayList<TicketStatus>();
        states.add(entries.get(0).from());
        entries.forEach(e -> states.add(e.to()));
        return states;
    }

    @Test
    void execute_purchaseThenProcess_sellsAndMovesCountersAndAudits() {
        var event = newEvent(50);
        var id = buy(event, 4);
        assertThat(queue.published).extracting(Order::id).containsExactly(id);

        var result = process.execute(queue.published.get(0).id()).block(WAIT);

        assertThat(result).isEqualTo(new Sold(id));
        assertThat(status(id)).isEqualTo(TicketStatus.SOLD);
        var inv = inventory(event);
        assertCounters(inv, 46, 0, 0, 4);
        assertThat(inv.version()).isEqualTo(3); // reserve, mark pending, confirm
        var entries = trail(id);
        assertThat(path(entries)).containsExactly(TicketStatus.AVAILABLE, TicketStatus.RESERVED,
                TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD);
        assertThat(entries).allSatisfy(e -> assertThat(e.actor()).isNotBlank());
        assertThat(entries.get(2).actor()).isEqualTo("order-processor");
    }

    @Test
    void execute_sameOrderTwiceSequentially_sameStateAndCounters() {
        var event = newEvent(20);
        var id = buy(event, 2);

        var first = process.execute(id).block(WAIT);
        var afterFirst = inventory(event);
        var auditAfterFirst = trail(id);
        var second = process.execute(id).block(WAIT);
        var third = process.execute(id).block(WAIT);

        assertThat(first).isEqualTo(new Sold(id));
        assertThat(second).isEqualTo(new AlreadyProcessed(id, TicketStatus.SOLD));
        assertThat(third).isEqualTo(new AlreadyProcessed(id, TicketStatus.SOLD));
        assertThat(inventory(event)).isEqualTo(afterFirst);
        assertThat(trail(id)).isEqualTo(auditAfterFirst);
        assertThat(status(id)).isEqualTo(TicketStatus.SOLD);
        assertCounters(afterFirst, 18, 0, 0, 2);
    }

    @Test
    void execute_twentyWorkersOnSameOrder_exactlyOneSale() {
        var event = newEvent(30);
        var id = buy(event, 5);

        var results = Flux.range(0, 20)
                .flatMap(i -> process.execute(id).subscribeOn(Schedulers.parallel()), 20)
                .collectList().block(WAIT);

        assertThat(results).hasSize(20);
        assertThat(results.stream().filter(Sold.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(r -> !(r instanceof Sold)))
                .allSatisfy(r -> assertThat(r).isEqualTo(new AlreadyProcessed(id, TicketStatus.SOLD)));
        assertThat(status(id)).isEqualTo(TicketStatus.SOLD);
        assertCounters(inventory(event), 25, 0, 0, 5);
        assertThat(path(trail(id))).containsExactly(TicketStatus.AVAILABLE, TicketStatus.RESERVED,
                TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD);
    }

    @Test
    void execute_crashAfterFirstStep_recoversToSold() {
        var event = newEvent(10);
        var id = buy(event, 3);
        // A previous attempt completed step 1 through the real port, then "crashed".
        fulfillment.markPendingConfirmation(orders.findById(id).block(WAIT), "crashed-worker", NOW.plusSeconds(1)).block(WAIT);
        assertThat(status(id)).isEqualTo(TicketStatus.PENDING_CONFIRMATION);
        assertCounters(inventory(event), 7, 0, 3, 0);

        var result = process.execute(id).block(WAIT);

        assertThat(result).isEqualTo(new Sold(id));
        assertCounters(inventory(event), 7, 0, 0, 3);
        assertThat(path(trail(id))).containsExactly(TicketStatus.AVAILABLE, TicketStatus.RESERVED,
                TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD);
    }

    @Test
    void execute_expiredReservation_releasedAndNotSold() {
        var event = newEvent(10);
        var id = buy(event, 4);
        var late = processorAt(NOW.plus(TTL).plusSeconds(1));

        var result = late.execute(id).block(WAIT);

        assertThat(result).isEqualTo(new ReleasedAsExpired(id));
        assertThat(status(id)).isEqualTo(TicketStatus.AVAILABLE);
        assertCounters(inventory(event), 10, 0, 0, 0);
        var entries = trail(id);
        assertThat(path(entries)).containsExactly(TicketStatus.AVAILABLE, TicketStatus.RESERVED, TicketStatus.AVAILABLE);
        assertThat(entries.get(1).reason()).isEqualTo("reservation expired");
        assertThat(entries).noneMatch(e -> e.to() == TicketStatus.SOLD);
        // redelivery after the release is a no-op
        assertThat(late.execute(id).block(WAIT)).isEqualTo(new AlreadyProcessed(id, TicketStatus.AVAILABLE));
        assertCounters(inventory(event), 10, 0, 0, 0);
    }

    @Test
    void execute_expiredWhileAlreadyPending_releasesFromPendingCounter() {
        var event = newEvent(10);
        var id = buy(event, 2);
        fulfillment.markPendingConfirmation(orders.findById(id).block(WAIT), "crashed-worker", NOW.plusSeconds(1)).block(WAIT);

        var result = processorAt(NOW.plus(TTL)).execute(id).block(WAIT);

        assertThat(result).isEqualTo(new ReleasedAsExpired(id));
        assertThat(status(id)).isEqualTo(TicketStatus.AVAILABLE);
        assertCounters(inventory(event), 10, 0, 0, 0);
        var entries = trail(id);
        assertThat(entries.get(entries.size() - 1).from()).isEqualTo(TicketStatus.PENDING_CONFIRMATION);
        assertThat(entries.get(entries.size() - 1).reason()).isEqualTo("reservation expired");
    }

    @Test
    void execute_alreadyAvailableOrComplimentaryOrder_noWrites() {
        var event = newEvent(10);
        var released = buy(event, 1);
        processorAt(NOW.plus(TTL).plusSeconds(1)).execute(released).block(WAIT);
        var complimentary = new Order(OrderId.fromIdempotencyKey(new IdempotencyKey(UUID.randomUUID().toString())),
                event, new Quantity(1), TicketStatus.COMPLIMENTARY, new IdempotencyKey(UUID.randomUUID().toString()),
                NOW.plus(TTL), NOW);
        orders.save(complimentary).block(WAIT);
        var before = inventory(event);
        var auditBefore = trail(released);

        assertThat(process.execute(released).block(WAIT))
                .isEqualTo(new AlreadyProcessed(released, TicketStatus.AVAILABLE));
        assertThat(process.execute(complimentary.id()).block(WAIT))
                .isEqualTo(new AlreadyProcessed(complimentary.id(), TicketStatus.COMPLIMENTARY));

        assertThat(inventory(event)).isEqualTo(before);
        assertThat(trail(released)).isEqualTo(auditBefore);
        assertThat(trail(complimentary.id())).isEmpty();
        assertThat(status(complimentary.id())).isEqualTo(TicketStatus.COMPLIMENTARY);
    }

    @Test
    void execute_missingOrder_returnsOrderMissing() {
        var id = new OrderId(UUID.randomUUID().toString());

        assertThat(process.execute(id).block(WAIT)).isEqualTo(new OrderMissing(id));
    }

    @Test
    void execute_thirtyOrdersInParallel_allSoldAndInvariantHolds() {
        var event = newEvent(100);
        var ids = Flux.range(0, 30).map(i -> buy(event, 1 + i % 3)).collectList().block(WAIT);
        int totalQuantity = ids.stream().mapToInt(id -> orders.findById(id).block(WAIT).quantity().value()).sum();

        var results = Flux.fromIterable(ids)
                .flatMap(id -> process.execute(id).subscribeOn(Schedulers.parallel()), 30)
                .collectList().block(WAIT);

        assertThat(results).hasSize(30).allMatch(Sold.class::isInstance);
        assertThat(ids).allSatisfy(id -> assertThat(status(id)).isEqualTo(TicketStatus.SOLD));
        assertCounters(inventory(event), 100 - totalQuantity, 0, 0, totalQuantity);
        assertThat(ids).allSatisfy(id -> assertThat(trail(id)).hasSize(3));
    }

    @Test
    void execute_redeliveredWhileOthersProcessedConcurrently_stillConsistent() {
        var event = newEvent(60);
        var ids = Flux.range(0, 10).map(i -> buy(event, 2)).collectList().block(WAIT);

        var results = Flux.fromIterable(ids).flatMap(id -> Flux.range(0, 3).map(i -> id))
                .flatMap(id -> process.execute(id).subscribeOn(Schedulers.parallel()), 30)
                .collectList().block(WAIT);

        assertThat(results).hasSize(30);
        assertThat(results.stream().filter(Sold.class::isInstance)).hasSize(10);
        assertCounters(inventory(event), 40, 0, 0, 20);
    }
}
