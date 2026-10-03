package com.ticketflow.concurrency;

import static com.ticketflow.concurrency.ConcurrencySupport.fireAll;
import static com.ticketflow.concurrency.ConcurrencySupport.newKey;
import static com.ticketflow.concurrency.ConcurrencySupport.orderIdFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketflow.concurrency.ConcurrencySupport.Availability;
import com.ticketflow.concurrency.ConcurrencySupport.Call;
import com.ticketflow.concurrency.ConcurrencySupport.Response;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderPlacementRepository;
import com.ticketflow.domain.port.OrderQueuePublisher;
import com.ticketflow.domain.port.OrderRepository;
import com.ticketflow.infrastructure.messaging.SqsTestQueues;
import com.ticketflow.infrastructure.web.E2eContainers;
import com.ticketflow.usecase.BusinessMetrics;
import com.ticketflow.usecase.RequestPurchaseCommand;
import com.ticketflow.usecase.RequestPurchaseResult;
import com.ticketflow.usecase.RequestPurchaseUseCase;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * End-to-end adversarial purchases (scenarios 1, 2, 3, 8 and 9 of F-022): the whole application (RANDOM_PORT, real
 * HTTP with hundreds of requests in flight, the real SQS consumer running) against REAL DynamoDB Local and
 * LocalStack SQS. Every scenario ends with the {@link Reconciliation} check over the three tables. Assertions are on
 * final states and on invariants sampled during the run, never on timing. Enabled with INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PurchaseConcurrencyIT {

    private static final String ADMIN_KEY = "concurrency-admin-key-" + UUID.randomUUID();
    private static SqsTestQueues.Queues queues;

    @BeforeAll
    static void startInfrastructure() {
        E2eContainers.start();
        queues = E2eContainers.newQueue("conc-purchases");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        E2eContainers.register(registry, queues.name());
        E2eContainers.generousRateLimits(registry);
        registry.add("ticketflow.admin.api-key", () -> ADMIN_KEY);
        registry.add("ticketflow.sqs.consumer.enabled", () -> "true");
        registry.add("ticketflow.sqs.consumer.wait-time", () -> "1s");
        registry.add("ticketflow.sqs.consumer.concurrency", () -> "8");
    }

    /**
     * Counts, on the server side, how far the purchase requests got: exchanges started/ended/cancelled and purchase
     * chains running. Used only to know when work that outlives a cancelled request has finished (scenario 9).
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Probes {

        @Bean
        @Primary
        TrackedPurchases trackedPurchases(OrderPlacementRepository placement, OrderRepository orders,
                                          OrderQueuePublisher queue, Clock clock,
                                          @Value("${ticketflow.reservation.ttl:PT10M}") Duration ttl,
                                          BusinessMetrics metrics) {
            return new TrackedPurchases(placement, orders, queue, clock, ttl, metrics);
        }

        @Bean
        ExchangeProbe exchangeProbe() {
            return new ExchangeProbe();
        }
    }

    static final class TrackedPurchases extends RequestPurchaseUseCase {
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger();

        TrackedPurchases(OrderPlacementRepository placement, OrderRepository orders, OrderQueuePublisher queue,
                         Clock clock, Duration ttl, BusinessMetrics metrics) {
            super(placement, orders, queue, clock, ttl, metrics);
        }

        @Override
        public Mono<RequestPurchaseResult> execute(RequestPurchaseCommand command) {
            return Mono.defer(() -> {
                started.incrementAndGet();
                running.incrementAndGet();
                return super.execute(command).doFinally(signal -> running.decrementAndGet());
            });
        }
    }

    static final class ExchangeProbe implements WebFilter, Ordered {
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger ended = new AtomicInteger();
        final AtomicInteger cancelled = new AtomicInteger();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            if (HttpMethod.POST.equals(exchange.getRequest().getMethod())
                    && exchange.getRequest().getPath().value().equals("/orders")) {
                started.incrementAndGet();
                return chain.filter(exchange).doFinally(signal -> {
                    ended.incrementAndGet();
                    if (signal == SignalType.CANCEL) {
                        cancelled.incrementAndGet();
                    }
                });
            }
            return chain.filter(exchange);
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 30;
        }
    }

    @Autowired
    private DynamoDbAsyncClient dynamo;
    @Autowired
    private TrackedPurchases purchases;
    @Autowired
    private ExchangeProbe exchanges;
    @LocalServerPort
    private int port;

    private ConcurrencySupport support;

    @BeforeEach
    void setUp() {
        ConcurrencySupport.awaitTables(dynamo);
        support = new ConcurrencySupport(port, dynamo, ADMIN_KEY);
    }

    private static int accepted(List<Call> calls) {
        return calls.stream().filter(Call::accepted).mapToInt(Call::quantity).sum();
    }

    private static List<Call> rejected(List<Call> calls) {
        return calls.stream().filter(c -> !c.accepted()).toList();
    }

    private static void assertOnlyAcceptedOrInsufficient(List<Call> calls) {
        assertThat(calls).allSatisfy(call -> {
            if (!call.accepted()) {
                assertThat(call.response().status()).as(call.response().body()).isEqualTo(409);
                assertThat(call.response().problem()).isEqualTo("insufficient-inventory");
            }
        });
    }

    // ---------------------------------------------------------------- scenario 1

    @Test
    void highContentionPurchases_neverOversell_andEveryAcceptedOrderEndsSold() {
        int capacity = 100;
        String eventId = support.createEvent(capacity);
        Random random = new Random(22);
        List<Mono<Call>> requests = IntStream.range(0, 300)
                .mapToObj(i -> support.purchase(newKey(), eventId, 1 + random.nextInt(3))).toList();

        List<Call> calls;
        ConcurrencySupport.Sampler sampler = support.sample(eventId);
        try (sampler) {
            calls = fireAll(requests);
            support.awaitProcessed(eventId);
        }

        assertThat(sampler.samples()).as("the sampler ran during the load").isPositive();
        assertThat(sampler.errors()).isEmpty();
        assertThat(sampler.violations()).as("samples that broke the invariant").isEmpty();
        assertThat(calls).hasSize(300);
        assertOnlyAcceptedOrInsufficient(calls);
        int acceptedQuantity = accepted(calls);
        assertThat(acceptedQuantity).isLessThanOrEqualTo(capacity);
        // Nothing is ever released here, so availability only goes down: after a rejection it stayed below that
        // request's quantity, and the sold-out state is reached unless the capacity was never contested.
        assertThat(rejected(calls)).as("capacity 100 against ~600 requested tickets").isNotEmpty();
        int smallestRejected = rejected(calls).stream().mapToInt(Call::quantity).min().orElseThrow();
        Availability end = support.availability(eventId);
        assertThat(end.available()).isLessThan(smallestRejected);
        assertThat(end.sold()).isEqualTo(acceptedQuantity);
        assertThat(end.reserved() + end.pendingConfirmation() + end.complimentary()).isZero();

        var report = support.reconcile(eventId, calls, true);
        assertThat(report.orders()).allSatisfy(order -> assertThat(order.status()).isEqualTo(TicketStatus.SOLD));
        assertThat(report.quantityIn(TicketStatus.SOLD)).isEqualTo(acceptedQuantity);
    }

    // ---------------------------------------------------------------- scenario 2

    @Test
    void sameKeyRetryStorm_createsExactlyOneOrderAndOneReservation() {
        String eventId = support.createEvent(50);
        String key = newKey();
        List<Mono<Call>> requests = IntStream.range(0, 100).mapToObj(i -> support.purchase(key, eventId, 2)).toList();

        List<Call> calls = fireAll(requests);

        assertThat(calls).allSatisfy(call -> assertThat(call.response().status()).as(call.response().body())
                .isEqualTo(202));
        assertThat(calls.stream().map(call -> call.response().orderId()).collect(Collectors.toSet()))
                .containsExactly(orderIdFor(key));
        support.awaitProcessed(eventId);
        Availability end = support.availability(eventId);
        assertThat(end.sold()).as("one reservation of 2 tickets, however many retries").isEqualTo(2);
        assertThat(end.available()).isEqualTo(48);
        var report = support.reconcile(eventId, calls, true);
        assertThat(report.orders()).hasSize(1);
        assertThat(report.order(orderIdFor(key)).audit()).hasSize(3); // reserved, pending, sold: sold once
    }

    @Test
    void sameKeyWithDifferentPayloadsInParallel_onePayloadWins_theOtherIs409_noExtraReservation() {
        String eventId = support.createEvent(50);
        String key = newKey();
        List<Mono<Call>> requests = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            requests.add(support.purchase(key, eventId, i % 2 == 0 ? 2 : 3));
        }

        List<Call> calls = fireAll(requests);

        Map<Integer, List<Call>> byQuantity = calls.stream().collect(Collectors.groupingBy(Call::quantity));
        List<Call> winners = byQuantity.values().stream().filter(group -> group.get(0).accepted()).findFirst()
                .orElseThrow(() -> new AssertionError("no payload won: " + calls));
        List<Call> losers = byQuantity.values().stream().filter(group -> !group.get(0).accepted()).findFirst()
                .orElseThrow();
        assertThat(winners).hasSize(50).allSatisfy(call -> assertThat(call.accepted()).isTrue());
        assertThat(losers).hasSize(50).allSatisfy(call -> {
            assertThat(call.response().status()).isEqualTo(409);
            assertThat(call.response().problem()).isEqualTo("idempotency-key-reused");
        });
        int winningQuantity = winners.get(0).quantity();
        support.awaitProcessed(eventId);
        Availability end = support.availability(eventId);
        assertThat(end.sold()).as("only the winning payload reserved").isEqualTo(winningQuantity);
        assertThat(support.reconcile(eventId, calls, true).orders()).hasSize(1);
    }

    // ---------------------------------------------------------------- scenario 3

    @Test
    void mixedPurchasesComplimentaryAndReads_neverExceedCapacity_complimentaryIsNeverSold() {
        int capacity = 60;
        String eventId = support.createEvent(capacity);
        Random random = new Random(3022);
        List<Mono<Call>> purchaseRequests = IntStream.range(0, 150)
                .mapToObj(i -> support.purchase(newKey(), eventId, 1 + random.nextInt(3))).toList();
        List<Mono<Call>> complimentaryRequests = IntStream.range(0, 40)
                .mapToObj(i -> support.complimentary(newKey(), eventId, 1 + random.nextInt(2))).toList();
        List<Mono<Object>> all = new ArrayList<>();
        purchaseRequests.forEach(request -> all.add(request.map(call -> (Object) call)));
        complimentaryRequests.forEach(request -> all.add(request.map(call -> (Object) call)));
        for (int i = 0; i < 100; i++) {
            all.add(Mono.fromSupplier(() -> (Object) support.availability(eventId))
                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()));
        }

        List<Object> results;
        ConcurrencySupport.Sampler sampler = support.sample(eventId);
        try (sampler) {
            results = fireAll(all);
            support.awaitProcessed(eventId);
        }

        List<Call> calls = results.stream().filter(Call.class::isInstance).map(Call.class::cast).toList();
        assertThat(sampler.violations()).isEmpty();
        assertThat(sampler.errors()).isEmpty();
        assertThat(calls).hasSize(190);
        // A purchase answers 202, a complimentary issuance 201; the rest are insufficient-inventory conflicts.
        int soldExpected = calls.stream().filter(c -> c.response().status() == 202).mapToInt(Call::quantity).sum();
        int complimentaryExpected = calls.stream().filter(c -> c.response().status() == 201)
                .mapToInt(Call::quantity).sum();
        assertThat(calls).allSatisfy(call -> {
            if (!call.accepted()) {
                assertThat(call.response().status()).as(call.response().body()).isEqualTo(409);
                assertThat(call.response().problem()).isEqualTo("insufficient-inventory");
            }
        });
        assertThat(soldExpected + complimentaryExpected).isLessThanOrEqualTo(capacity);
        Availability end = support.availability(eventId);
        assertThat(end.sold()).as("complimentary tickets are never counted as sold").isEqualTo(soldExpected);
        assertThat(end.complimentary()).isEqualTo(complimentaryExpected);
        assertThat(end.available()).isEqualTo(capacity - soldExpected - complimentaryExpected);
        assertThat(rejectedMin(calls)).as("sold out when something was rejected").isGreaterThan(end.available());

        var report = support.reconcile(eventId, calls, true);
        assertThat(report.quantityIn(TicketStatus.COMPLIMENTARY)).isEqualTo(complimentaryExpected);
        assertThat(report.quantityIn(TicketStatus.SOLD)).isEqualTo(soldExpected);
    }

    private static int rejectedMin(List<Call> calls) {
        return calls.stream().filter(c -> !c.accepted()).mapToInt(Call::quantity).min().orElse(Integer.MAX_VALUE);
    }

    // ---------------------------------------------------------------- scenario 8

    @Test
    void purchaseOfNonExistentEvent_is404EndToEnd_andReservesNothing() {
        String missing = "no-such-event-" + UUID.randomUUID();
        String key = newKey();

        Response first = support.purchaseResponse(key, missing, 2).block(ConcurrencySupport.WAIT);
        assertThat(first.status()).as(first.body()).isEqualTo(404);
        assertThat(first.problem()).isEqualTo("event-not-found");
        assertThat(support.get("/orders/{id}", orderIdFor(key)).status()).as("no order was created").isEqualTo(404);
        assertThat(support.get("/events/{id}/availability", missing).status()).isEqualTo(404);

        // The same under concurrency, next to a real event: nothing reserved anywhere.
        String real = support.createEvent(10);
        List<Mono<Call>> requests = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            requests.add(support.purchase(newKey(), missing, 1));
        }
        List<Call> calls = fireAll(requests);
        assertThat(calls).allSatisfy(call -> {
            assertThat(call.response().status()).isEqualTo(404);
            assertThat(call.response().problem()).isEqualTo("event-not-found");
            assertThat(support.get("/orders/{id}", call.orderId()).status()).isEqualTo(404);
        });
        assertThat(support.availability(real)).isEqualTo(new Availability(10, 0, 0, 0, 0, 10));
        support.reconcile(real);
    }

    // ---------------------------------------------------------------- scenario 9

    @Test
    void clientsCancellingMidFlight_leaveNoReservationWithoutOrder_andNoOrderWithoutItsMessage() {
        String eventId = support.createEvent(500);
        Random random = new Random(9022);
        List<String> keys = IntStream.range(0, 120).mapToObj(i -> newKey()).toList();
        List<Mono<Call>> requests = keys.stream().map(key -> support.purchase(key, eventId, 1)
                .timeout(Duration.ofMillis(1 + random.nextInt(25)))
                .onErrorResume(error -> Mono.empty())).toList();

        fireAll(requests);

        assertThat(exchanges.cancelled.get()).as("some requests really were cancelled server-side").isPositive();
        awaitPurchaseWorkFinished();
        // Everything that was created is processed by the consumer: an order stranded without its message would stay
        // RESERVED (the reservation lasts 10 minutes) and fail this wait.
        support.awaitProcessed(eventId);
        awaitPurchaseWorkFinished();
        support.awaitProcessed(eventId);
        var afterCancels = support.reconcile(eventId);
        assertThat(afterCancels.orders()).allSatisfy(order -> assertThat(order.status()).isEqualTo(TicketStatus.SOLD));
        assertThat(afterCancels.orders().size()).isLessThanOrEqualTo(keys.size());

        // The clients retry every key: all of them converge on exactly one order each.
        List<Mono<Call>> retries = keys.stream().map(key -> support.purchase(key, eventId, 1)).toList();
        List<Call> calls = fireAll(retries);
        assertThat(calls).allSatisfy(call -> {
            assertThat(call.response().status()).as(call.response().body()).isEqualTo(202);
            assertThat(call.response().orderId()).isEqualTo(call.orderId());
        });
        support.awaitProcessed(eventId);
        var report = support.reconcile(eventId, calls, true);
        assertThat(report.orders()).hasSize(keys.size());
        assertThat(report.countIn(TicketStatus.SOLD)).isEqualTo(keys.size());
        assertThat(support.availability(eventId).sold()).isEqualTo(keys.size());
    }

    /** Server side quiet: every exchange ended, no purchase chain running, and it stays so for a moment. */
    private void awaitPurchaseWorkFinished() {
        AtomicInteger lastStarted = new AtomicInteger(-1);
        await().atMost(ConcurrencySupport.WAIT).pollInterval(Duration.ofMillis(300)).until(() -> {
            int started = purchases.started.get();
            boolean quiet = purchases.running.get() == 0 && exchanges.started.get() == exchanges.ended.get()
                    && started == lastStarted.get();
            lastStarted.set(started);
            return quiet;
        });
    }
}
