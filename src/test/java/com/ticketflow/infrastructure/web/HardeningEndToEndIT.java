package com.ticketflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderPlacementRepository;
import com.ticketflow.domain.port.OrderQueuePublisher;
import com.ticketflow.infrastructure.config.ExpirationProperties;
import com.ticketflow.infrastructure.config.SqsConsumerProperties;
import com.ticketflow.infrastructure.config.SqsProperties;
import com.ticketflow.infrastructure.messaging.SqsOrderConsumer;
import com.ticketflow.infrastructure.messaging.SqsOrderQueuePublisher;
import com.ticketflow.infrastructure.messaging.SqsTestQueues;
import com.ticketflow.infrastructure.persistence.DynamoDbTables;
import com.ticketflow.infrastructure.scheduler.ReservationExpirationScheduler;
import com.ticketflow.usecase.ProcessOrderUseCase;
import com.ticketflow.usecase.ReleaseExpiredReservationsUseCase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SignalType;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * C12: the application-hardening features over HTTP against the whole application context with REAL
 * DynamoDB Local and LocalStack SQS (limits are generous here; {@link RateLimitEndToEndIT} covers the
 * limiter): security headers on every kind of response, body/key/quantity/path-id limits, republishing of a
 * stranded reservation on replay, a client that disconnects mid-purchase, transient dependency failures
 * answered 503, and restarts of the real consumer and expiration scheduler. The consumer and the scheduler
 * are off in the context (so the queue and the reservations can be inspected); the restart tests build their
 * own against the same real infrastructure. No fixed sleeps. Enabled with INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
class HardeningEndToEndIT {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final String ADMIN_KEY = "e2e-admin-key-" + UUID.randomUUID();
    private static final String TYPE = "urn:ticketflow:problem:";
    private static SqsTestQueues.Queues queues;
    private static SqsAsyncClient sqs;

    @BeforeAll
    static void startInfrastructure() {
        sqs = E2eContainers.start();
        queues = E2eContainers.newQueue("orders-hardening");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        E2eContainers.register(registry, queues.name());
        registry.add("ticketflow.admin.api-key", () -> ADMIN_KEY);
        registry.add("ticketflow.orders.max-quantity", () -> "4");
        E2eContainers.generousRateLimits(registry);
    }

    /** Test doubles around the real adapters: a gate before publishing, DynamoDB throttling, a cancel probe. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Probes {

        @Bean
        @Primary
        GatedPublisher gatedPublisher(SqsAsyncClient client, SqsProperties properties) {
            return new GatedPublisher(SqsOrderQueuePublisher.forQueueName(client, properties.ordersQueueName()));
        }

        @Bean
        static BeanPostProcessor throttlingDynamoDb() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof DynamoDbAsyncClient real)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(DynamoDbAsyncClient.class.getClassLoader(),
                            new Class<?>[] {DynamoDbAsyncClient.class}, (proxy, method, args) -> {
                                if (Throttle.armed && Throttle.OPERATIONS.contains(method.getName())) {
                                    return CompletableFuture.failedFuture(ProvisionedThroughputExceededException
                                            .builder().message("Throughput exceeded: internal-detail-9931").build());
                                }
                                try {
                                    return method.invoke(real, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            });
                }
            };
        }

        @Bean
        CancelProbe cancelProbe() {
            return new CancelProbe();
        }
    }

    /** Makes the real DynamoDB client fail like a throttled table while armed. */
    static final class Throttle {
        static final Set<String> OPERATIONS = Set.of("getItem", "putItem", "updateItem", "query", "transactWriteItems");
        static volatile boolean armed;

        private Throttle() {}
    }

    /** Publishes through the real SQS publisher, but can park the next publish until released (or failed). */
    static final class GatedPublisher implements OrderQueuePublisher {
        private final OrderQueuePublisher delegate;
        private volatile boolean armed;
        private volatile CountDownLatch entered = new CountDownLatch(1);
        private volatile Sinks.Empty<Void> gate = Sinks.empty();
        private volatile RuntimeException failure;

