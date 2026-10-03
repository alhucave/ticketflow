package com.ticketflow.concurrency;

import static com.ticketflow.concurrency.ConcurrencySupport.fireAll;
import static com.ticketflow.concurrency.ConcurrencySupport.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketflow.concurrency.ConcurrencySupport.Call;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.infrastructure.messaging.SqsOrderConsumer;
import com.ticketflow.infrastructure.messaging.SqsTestQueues;
import com.ticketflow.infrastructure.scheduler.ReservationExpirationScheduler;
import com.ticketflow.infrastructure.web.E2eContainers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * Message-level adversaries over the REAL queue (scenarios 4 and 7 of F-022): duplicated and redelivered messages,
 * poison messages that must reach the real DLQ without blocking anything, and repeated stop()/start() of the real
 * consumer and expiration scheduler beans while messages are in flight. Each scenario ends with the
 * {@link Reconciliation} check. Enabled with INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MessageRedeliveryIT {

    private static final String ADMIN_KEY = "concurrency-admin-key-" + UUID.randomUUID();
    private static SqsTestQueues.Queues queues;
    private static SqsAsyncClient sqs;

    @BeforeAll
    static void startInfrastructure() {
        sqs = E2eContainers.start();
        queues = E2eContainers.newQueue("conc-redelivery");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        E2eContainers.register(registry, queues.name());
        E2eContainers.generousRateLimits(registry);
        registry.add("ticketflow.admin.api-key", () -> ADMIN_KEY);
        registry.add("ticketflow.sqs.consumer.enabled", () -> "true");
        registry.add("ticketflow.sqs.consumer.wait-time", () -> "1s");
        registry.add("ticketflow.sqs.consumer.concurrency", () -> "8");
        // Short visibility: poison messages cycle through their receive attempts (3) to the DLQ within seconds.
        registry.add("ticketflow.sqs.consumer.visibility-timeout", () -> "2s");
        registry.add("ticketflow.expiration.enabled", () -> "true");
        registry.add("ticketflow.expiration.interval", () -> "1s");
        registry.add("ticketflow.expiration.initial-delay", () -> "0s");
    }

    @Autowired
    private DynamoDbAsyncClient dynamo;
    @Autowired
    private SqsOrderConsumer consumer;
    @Autowired
    private ReservationExpirationScheduler scheduler;
    @LocalServerPort
    private int port;

    private ConcurrencySupport support;

    @BeforeEach
    void setUp() {
        ConcurrencySupport.awaitTables(dynamo);
        support = new ConcurrencySupport(port, dynamo, ADMIN_KEY);
    }

    private static void awaitDlqCount(int expected) {
        await().atMost(ConcurrencySupport.WAIT).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(SqsTestQueues.total(sqs, queues.dlqUrl())).as("messages in the DLQ").isEqualTo(expected));
    }

    // ---------------------------------------------------------------- scenario 4

    @Test
    void duplicatedAndRedeliveredMessages_sellEachOrderExactlyOnce_andPoisonGoesToTheDlq() {
        String eventId = support.createEvent(100);
        // Paused consumer: the real messages wait in the real queue, so the duplicates race each other when it starts.
        consumer.stop();
        Random random = new Random(4022);
        List<Mono<Call>> requests = IntStream.range(0, 30)
                .mapToObj(i -> support.purchase(newKey(), eventId, 1 + random.nextInt(2))).toList();
        List<Call> calls = fireAll(requests);
        assertThat(calls).allSatisfy(call -> assertThat(call.accepted()).isTrue());
        int expectedSold = calls.stream().mapToInt(Call::quantity).sum();

        for (Call call : calls) {
            for (int copy = 0; copy < 6; copy++) {
                ConcurrencySupport.send(sqs, queues.url(), ConcurrencySupport.orderMessage(call.orderId()));
            }
        }
        List<String> poison = List.of("this is not json", "{\"version\":2,\"orderId\":\"" + calls.get(0).orderId()
                + "\"}", "{\"version\":1}");
        poison.forEach(body -> ConcurrencySupport.send(sqs, queues.url(), body));
        consumer.start();

        support.awaitProcessed(eventId);
        List<String> orderIds = calls.stream().map(Call::orderId).toList();
        support.awaitStatuses(orderIds, "SOLD");
        awaitDlqCount(poison.size());
        ConcurrencySupport.awaitEmpty(sqs, queues.url());

        var report = support.reconcile(eventId, calls, true);
        assertThat(support.availability(eventId).sold()).as("one sale per order").isEqualTo(expectedSold);
        assertThat(report.orders()).allSatisfy(order -> assertThat(order.audit()).as("reserved, pending, sold once")
                .hasSize(3));

        // Late duplicates for orders that are already SOLD change nothing and are acknowledged.
        for (Call call : calls) {
            for (int copy = 0; copy < 3; copy++) {
                ConcurrencySupport.send(sqs, queues.url(), ConcurrencySupport.orderMessage(call.orderId()));
            }
        }
        ConcurrencySupport.awaitEmpty(sqs, queues.url());
        var after = support.reconcile(eventId, calls, true);
        assertThat(after.orders()).allSatisfy(order -> assertThat(order.audit()).hasSize(3));
        assertThat(support.availability(eventId).sold()).isEqualTo(expectedSold);

        // DLQ end state: exactly the poison messages, each once.
        assertThat(ConcurrencySupport.drain(sqs, queues.dlqUrl())).containsExactlyInAnyOrderElementsOf(poison);
        assertThat(SqsTestQueues.total(sqs, queues.url())).isZero();
    }

    // ---------------------------------------------------------------- scenario 7

    @Test
    void restartingConsumerAndSchedulerRepeatedlyWhileMessagesAreInFlight_losesNothingAndSellsOnce() throws Exception {
        String eventId = support.createEvent(300);
        int purchases = 120;
        List<Mono<Call>> requests = new ArrayList<>();
        Semaphore progress = new Semaphore(0);
        for (int i = 0; i < purchases; i++) {
            requests.add(support.purchase(newKey(), eventId, 1).doOnSuccess(call -> progress.release()));
        }
        AtomicBoolean trafficDone = new AtomicBoolean();
        AtomicInteger toggles = new AtomicInteger();
        Thread toggler = new Thread(() -> {
            try {
                // One restart per few answered purchases, and at least 10 restarts whatever the pace of the traffic.
                while (!trafficDone.get() || toggles.get() < 10) {
                    consumer.stop();
                    consumer.start();
                    scheduler.stop();
                    scheduler.start();
                    toggles.incrementAndGet();
                    progress.tryAcquire(5, 200, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "lifecycle-toggler");
        toggler.start();

        List<Call> calls;
        try {
            calls = fireAll(requests);
        } finally {
            trafficDone.set(true);
            toggler.join(ConcurrencySupport.WAIT.toMillis());
        }
        consumer.start();
        scheduler.start();
        assertThat(consumer.isRunning()).isTrue();
        assertThat(scheduler.isRunning()).isTrue();

        assertThat(calls).allSatisfy(call -> assertThat(call.accepted()).isTrue());
        support.awaitProcessed(eventId);
        ConcurrencySupport.awaitEmpty(sqs, queues.url());
        var report = support.reconcile(eventId, calls, true);
        assertThat(toggles.get()).isGreaterThanOrEqualTo(10);
        assertThat(report.countIn(TicketStatus.SOLD)).as("none lost").isEqualTo(purchases);
        assertThat(support.availability(eventId).sold()).as("no double sale").isEqualTo(purchases);
        assertThat(report.orders()).allSatisfy(order -> assertThat(order.audit()).hasSize(3));
        assertThat(Set.copyOf(calls.stream().map(Call::orderId).toList())).hasSize(purchases);
    }
}
