package com.ticketflow.concurrency;

import static com.ticketflow.concurrency.ConcurrencySupport.fireAll;
import static com.ticketflow.concurrency.ConcurrencySupport.newKey;
import static com.ticketflow.concurrency.ConcurrencySupport.orderIdFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketflow.concurrency.ConcurrencySupport.Call;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderAuditEntry;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderFulfillmentRepository;
import com.ticketflow.infrastructure.messaging.SqsTestQueues;
import com.ticketflow.infrastructure.persistence.DynamoDbOrderFulfillmentRepository;
import com.ticketflow.infrastructure.web.E2eContainers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Failure injection (scenario 5 of F-022) with the real consumer, queue, DLQ and expiration job: a test-only
 * decorator around the real fulfillment adapter (wired through a {@code @TestConfiguration}, no hook in production
 * code) makes processing fail for chosen orders, either before the first step (order stays RESERVED) or between the
 * two steps (order stays PENDING_CONFIRMATION). Transient failures must be redelivered by SQS and end SOLD;
 * permanent ones must reach the DLQ with their reservation intact and then be released by the expiration job.
 * Enabled with INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FailureInjectionIT {

    private static final Duration TTL = Duration.ofSeconds(25);
    private static SqsTestQueues.Queues queues;
    private static SqsAsyncClient sqs;

    @BeforeAll
    static void startInfrastructure() {
        sqs = E2eContainers.start();
        queues = E2eContainers.newQueue("conc-failures");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        E2eContainers.register(registry, queues.name());
        E2eContainers.generousRateLimits(registry);
        registry.add("ticketflow.reservation.ttl", TTL::toString);
        registry.add("ticketflow.sqs.consumer.enabled", () -> "true");
        registry.add("ticketflow.sqs.consumer.wait-time", () -> "1s");
        registry.add("ticketflow.sqs.consumer.concurrency", () -> "8");
        registry.add("ticketflow.sqs.consumer.visibility-timeout", () -> "2s");
        registry.add("ticketflow.expiration.enabled", () -> "true");
        registry.add("ticketflow.expiration.interval", () -> "1s");
        registry.add("ticketflow.expiration.initial-delay", () -> "0s");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Faults {

        @Bean
        @Primary
        FaultyFulfillment faultyFulfillment(DynamoDbOrderFulfillmentRepository real) {
            return new FaultyFulfillment(real);
        }
    }

    /** Fails chosen orders at chosen steps, then (for transient faults) lets the real adapter through. */
    static final class FaultyFulfillment implements OrderFulfillmentRepository {
        private final OrderFulfillmentRepository real;
        final Map<String, AtomicInteger> markFailures = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> confirmFailures = new ConcurrentHashMap<>();

        FaultyFulfillment(OrderFulfillmentRepository real) {
            this.real = real;
        }

        private static boolean fails(Map<String, AtomicInteger> faults, Order order) {
            AtomicInteger left = faults.get(order.id().value());
            return left != null && left.getAndDecrement() > 0;
        }

        @Override
        public Mono<OrderAuditEntry> markPendingConfirmation(Order order, String actor, Instant at) {
            return fails(markFailures, order) ? Mono.error(new IllegalStateException("injected failure (mark)"))
                    : real.markPendingConfirmation(order, actor, at);
        }

        @Override
        public Mono<OrderAuditEntry> confirmSale(Order order, String actor, Instant at) {
            return fails(confirmFailures, order) ? Mono.error(new IllegalStateException("injected failure (confirm)"))
                    : real.confirmSale(order, actor, at);
        }
    }

    @Autowired
    private DynamoDbAsyncClient dynamo;
    @Autowired
    private FaultyFulfillment faults;
    @LocalServerPort
    private int port;

    private ConcurrencySupport support;

    @BeforeEach
    void setUp() {
        ConcurrencySupport.awaitTables(dynamo);
        support = new ConcurrencySupport(port, dynamo, "unused");
    }

    @Test
    void transientFailuresAreRedeliveredAndSold_permanentOnesReachTheDlqAndAreReleasedByTheExpirationJob() {
        int capacity = 200;
        String eventId = support.createEvent(capacity);
        int group = 10;
        Random random = new Random(5022);
        List<String> transientMark = keys(group);
        List<String> transientConfirm = keys(group);
        List<String> permanentMark = keys(group);
        List<String> permanentConfirm = keys(group);
        List<String> healthy = keys(2 * group);
        // Transient: the first 2 attempts fail (maxReceiveCount is 3, so the 3rd delivery succeeds).
        transientMark.forEach(k -> faults.markFailures.put(orderIdFor(k), new AtomicInteger(2)));
        transientConfirm.forEach(k -> faults.confirmFailures.put(orderIdFor(k), new AtomicInteger(2)));
        permanentMark.forEach(k -> faults.markFailures.put(orderIdFor(k), new AtomicInteger(Integer.MAX_VALUE)));
        permanentConfirm.forEach(k -> faults.confirmFailures.put(orderIdFor(k), new AtomicInteger(Integer.MAX_VALUE)));

        List<String> allKeys = new ArrayList<>();
        for (var keys : List.of(transientMark, transientConfirm, permanentMark, permanentConfirm, healthy)) {
            allKeys.addAll(keys);
        }
        List<Mono<Call>> requests = allKeys.stream().map(k -> support.purchase(k, eventId, 1 + random.nextInt(2)))
                .toList();
        List<Call> calls = fireAll(requests);
        assertThat(calls).allSatisfy(call -> assertThat(call.accepted()).isTrue());
        Map<String, Call> byKey = calls.stream().collect(Collectors.toMap(Call::key, c -> c));
        Instant earliestExpiry = calls.stream().map(c -> c.response().reservationExpiresAt()).min(Instant::compareTo)
                .orElseThrow();

        List<String> okIds = new ArrayList<>();
        for (var keys : List.of(transientMark, transientConfirm, healthy)) {
            keys.forEach(k -> okIds.add(orderIdFor(k)));
        }
        support.awaitStatuses(okIds, "SOLD");
        // Permanent failures exhausted their deliveries: the real DLQ holds exactly one message per such order.
        await().atMost(ConcurrencySupport.WAIT).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(SqsTestQueues.total(sqs, queues.dlqUrl())).isEqualTo(2 * group));
        // Reservation intact while the DLQ holds them (judged only while the TTL has not run out).
        if (Instant.now().isBefore(earliestExpiry.minusSeconds(2))) {
            permanentMark.forEach(k -> assertThat(support.orderStatus(orderIdFor(k))).isEqualTo("RESERVED"));
            permanentConfirm.forEach(k -> assertThat(support.orderStatus(orderIdFor(k)))
                    .isEqualTo("PENDING_CONFIRMATION"));
        }

        // The expiration job returns everything permanent to AVAILABLE once the TTL elapses.
        List<String> permanentIds = new ArrayList<>();
        permanentMark.forEach(k -> permanentIds.add(orderIdFor(k)));
        permanentConfirm.forEach(k -> permanentIds.add(orderIdFor(k)));
        support.awaitStatuses(permanentIds, "AVAILABLE");
        support.awaitProcessed(eventId);

        int soldQuantity = okIds.stream().mapToInt(id -> byKey.values().stream().filter(c -> c.orderId().equals(id))
                .findFirst().orElseThrow().quantity()).sum();
        var end = support.availability(eventId);
        assertThat(end.sold()).isEqualTo(soldQuantity);
        assertThat(end.available()).as("nothing leaked").isEqualTo(capacity - soldQuantity);
        var report = support.reconcile(eventId, calls, true);
        assertThat(report.countIn(TicketStatus.SOLD)).isEqualTo(okIds.size());
        assertThat(report.countIn(TicketStatus.AVAILABLE)).isEqualTo(permanentIds.size());
        // The DLQ messages are the permanent orders' (and only theirs).
        Set<String> dlqOrders = ConcurrencySupport.drain(sqs, queues.dlqUrl()).stream()
                .map(body -> body.replaceAll(".*\"orderId\":\"([^\"]*)\".*", "$1")).collect(Collectors.toSet());
        assertThat(dlqOrders).containsExactlyInAnyOrderElementsOf(permanentIds);
        ConcurrencySupport.awaitEmpty(sqs, queues.url());
    }

    private static List<String> keys(int count) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            keys.add(newKey());
        }
        return keys;
    }
}
