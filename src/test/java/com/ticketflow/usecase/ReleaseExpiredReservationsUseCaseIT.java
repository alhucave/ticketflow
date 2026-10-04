package com.ticketflow.usecase;

import com.ticketflow.testsupport.TestTimeouts;
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
import com.ticketflow.usecase.ReleaseExpiredReservationsUseCase.Summary;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * C12: the expiration sweep over the REAL DynamoDB adapters (DynamoDB Local) with a mutable clock and a
 * fake queue: purchase ({@link RequestPurchaseUseCase}) -> time passes -> sweep. Also races between
 * concurrent sweeps and between the sweeper and {@link ProcessOrderUseCase}. Enabled with
 * INCLUDE_INTEGRATION=true.
 *
 * <p>Isolation: the sweep is table wide, so every test lives in its own time window, going backwards
 * ({@code BASE - n days}); orders left over by earlier tests expire later than anything a later test
 * sweeps at, so they are never touched and each test can assert exact summaries.
 */
@Tag("integration")
class ReleaseExpiredReservationsUseCaseIT {

    private static final Duration WAIT = TestTimeouts.WAIT;
    private static final Instant BASE = Instant.parse("2030-06-01T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final AtomicInteger WINDOW = new AtomicInteger();

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

    /** Clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private static final class FakeQueue implements OrderQueuePublisher {
        final List<Order> published = new CopyOnWriteArrayList<>();

        @Override
        public Mono<Void> publish(Order order) {
            return Mono.fromRunnable(() -> published.add(order));
        }
    }

    private Instant base;
    private MutableClock clock;
    private FakeQueue queue;
    private RequestPurchaseUseCase purchase;
    private ProcessOrderUseCase process;
    private ReleaseExpiredReservationsUseCase sweeper;

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
        base = BASE.minus(Duration.ofDays(WINDOW.incrementAndGet()));
        clock = new MutableClock(base);
        queue = new FakeQueue();
        purchase = new RequestPurchaseUseCase(placement, orders, queue, clock, TTL);
        process = new ProcessOrderUseCase(orders, fulfillment, placement, clock);
        sweeper = sweeper(4, 500);
    }

    private ReleaseExpiredReservationsUseCase sweeper(int concurrency, int max) {
        return new ReleaseExpiredReservationsUseCase(orders, placement, clock, concurrency, max);
    }

    private static EventId newEvent(int capacity) {
        var event = new Event(EventId.generate(), "Concert", Instant.parse("2031-01-01T00:00:00Z"), "Arena",
                capacity);
        events.save(event).block(WAIT);
        return event.id();
    }

    private OrderId buy(EventId event, int qty) {
        return purchase.execute(new RequestPurchaseCommand(event, new Quantity(qty),
                new IdempotencyKey(UUID.randomUUID().toString()))).block(WAIT).orderId();
    }

    private static Inventory inventory(EventId id) {
        return inventories.findByEventId(id).block(WAIT);
    }

    private static Order order(OrderId id) {
        return orders.findById(id).block(WAIT);
    }

    private static List<OrderAuditEntry> trail(OrderId id) {
        return orders.findAuditTrail(id).collectList().block(WAIT);
    }

    private static void assertCounters(Inventory inv, int available, int reserved, int pending, int sold) {
        assertThat(inv.available()).as("available").isEqualTo(available);
        assertThat(inv.reserved()).as("reserved").isEqualTo(reserved);
        assertThat(inv.pendingConfirmation()).as("pendingConfirmation").isEqualTo(pending);
        assertThat(inv.sold()).as("sold").isEqualTo(sold);
        assertThat(inv.available() + inv.reserved() + inv.pendingConfirmation() + inv.sold() + inv.complimentary())
                .as("invariant").isEqualTo(inv.capacity());
    }

    private static void assertReleasedOnce(OrderId id, TicketStatus from) {
        assertThat(order(id).status()).isEqualTo(TicketStatus.AVAILABLE);
        var entries = trail(id);
        var releases = entries.stream().filter(e -> e.to() == TicketStatus.AVAILABLE && e.from() != TicketStatus.AVAILABLE)
                .toList();
        assertThat(releases).hasSize(1);
        assertThat(releases.get(0).from()).isEqualTo(from);
        assertThat(releases.get(0).reason()).isEqualTo("reservation expired");
        assertThat(entries).noneMatch(e -> e.to() == TicketStatus.SOLD);
    }

    @Test
    void execute_afterTtl_releasesExpiredOnlyAndLeavesTheRestUntouched() {
        var event = newEvent(100);
        var expiredReserved = buy(event, 2);
        var expiredReserved2 = buy(event, 3);
        var expiredPending = buy(event, 4);
        var sold = buy(event, 5);
        // t+1min: one order is advanced to PENDING_CONFIRMATION (consumer crashed), another one is sold
        clock.set(base.plus(Duration.ofMinutes(1)));
        fulfillment.markPendingConfirmation(order(expiredPending), "crashed-worker", clock.instant()).block(WAIT);
        process.execute(sold).block(WAIT);
        // t+8min: a fresh reservation, still valid when the sweep runs
        clock.set(base.plus(Duration.ofMinutes(8)));
        var fresh = buy(event, 6);
        assertCounters(inventory(event), 80, 11, 4, 5);
        var freshAudit = trail(fresh);
        var soldAudit = trail(sold);
        var soldBefore = order(sold);

        // t+11min: the first three passed their 10 minute limit, `fresh` expires at t+18
        clock.set(base.plus(Duration.ofMinutes(11)));
        var summary = sweeper.execute().block(WAIT);

        assertThat(summary).isEqualTo(new Summary(3, 3, 0, 0));
        assertReleasedOnce(expiredReserved, TicketStatus.RESERVED);
        assertReleasedOnce(expiredReserved2, TicketStatus.RESERVED);
        assertReleasedOnce(expiredPending, TicketStatus.PENDING_CONFIRMATION);
        assertThat(trail(expiredReserved).get(1).actor()).isEqualTo("reservation-expirer");
        assertCounters(inventory(event), 89, 6, 0, 5);
        assertThat(order(fresh).status()).isEqualTo(TicketStatus.RESERVED);
        assertThat(trail(fresh)).isEqualTo(freshAudit);
        assertThat(order(sold)).isEqualTo(soldBefore);
        assertThat(trail(sold)).isEqualTo(soldAudit);

        // idempotent: nothing left to do, nothing changes
        var before = inventory(event);
        assertThat(sweeper.execute().block(WAIT)).isEqualTo(new Summary(0, 0, 0, 0));
        assertThat(inventory(event)).isEqualTo(before);

        // later the fresh one expires too
        clock.set(base.plus(Duration.ofMinutes(18)));
        assertThat(sweeper.execute().block(WAIT)).isEqualTo(new Summary(1, 1, 0, 0));
        assertReleasedOnce(fresh, TicketStatus.RESERVED);
        assertCounters(inventory(event), 95, 0, 0, 5);
    }

    @Test
    void execute_boundary_expiresAtEqualsNowIsExpiredOneTickEarlierIsNot() {
        var event = newEvent(10);
        var id = buy(event, 3);
        var expiresAt = order(id).reservationExpiresAt();
        assertThat(expiresAt).isEqualTo(base.plus(TTL));

        clock.set(expiresAt.minusNanos(1));
        assertThat(sweeper.execute().block(WAIT)).isEqualTo(new Summary(0, 0, 0, 0));
        assertThat(order(id).status()).isEqualTo(TicketStatus.RESERVED);
        assertCounters(inventory(event), 7, 3, 0, 0);

        clock.set(expiresAt);
        assertThat(sweeper.execute().block(WAIT)).isEqualTo(new Summary(1, 1, 0, 0));
        assertReleasedOnce(id, TicketStatus.RESERVED);
        assertCounters(inventory(event), 10, 0, 0, 0);
    }

    @Test
    void boundary_sweeperAndProcessOrderAgreeOnTheBoundaryInstant() {
        var event = newEvent(10);
        var bySweeper = buy(event, 1);
        var byProcessor = buy(event, 2);
        var expiresAt = base.plus(TTL);

        // one tick before: the processor still sells, the sweeper would not release
        clock.set(expiresAt.minusNanos(1));
        assertThat(sweeper.execute().block(WAIT)).isEqualTo(new Summary(0, 0, 0, 0));

        // exactly at the limit: both treat the order as expired
        clock.set(expiresAt);
        var result = process.execute(byProcessor).block(WAIT);
        assertThat(result).isEqualTo(new ProcessOrderResult.ReleasedAsExpired(byProcessor));
        assertThat(sweeper.execute().block(WAIT)).isEqualTo(new Summary(1, 1, 0, 0));

        assertReleasedOnce(byProcessor, TicketStatus.RESERVED);
        assertReleasedOnce(bySweeper, TicketStatus.RESERVED);
        assertCounters(inventory(event), 10, 0, 0, 0);
    }

    @Test
    void execute_orderCreatedOneTickBeforeNowWindow_notExpiredWhileProcessorStillSellsIt() {
        var event = newEvent(10);
        var id = buy(event, 2);
        clock.set(base.plus(TTL).minusNanos(1));

        assertThat(sweeper.execute().block(WAIT)).isEqualTo(new Summary(0, 0, 0, 0));
        assertThat(process.execute(id).block(WAIT)).isEqualTo(new ProcessOrderResult.Sold(id));

        assertCounters(inventory(event), 8, 0, 0, 2);
    }

    @Test
    void execute_maxPerSweep_releasesInBatchesAcrossSweeps() {
        var event = newEvent(50);
        var ids = Flux.range(0, 7).map(i -> buy(event, 1)).collectList().block(WAIT);
        clock.set(base.plus(TTL).plusSeconds(1));
        var limited = sweeper(2, 3);

        assertThat(limited.execute().block(WAIT).released()).isEqualTo(3);
        assertCounters(inventory(event), 46, 4, 0, 0);
        assertThat(limited.execute().block(WAIT).released()).isEqualTo(3);
        assertThat(limited.execute().block(WAIT)).isEqualTo(new Summary(1, 1, 0, 0));
        assertCounters(inventory(event), 50, 0, 0, 0);
        assertThat(ids).allSatisfy(id -> assertReleasedOnce(id, TicketStatus.RESERVED));
    }

    @Test
    void execute_twoSweepsAndTwoUseCaseInstancesConcurrently_releaseEachOrderExactlyOnce() {
        var event = newEvent(200);
        var ids = Flux.range(0, 40).map(i -> buy(event, 1 + i % 3)).collectList().block(WAIT);
        // some of them in PENDING_CONFIRMATION, to cover both counters
        ids.stream().limit(10).forEach(id ->
                fulfillment.markPendingConfirmation(order(id), "crashed-worker", base.plusSeconds(1)).block(WAIT));
        clock.set(base.plus(TTL).plusSeconds(1));
        var other = sweeper(3, 500);

        var summaries = Flux.merge(
                        sweeper.execute().subscribeOn(Schedulers.parallel()),
                        other.execute().subscribeOn(Schedulers.parallel()),
                        sweeper.execute().subscribeOn(Schedulers.parallel()),
                        other.execute().subscribeOn(Schedulers.parallel()))
                .collectList().block(WAIT);

        assertThat(summaries.stream().mapToInt(Summary::released).sum()).isEqualTo(40);
        assertThat(summaries.stream().mapToInt(Summary::failed).sum()).isZero();
        assertThat(summaries.stream().mapToInt(s -> s.released() + s.skippedConflicts() + s.failed()).sum())
                .isEqualTo(summaries.stream().mapToInt(Summary::examined).sum());
        for (int i = 0; i < ids.size(); i++) {
            assertReleasedOnce(ids.get(i), i < 10 ? TicketStatus.PENDING_CONFIRMATION : TicketStatus.RESERVED);
        }
        assertCounters(inventory(event), 200, 0, 0, 0);
    }

    @Test
    void race_sweeperAndProcessorOnTheSameBoundaryOrders_exactlyOneReleasePerOrder() {
        var event = newEvent(100);
        var ids = Flux.range(0, 20).map(i -> buy(event, 1 + i % 2)).collectList().block(WAIT);
        int total = ids.stream().mapToInt(id -> order(id).quantity().value()).sum();
        clock.set(base.plus(TTL)); // exactly the boundary: both sides consider every order expired

        var releasedByProcessor = new AtomicInteger();
        var sweeps = Flux.merge(sweeper.execute().subscribeOn(Schedulers.parallel()),
                sweeper.execute().subscribeOn(Schedulers.parallel())).collectList();
        var processing = Flux.fromIterable(ids)
                .flatMap(id -> process.execute(id).subscribeOn(Schedulers.parallel()), 20)
                .doOnNext(r -> {
                    if (r instanceof ProcessOrderResult.ReleasedAsExpired) {
                        releasedByProcessor.incrementAndGet();
                    }
                    assertThat(r).isNotInstanceOf(ProcessOrderResult.Sold.class);
                }).collectList();

        var both = Mono.zip(sweeps, processing).block(WAIT);

        int releasedBySweepers = both.getT1().stream().mapToInt(Summary::released).sum();
        assertThat(releasedBySweepers + releasedByProcessor.get()).isEqualTo(20);
        assertThat(both.getT1().stream().mapToInt(Summary::failed).sum()).isZero();
        ids.forEach(id -> assertReleasedOnce(id, TicketStatus.RESERVED));
        assertCounters(inventory(event), 100, 0, 0, 0);
        assertThat(total).isPositive();
    }

    @Test
    void race_sweeperAfterExpiryAndProcessorJustBeforeExpiry_endsInExactlyOneOutcomePerOrder() {
        var event = newEvent(100);
        var ids = Flux.range(0, 20).map(i -> buy(event, 1 + i % 2)).collectList().block(WAIT);
        // The consumer's clock is one tick before the limit (valid: it sells); the sweeper's clock is at the
        // limit (expired: it releases). Whoever wins each step decides; the loser must back off cleanly.
        var consumerClock = new MutableClock(base.plus(TTL).minusNanos(1));
        var consumer = new ProcessOrderUseCase(orders, fulfillment, placement, consumerClock);
        clock.set(base.plus(TTL));

        var sweeps = Flux.merge(sweeper.execute().subscribeOn(Schedulers.parallel()),
                sweeper.execute().subscribeOn(Schedulers.parallel())).collectList();
        var processing = Flux.fromIterable(ids)
                .flatMap(id -> consumer.execute(id).subscribeOn(Schedulers.parallel()), 20).collectList();

        Mono.zip(sweeps, processing).block(WAIT);
        // a sweep that lost a race on a status the consumer moved (RESERVED -> PENDING) leaves it for the next one
        sweeper.execute().block(WAIT);

        int sold = 0;
        int soldTickets = 0;
        for (var id : ids) {
            var order = order(id);
            assertThat(order.status()).isIn(TicketStatus.SOLD, TicketStatus.AVAILABLE);
            var entries = trail(id);
            boolean wasSold = entries.stream().anyMatch(e -> e.to() == TicketStatus.SOLD);
            boolean wasReleased = entries.stream().anyMatch(e -> e.to() == TicketStatus.AVAILABLE
                    && e.from() != TicketStatus.AVAILABLE);
            assertThat(wasSold ^ wasReleased).as("exactly one outcome for %s: %s", id.value(), entries).isTrue();
            assertThat(wasSold).isEqualTo(order.status() == TicketStatus.SOLD);
            if (wasSold) {
                sold++;
                soldTickets += order.quantity().value();
            }
        }
        var inv = inventory(event);
        assertCounters(inv, 100 - soldTickets, 0, 0, soldTickets);
        assertThat(sold).isBetween(0, 20);
    }
}