        GatedPublisher(OrderQueuePublisher delegate) {
            this.delegate = delegate;
        }

        void arm(RuntimeException failureAfterRelease) {
            entered = new CountDownLatch(1);
            gate = Sinks.empty();
            failure = failureAfterRelease;
            armed = true;
        }

        boolean awaitEntered() throws InterruptedException {
            return entered.await(WAIT.toSeconds(), TimeUnit.SECONDS);
        }

        void release() {
            gate.tryEmitEmpty();
        }

        @Override
        public Mono<Void> publish(Order order) {
            return Mono.defer(() -> {
                if (!armed) {
                    return delegate.publish(order);
                }
                armed = false; // one-shot
                Sinks.Empty<Void> myGate = gate;
                RuntimeException myFailure = failure;
                entered.countDown();
                return myGate.asMono().then(Mono.defer(() ->
                        myFailure != null ? Mono.<Void>error(myFailure) : delegate.publish(order)));
            });
        }
    }

    /** Records how every {@code POST /orders} exchange ended, so a test can wait for the server to see a cancel. */
    static final class CancelProbe implements WebFilter, Ordered {
        final List<SignalType> endings = new CopyOnWriteArrayList<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            if (HttpMethod.POST.equals(exchange.getRequest().getMethod())
                    && exchange.getRequest().getPath().value().equals("/orders")) {
                return chain.filter(exchange).doFinally(endings::add);
            }
            return chain.filter(exchange);
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 30;
        }
    }

    @Autowired
    private WebTestClient client;

    @Autowired
    private DynamoDbAsyncClient dynamo;

    @Autowired
    private GatedPublisher gated;

    @Autowired
    private CancelProbe cancelProbe;

    @Autowired
    private OrderPlacementRepository placement;

    @Autowired
    private ProcessOrderUseCase processOrder;

    @Autowired
    private ReleaseExpiredReservationsUseCase releaseExpired;

    @LocalServerPort
    private int port;

    @BeforeEach
    void awaitTablesAndResetProbes() {
        Throttle.armed = false;
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(dynamo.listTables().join().tableNames())
                        .contains(DynamoDbTables.EVENTS, DynamoDbTables.INVENTORY, DynamoDbTables.ORDERS));
    }

    // ---------------------------------------------------------------- helpers

    private String createEvent(int capacity) {
        String location = client.post().uri("/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"Concert","startsAt":"%s","venue":"Arena","capacity":%d}"""
                        .formatted(Instant.now().plus(Duration.ofDays(30)), capacity))
                .exchange().expectStatus().isCreated()
                .expectBody().returnResult().getResponseHeaders().getFirst("Location");
        return location.substring("/events/".length());
    }

    private WebTestClient.ResponseSpec purchase(String key, String eventId, int quantity) {
        return client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key)
                .bodyValue("{\"eventId\":\"%s\",\"quantity\":%d}".formatted(eventId, quantity)).exchange();
    }

    private AvailabilityResponse availability(String eventId) {
        return client.get().uri("/events/{id}/availability", eventId).exchange().expectStatus().isOk()
                .expectBody(AvailabilityResponse.class).returnResult().getResponseBody();
    }

    private String orderStatus(String orderId) {
        return client.get().uri("/orders/{id}", orderId).exchange().expectStatus().isOk()
                .expectBody(OrderStatusResponse.class).returnResult().getResponseBody().status();
    }

    private static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    private static void assertSecurityHeaders(HttpHeaders headers) {
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("Content-Security-Policy")).contains("default-src 'none'");
        assertThat(headers.getFirst("X-Correlation-Id")).isNotBlank();
    }

    /** Reads (and deletes) every message currently in the real queue. */
    private static List<Message> receiveAll() {
        List<Message> all = new ArrayList<>();
        while (true) {
            var batch = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queues.url())
                    .messageAttributeNames("All").maxNumberOfMessages(10).waitTimeSeconds(1).visibilityTimeout(60)
                    .build()).join().messages();
            if (batch.isEmpty()) {
                return all;
            }
            batch.forEach(message -> sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(queues.url())
                    .receiptHandle(message.receiptHandle()).build()).join());
            all.addAll(batch);
        }
    }

    private static long messagesFor(List<Message> messages, String orderId) {
        return messages.stream().filter(m -> m.messageAttributes().containsKey("orderId")
                && orderId.equals(m.messageAttributes().get("orderId").stringValue())).count();
    }

    private static String orderIdFor(String key) {
        return OrderId.fromIdempotencyKey(new IdempotencyKey(key)).value();
    }

    // ---------------------------------------------------------------- headers

    @Test
    void securityHeaders_arePresentOnSuccessAndOnEveryKindOfError() {
        String eventId = createEvent(5);
        List<HttpHeaders> all = new ArrayList<>();
        all.add(client.get().uri("/events").exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders());
        all.add(client.get().uri("/events/{id}/availability", eventId).exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders());
        all.add(purchase(newKey(), eventId, 2).expectStatus().isAccepted()
                .returnResult(String.class).getResponseHeaders());
        all.add(client.get().uri("/events/nope").exchange().expectStatus().isNotFound()
                .returnResult(String.class).getResponseHeaders());
        all.add(client.get().uri("/no/such/route").exchange().expectStatus().isNotFound()
                .returnResult(String.class).getResponseHeaders());
        all.add(client.delete().uri("/events").exchange().expectStatus().isEqualTo(405)
                .returnResult(String.class).getResponseHeaders());
        all.add(client.post().uri("/events").contentType(MediaType.TEXT_PLAIN).bodyValue("x").exchange()
                .expectStatus().isEqualTo(415).returnResult(String.class).getResponseHeaders());
        all.add(purchase(newKey(), eventId, 50).expectStatus().isBadRequest()
                .returnResult(String.class).getResponseHeaders());
        all.add(purchase(newKey(), eventId, 4).expectStatus().isEqualTo(409)
                .returnResult(String.class).getResponseHeaders());
        all.add(client.post().uri("/events/{id}/complimentary", eventId).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", newKey()).bodyValue("{\"quantity\":1}").exchange()
                .expectStatus().isUnauthorized().returnResult(String.class).getResponseHeaders());
        all.add(client.get().uri("/actuator/health").exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders());
        all.forEach(HardeningEndToEndIT::assertSecurityHeaders);
    }

    // ---------------------------------------------------------------- input and resource limits

    @Test
    void oversizedBody_is413ProblemJsonOnOrdersAndEvents_withHeadersAndNoInternals() {
        String padded = "{\"eventId\":\"x\",\"quantity\":1,\"pad\":\"" + "a".repeat(100_000) + "\"}";
        for (String path : new String[] {"/orders", "/events"}) {
            var result = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", newKey()).bodyValue(padded).exchange()
                    .expectStatus().isEqualTo(413)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.type").isEqualTo(TYPE + "payload-too-large")
                    .jsonPath("$.correlationId").isNotEmpty()
                    .returnResult();
            assertThat(new String(result.getResponseBodyContent())).doesNotContain("DataBuffer")
                    .doesNotContain("Exception");
            assertSecurityHeaders(result.getResponseHeaders());
        }
    }

    @Test
    void idempotencyKey_shorterThan16Chars_is400WithoutEchoingIt_and16CharsIsAccepted() {
        String eventId = createEvent(10);

        for (String shortKey : new String[] {"1", "short-key", "123456789012345"}) {
            var body = purchase(shortKey, eventId, 1).expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.type").isEqualTo(TYPE + "invalid-idempotency-key")
                    .returnResult().getResponseBodyContent();
            assertThat(new String(body)).contains("at least 16 characters").doesNotContain("\"" + shortKey + "\"");
        }
        assertThat(availability(eventId).reserved()).isZero();

        purchase("1234567890123456", eventId, 1).expectStatus().isAccepted();
        assertThat(availability(eventId).reserved()).isEqualTo(1);
    }

    @Test
    void maxQuantityPerOrder_isConfigurable_aboveItIs400AndReservesNothing() {
        String eventId = createEvent(20); // the context sets ticketflow.orders.max-quantity=4

        purchase(newKey(), eventId, 5).expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo(TYPE + "validation-error")
                .jsonPath("$.violations[0].field").isEqualTo("quantity")
                .jsonPath("$.violations[0].message").isEqualTo("must be less than or equal to 4");
        assertThat(availability(eventId).reserved()).isZero();

        purchase(newKey(), eventId, 4).expectStatus().isAccepted();
        assertThat(availability(eventId).reserved()).isEqualTo(4);
    }

    @Test
    void invalidPathIds_areGeneric404sThatNeverEchoTheInput() {
        List<String> hostile = List.of("../../etc/passwd", "<script>alert(1)</script>", "a b", "café", "x".repeat(65));
        String eventId = createEvent(5);
        for (String id : hostile) {
            List<WebTestClient.ResponseSpec> responses = List.of(
                    client.get().uri("/events/{id}", id).exchange(),
                    client.get().uri("/events/{id}/availability", id).exchange(),
                    client.get().uri("/events/{id}/availability/stream", id).accept(MediaType.TEXT_EVENT_STREAM)
                            .exchange(),
                    client.get().uri("/orders/{id}", id).exchange(),
                    client.post().uri("/events/{id}/complimentary", id).contentType(MediaType.APPLICATION_JSON)
                            .header("X-Admin-Key", ADMIN_KEY).header("Idempotency-Key", newKey())
                            .bodyValue("{\"quantity\":1}").exchange());
            for (var response : responses) {
                var result = response.expectStatus().isNotFound()
                        .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                        .expectBody()
                        .jsonPath("$.type").isEqualTo(TYPE + "not-found")
                        .jsonPath("$.detail").isEqualTo("The requested resource was not found")
                        .returnResult();
                String body = new String(result.getResponseBodyContent());
                assertThat(body).doesNotContain("passwd").doesNotContain("script").doesNotContain("etc")
                        .doesNotContain("\u00e9").doesNotContain("xxxx");
            }
        }
        // A well-formed id of an unknown event is still the documented event-not-found.
        client.get().uri("/events/{id}", "no-such-event").exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "event-not-found");
        assertThat(availability(eventId).capacity()).isEqualTo(5);
    }

    // ---------------------------------------------------------------- retry safety

    @Test
    void replay_ofAReservationWhoseMessageWasNeverPublished_republishesItToTheRealQueue() {
        String eventId = createEvent(10);
        String key = newKey();
        // The state a crashed or disconnected first attempt leaves behind: reserved, order saved, no message.
        Order stranded = new Order(OrderId.fromIdempotencyKey(new IdempotencyKey(key)), new EventId(eventId),
                new Quantity(2), TicketStatus.RESERVED, new IdempotencyKey(key),
                Instant.now().plus(Duration.ofMinutes(10)), Instant.now());
        placement.placeReservation(stranded, "test-crashed-attempt").block(WAIT);
        receiveAll(); // start from an empty queue
        String orderId = stranded.id().value();

        purchase(key, eventId, 2).expectStatus().isAccepted()
                .expectBody().jsonPath("$.orderId").isEqualTo(orderId).jsonPath("$.status").isEqualTo("RESERVED");

        List<Message> received = new ArrayList<>();
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            received.addAll(receiveAll());
            assertThat(messagesFor(received, orderId)).isEqualTo(1);
        });
        // Still the same single reservation: nothing was reserved twice or released.
        assertThat(availability(eventId)).isEqualTo(new AvailabilityResponse(8, 2, 0, 0, 0, 10));
        assertThat(orderStatus(orderId)).isEqualTo("RESERVED");
    }

    @Test
    void replay_ofAnOrderAlreadyBeyondReserved_doesNotRepublish() {
        String eventId = createEvent(10);
        String key = newKey();
        purchase(key, eventId, 1).expectStatus().isAccepted();
        receiveAll(); // consume the original message
        // Move the order past RESERVED the way the consumer would.
        processOrder.execute(OrderId.fromIdempotencyKey(new IdempotencyKey(key))).block(WAIT);
        assertThat(orderStatus(orderIdFor(key))).isEqualTo("SOLD");

        purchase(key, eventId, 1).expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("SOLD");

        assertThat(messagesFor(receiveAll(), orderIdFor(key))).isZero();
    }

    @Test
    void clientDisconnectsMidPurchase_theMessageIsStillPublishedExactlyOnceWithTheCorrelationId() throws Exception {
        String eventId = createEvent(10);
        String key = newKey();
        String orderId = orderIdFor(key);
        String correlationId = "cancel-" + UUID.randomUUID();
        receiveAll();
        cancelProbe.endings.clear();
        gated.arm(null);

        Disposable request = rawClient().post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).header("X-Correlation-Id", correlationId)
                .bodyValue("{\"eventId\":\"%s\",\"quantity\":3}".formatted(eventId))
                .retrieve().toBodilessEntity().subscribe(ok -> { }, error -> { });
        assertThat(gated.awaitEntered()).as("the purchase reached the publisher").isTrue();
        // The reservation is committed, the message is not published yet.
        assertThat(availability(eventId)).isEqualTo(new AvailabilityResponse(7, 3, 0, 0, 0, 10));

        request.dispose(); // the client disconnects
        await().atMost(WAIT).untilAsserted(() -> assertThat(cancelProbe.endings).contains(SignalType.CANCEL));
        gated.release();

        List<Message> received = new ArrayList<>();
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            received.addAll(receiveAll());
            assertThat(messagesFor(received, orderId)).isEqualTo(1);
        });
        received.addAll(receiveAll());
        assertThat(messagesFor(received, orderId)).as("published exactly once").isEqualTo(1);
        assertThat(received.stream().filter(m -> m.messageAttributes().containsKey("orderId")
                && orderId.equals(m.messageAttributes().get("orderId").stringValue()))
                .findFirst().orElseThrow().messageAttributes().get("correlationId").stringValue())
                .isEqualTo(correlationId);
        assertThat(orderStatus(orderId)).isEqualTo("RESERVED");
        assertThat(availability(eventId)).isEqualTo(new AvailabilityResponse(7, 3, 0, 0, 0, 10));
        // The client's retry gets the very same order.
        purchase(key, eventId, 3).expectStatus().isAccepted().expectBody().jsonPath("$.orderId").isEqualTo(orderId);
    }

    @Test
    void clientDisconnectsMidPurchaseAndPublishFails_theReservationIsCompensatedNothingStaysReserved()
            throws Exception {
        String eventId = createEvent(10);
        String key = newKey();
        String orderId = orderIdFor(key);
        receiveAll();
        cancelProbe.endings.clear();
        gated.arm(new IllegalStateException("queue down"));

        Disposable request = rawClient().post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .bodyValue("{\"eventId\":\"%s\",\"quantity\":3}".formatted(eventId))
                .retrieve().toBodilessEntity().subscribe(ok -> { }, error -> { });
        assertThat(gated.awaitEntered()).isTrue();
        request.dispose();
        await().atMost(WAIT).untilAsserted(() -> assertThat(cancelProbe.endings).contains(SignalType.CANCEL));
        gated.release();

        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(availability(eventId)).isEqualTo(new AvailabilityResponse(10, 0, 0, 0, 0, 10)));
        assertThat(orderStatus(orderId)).isEqualTo("AVAILABLE");
        assertThat(messagesFor(receiveAll(), orderId)).isZero();
    }

    private WebClient rawClient() {
        return WebClient.create("http://localhost:" + port);
    }

    // ---------------------------------------------------------------- transient failures

    @Test
    void dependencyThrottling_isAnswered503WithRetryAfterAndNoInternals_andBusinessConflictsAreNot() {
        String eventId = createEvent(2);
        String orderId = purchase(newKey(), eventId, 1).expectStatus().isAccepted()
                .expectBody(PurchaseAcceptedResponse.class).returnResult().getResponseBody().orderId();
        AvailabilityResponse before = availability(eventId);

        Throttle.armed = true;
        try {
            List<WebTestClient.ResponseSpec> throttled = List.of(
                    client.get().uri("/orders/{id}", orderId).exchange(),
                    client.get().uri("/events/{id}", eventId).exchange(),
                    client.get().uri("/events/{id}/availability", eventId).exchange(),
                    purchase(newKey(), eventId, 1));
            for (var response : throttled) {
                var result = response.expectStatus().isEqualTo(503)
                        .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "5")
                        .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                        .expectBody()
                        .jsonPath("$.type").isEqualTo(TYPE + "service-unavailable")
                        .jsonPath("$.correlationId").isNotEmpty()
                        .returnResult();
                assertThat(new String(result.getResponseBodyContent())).doesNotContain("internal-detail-9931")
                        .doesNotContain("Throughput").doesNotContain("Exception");
                assertSecurityHeaders(result.getResponseHeaders());
            }
        } finally {
            Throttle.armed = false;
        }

        // Recovered, and the throttled purchase left nothing behind.
        assertThat(orderStatus(orderId)).isEqualTo("RESERVED");
        assertThat(availability(eventId)).isEqualTo(before);
        // A business conflict is a 409, never a 503.
        purchase(newKey(), eventId, 3).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "insufficient-inventory");
    }

    // ---------------------------------------------------------------- lifecycle restarts

    @Test
    void sqsConsumer_restartedRepeatedlyWhileDraining_sellsEveryOrderExactlyOnce() {
        String eventId = createEvent(20);
        List<String> orderIds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            orderIds.add(purchase(newKey(), eventId, 1).expectStatus().isAccepted()
                    .expectBody(PurchaseAcceptedResponse.class).returnResult().getResponseBody().orderId());
        }
        var consumer = new SqsOrderConsumer(sqs, Mono.just(queues.url()), processOrder,
                new SqsConsumerProperties(true, 10, Duration.ofSeconds(1), Duration.ofSeconds(30), 4,
                        Duration.ofSeconds(10), Duration.ofMillis(100), Duration.ofSeconds(1)));
        try {
            consumer.start();
            for (int i = 0; i < 3; i++) {
                consumer.stop();
                consumer.start();
            }
            assertThat(consumer.isRunning()).isTrue();
            await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    assertThat(orderIds).allSatisfy(id -> assertThat(orderStatus(id)).isEqualTo("SOLD")));
        } finally {
            consumer.stop();
            await().atMost(WAIT).until(() -> !consumer.isRunning());
        }
        AvailabilityResponse state = availability(eventId);
        assertThat(state).isEqualTo(new AvailabilityResponse(14, 0, 0, 6, 0, 20));
    }

    @Test
    void expirationScheduler_restartedRepeatedly_stillReleasesTheExpiredReservation() {
        String eventId = createEvent(10);
        IdempotencyKey key = new IdempotencyKey(newKey());
        Order expired = new Order(OrderId.fromIdempotencyKey(key), new EventId(eventId), new Quantity(3),
                TicketStatus.RESERVED, key, Instant.now().minusSeconds(60), Instant.now().minusSeconds(120));
        placement.placeReservation(expired, "test-expired").block(WAIT); // no message: only the sweep can free it
        assertThat(availability(eventId)).isEqualTo(new AvailabilityResponse(7, 3, 0, 0, 0, 10));

        var scheduler = new ReservationExpirationScheduler(releaseExpired,
                new ExpirationProperties(true, Duration.ofSeconds(1), Duration.ZERO, 4, 500, Duration.ofSeconds(10)));
        try {
            scheduler.start();
            scheduler.stop();
            scheduler.start();
            scheduler.stop();
            scheduler.start();
            assertThat(scheduler.isRunning()).isTrue();
            await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    assertThat(availability(eventId)).isEqualTo(new AvailabilityResponse(10, 0, 0, 0, 0, 10)));
            assertThat(orderStatus(expired.id().value())).isEqualTo("AVAILABLE");
        } finally {
            scheduler.stop();
            await().atMost(WAIT).until(() -> !scheduler.isRunning());
        }
    }
}
