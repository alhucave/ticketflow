package com.ticketflow.concurrency;

import static com.ticketflow.concurrency.ConcurrencySupport.fireAll;
import static com.ticketflow.concurrency.ConcurrencySupport.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketflow.concurrency.ConcurrencySupport.Call;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderPlacementRepository;
import com.ticketflow.domain.port.OrderRepository;
import com.ticketflow.infrastructure.config.ExpirationProperties;
import com.ticketflow.infrastructure.config.SqsConsumerProperties;
import com.ticketflow.infrastructure.messaging.SqsOrderConsumer;
import com.ticketflow.infrastructure.messaging.SqsTestQueues;
import com.ticketflow.infrastructure.scheduler.ReservationExpirationScheduler;
import com.ticketflow.infrastructure.web.E2eContainers;
import com.ticketflow.usecase.ProcessOrderUseCase;
import com.ticketflow.usecase.ReleaseExpiredReservationsUseCase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Expiry under load (scenario 6 of F-022). The context has a short reservation TTL and neither the consumer nor the
 * scheduler enabled: each test starts the actors it needs, built from the real beans against the real tables, so
 * that two sweepers (two use-case instances, as two application instances would be) and the consumer can be raced
 * deliberately. Enabled with INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpiryUnderLoadIT {

    private static final String ADMIN_KEY = "concurrency-admin-key-" + UUID.randomUUID();
    private static SqsTestQueues.Queues queues;
    private static SqsAsyncClient sqs;

    @BeforeAll
    static void startInfrastructure() {
        sqs = E2eContainers.start();
        queues = E2eContainers.newQueue("conc-expiry");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        E2eContainers.register(registry, queues.name());
        E2eContainers.generousRateLimits(registry);
        registry.add("ticketflow.admin.api-key", () -> ADMIN_KEY);
        registry.add("ticketflow.reservation.ttl", () -> "PT4S");
    }

    @Autowired
    private DynamoDbAsyncClient dynamo;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private OrderPlacementRepository placement;
    @Autowired
    private ProcessOrderUseCase processOrder;
    @Autowired
    private Clock clock;
    @LocalServerPort
    private int port;

    private ConcurrencySupport support;

    @BeforeEach
    void setUp() {
        ConcurrencySupport.awaitTables(dynamo);
        support = new ConcurrencySupport(port, dynamo, ADMIN_KEY);
    }

    private ReleaseExpiredReservationsUseCase newSweep() {
        return new ReleaseExpiredReservationsUseCase(orders, placement, clock, 4, 500);
    }

    private static void awaitAfter(Instant instant) {
        await().atMost(ConcurrencySupport.WAIT).pollInterval(Duration.ofMillis(100))
                .until(() -> !Instant.now().isBefore(instant));
    }

    @Test
    void twoConcurrentSweepersReleaseEveryExpiredReservationExactlyOnce_andThePlaceIsSellableAgain() throws Exception {
        int capacity = 100;
        String eventId = support.createEvent(capacity);
        Random random = new Random(6022);
        List<Mono<Call>> requests = IntStream.range(0, 200)
                .mapToObj(i -> support.purchase(newKey(), eventId, 1 + random.nextInt(3))).toList();
        List<Call> calls = fireAll(requests);
        List<Call> accepted = calls.stream().filter(Call::accepted).toList();
        assertThat(accepted).isNotEmpty();
        assertThat(support.availability(eventId).reserved()).isEqualTo(accepted.stream().mapToInt(Call::quantity).sum());
        // Nothing consumes the queue: only expiry can free these reservations.
        awaitAfter(accepted.stream().map(c -> c.response().reservationExpiresAt()).max(Instant::compareTo)
                .orElseThrow());

        CyclicBarrier startTogether = new CyclicBarrier(2);
        AtomicInteger released = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> sweepers = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                ReleaseExpiredReservationsUseCase sweep = newSweep();
                sweepers.add(pool.submit(() -> {
                    startTogether.await();
                    int rounds = 0;
                    while (rounds++ < 3 || support.availability(eventId).reserved() > 0) {
                        released.addAndGet(sweep.execute().block(ConcurrencySupport.WAIT).released());
                    }
                    return null;
                }));
            }
            for (Future<?> sweeper : sweepers) {
                sweeper.get(ConcurrencySupport.WAIT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(released.get()).as("each order was released by exactly one sweeper").isEqualTo(accepted.size());
        assertThat(support.availability(eventId).available()).isEqualTo(capacity);
        var report = support.reconcile(eventId, calls, true);
        assertThat(report.orders()).allSatisfy(order -> {
            assertThat(order.status()).isEqualTo(TicketStatus.AVAILABLE);
            assertThat(order.audit()).as("reserved, then released once").hasSize(2);
        });

        // The released capacity can be bought again.
        List<Mono<Call>> again = IntStream.range(0, 10).mapToObj(i -> support.purchase(newKey(), eventId, 1)).toList();
        List<Call> second = fireAll(again);
        assertThat(second).allSatisfy(call -> assertThat(call.accepted()).isTrue());
        assertThat(support.availability(eventId).reserved()).isEqualTo(10);
        support.reconcile(eventId, concat(calls, second), true);
    }

    private static List<Call> concat(List<Call> a, List<Call> b) {
        List<Call> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    @Test
    void consumerRacingTwoSweepersAtTheExpiryBoundary_everyOrderEndsInExactlyOneTerminalOutcome() {
        String eventId = support.createEvent(150);
        List<Mono<Call>> requests = IntStream.range(0, 100).mapToObj(i -> support.purchase(newKey(), eventId, 1))
                .toList();
        List<Call> calls = fireAll(requests);
        assertThat(calls).allSatisfy(call -> assertThat(call.accepted()).isTrue());
        List<Instant> expiries = calls.stream().map(c -> c.response().reservationExpiresAt()).sorted().toList();
        // Start when the first orders have expired and the rest are about to: the boundary runs through the batch.
        Instant startAt = expiries.get(10);
        awaitAfter(startAt);

        var consumer = new SqsOrderConsumer(sqs, Mono.just(queues.url()), processOrder,
                new SqsConsumerProperties(true, 10, Duration.ofSeconds(1), Duration.ofSeconds(2), 8,
                        Duration.ofSeconds(10), Duration.ofMillis(100), Duration.ofSeconds(1)));
        ExpirationProperties sweepProperties =
                new ExpirationProperties(true, Duration.ofMillis(100), Duration.ZERO, 4, 500, Duration.ofSeconds(10));
        var sweeperA = new ReservationExpirationScheduler(newSweep(), sweepProperties);
        var sweeperB = new ReservationExpirationScheduler(newSweep(), sweepProperties);
        Instant started = Instant.now();
        try {
            consumer.start();
            sweeperA.start();
            sweeperB.start();
            List<String> ids = calls.stream().map(Call::orderId).toList();
            await().atMost(ConcurrencySupport.WAIT).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                    assertThat(ids).allSatisfy(id -> assertThat(support.orderStatus(id)).isIn("SOLD", "AVAILABLE")));
            support.awaitProcessed(eventId);
        } finally {
            consumer.stop();
            sweeperA.stop();
            sweeperB.stop();
            await().atMost(ConcurrencySupport.WAIT).until(() -> !consumer.isRunning() && !sweeperA.isRunning()
                    && !sweeperB.isRunning());
        }

        var report = support.reconcile(eventId, calls, true);
        assertThat(report.orders()).allSatisfy(order -> {
            long terminal = order.audit().stream()
                    .filter(e -> e.to() == TicketStatus.SOLD || e.to() == TicketStatus.AVAILABLE).count();
            assertThat(terminal).as("exactly one terminal outcome of %s: %s", order.orderId(), order.audit())
                    .isEqualTo(1);
        });
        // What was already expired when the actors started can only have been released, never sold.
        for (Call call : calls) {
            if (!call.response().reservationExpiresAt().isAfter(started)) {
                assertThat(report.order(call.orderId()).status()).isEqualTo(TicketStatus.AVAILABLE);
            }
        }
        assertThat(support.availability(eventId).sold()).isEqualTo(report.quantityIn(TicketStatus.SOLD));
    }
}
