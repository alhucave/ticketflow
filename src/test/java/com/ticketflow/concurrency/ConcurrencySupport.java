package com.ticketflow.concurrency;

import com.ticketflow.testsupport.TestTimeouts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.infrastructure.messaging.SqsTestQueues;
import com.ticketflow.infrastructure.persistence.DynamoDbTables;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reusable support of the end-to-end concurrency suite: a real-HTTP client (many requests in flight at once, one
 * connection each), event creation, availability sampling, queue helpers and the {@link Reconciliation} check. It
 * only talks to the application over HTTP and to the real DynamoDB Local / LocalStack SQS the application uses.
 */
public final class ConcurrencySupport {

    /** Generous bound for every asynchronous outcome; Awaitility returns as soon as the condition holds. */
    public static final Duration WAIT = TestTimeouts.WAIT;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROBLEM_PREFIX = "urn:ticketflow:problem:";

    /** One HTTP answer. */
    public record Response(int status, String body) {

        public String problem() {
            String type = field("type");
            return type == null ? null : type.substring(PROBLEM_PREFIX.length());
        }

        public String orderId() {
            return field("orderId");
        }

        public Instant reservationExpiresAt() {
            String value = field("reservationExpiresAt");
            return value == null ? null : Instant.parse(value);
        }

        String field(String name) {
            try {
                JsonNode node = JSON.readTree(body).get(name);
                return node == null || node.isNull() ? null : node.asString();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** A request this suite sent (the key identifies the order) and what came back. */
    public record Call(String key, int quantity, Response response) {

        public boolean accepted() {
            return response.status() == 202 || response.status() == 201;
        }

        public String orderId() {
            return OrderId.fromIdempotencyKey(new IdempotencyKey(key)).value();
        }
    }

    /** Availability as served by {@code GET /events/{id}/availability}. */
    public record Availability(int available, int reserved, int pendingConfirmation, int sold, int complimentary,
                               int capacity) {

        /** True when the counters add up to the capacity and none is negative. */
        public boolean consistent() {
            return available >= 0 && reserved >= 0 && pendingConfirmation >= 0 && sold >= 0 && complimentary >= 0
                    && available + reserved + pendingConfirmation + sold + complimentary == capacity;
        }

        public int held() {
            return reserved + pendingConfirmation + sold + complimentary;
        }
    }

    private final WebClient web;
    private final DynamoDbAsyncClient dynamo;
    private final String adminKey;
    private final Reconciliation reconciliation;

    public ConcurrencySupport(int port, DynamoDbAsyncClient dynamo, String adminKey) {
        // One generous pool so hundreds of requests are really in flight together.
        ConnectionProvider pool = ConnectionProvider.builder("concurrency-suite").maxConnections(1000)
                .pendingAcquireMaxCount(5000).pendingAcquireTimeout(TestTimeouts.RESPONSE).build();
        this.web = WebClient.builder().baseUrl("http://localhost:" + port)
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                        HttpClient.create(pool))).build();
        this.dynamo = dynamo;
        this.adminKey = adminKey;
        this.reconciliation = new Reconciliation(dynamo);
    }

    public static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    public static String orderIdFor(String key) {
        return OrderId.fromIdempotencyKey(new IdempotencyKey(key)).value();
    }

    // ------------------------------------------------------------------------------------------- http

    private static Mono<Response> exchange(WebClient.RequestHeadersSpec<?> spec) {
        return spec.exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                .map(body -> new Response(response.statusCode().value(), body)));
    }

    public String createEvent(int capacity) {
        Response response = exchange(web.post().uri("/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"Concert","startsAt":"%s","venue":"Arena","capacity":%d}"""
                        .formatted(Instant.now().plus(Duration.ofDays(30)), capacity))).block(WAIT);
        assertThat(response.status()).as("create event: %s", response.body()).isEqualTo(201);
        return response.field("id");
    }

    public Mono<Response> purchaseResponse(String key, String eventId, int quantity) {
        return exchange(web.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .bodyValue("{\"eventId\":\"%s\",\"quantity\":%d}".formatted(eventId, quantity)));
    }

    public Mono<Call> purchase(String key, String eventId, int quantity) {
        return purchaseResponse(key, eventId, quantity).map(response -> new Call(key, quantity, response));
    }

    public Mono<Call> complimentary(String key, String eventId, int quantity) {
        return exchange(web.post().uri("/events/{id}/complimentary", eventId).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).header("X-Admin-Key", adminKey)
                .bodyValue("{\"quantity\":%d,\"reason\":\"concurrency suite\"}".formatted(quantity)))
                .map(response -> new Call(key, quantity, response));
    }

    public Response get(String path, Object... uriVariables) {
        return exchange(web.get().uri(path, uriVariables)).block(WAIT);
    }

    public Availability availability(String eventId) {
        Response response = get("/events/{id}/availability", eventId);
        assertThat(response.status()).as("availability: %s", response.body()).isEqualTo(200);
        try {
            JsonNode n = JSON.readTree(response.body());
            return new Availability(n.get("available").asInt(), n.get("reserved").asInt(),
                    n.get("pendingConfirmation").asInt(), n.get("sold").asInt(), n.get("complimentary").asInt(),
                    n.get("capacity").asInt());
        } catch (RuntimeException e) {
            throw new AssertionError("unreadable availability: " + response.body(), e);
        }
    }

    public String orderStatus(String orderId) {
        Response response = get("/orders/{id}", orderId);
        assertThat(response.status()).as("order status: %s", response.body()).isEqualTo(200);
        return response.field("status");
    }

    /** Fires all requests at once (every subscription is concurrent) and returns every answer. */
    public static <T> List<T> fireAll(List<Mono<T>> requests) {
        return Flux.fromIterable(requests).flatMap(request -> request, Math.max(1, requests.size()))
                .collectList().block(WAIT);
    }

    // ------------------------------------------------------------------------------------------- waiting

    /** Waits until the availability of the event satisfies the condition (polling the real endpoint). */
    public void awaitAvailability(String eventId, String description, Predicate<Availability> condition) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(250)).alias(description)
                .untilAsserted(() -> assertThat(condition.test(availability(eventId)))
                        .as("%s; availability=%s", description, availability(eventId)).isTrue());
    }

    /** With the consumer running: nothing of the event is held in RESERVED / PENDING_CONFIRMATION any more. */
    public void awaitProcessed(String eventId) {
        awaitAvailability(eventId, "no reserved/pending left", a -> a.reserved() == 0 && a.pendingConfirmation() == 0);
    }

    public void awaitStatuses(List<String> orderIds, String status) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                assertThat(orderIds).allSatisfy(id -> assertThat(orderStatus(id)).isEqualTo(status)));
    }

    public Reconciliation.Report reconcile(String eventId) {
        return reconciliation.check(eventId);
    }

    public Reconciliation.Report reconcile(String eventId, List<Call> calls, boolean exactly) {
        return reconciliation.check(eventId, calls.stream().filter(Call::accepted).map(Call::orderId)
                .collect(java.util.stream.Collectors.toSet()), exactly);
    }

    public DynamoDbAsyncClient dynamo() {
        return dynamo;
    }

    public static void awaitTables(DynamoDbAsyncClient dynamo) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(dynamo.listTables().join().tableNames())
                        .contains(DynamoDbTables.EVENTS, DynamoDbTables.INVENTORY, DynamoDbTables.ORDERS,
                                DynamoDbTables.ORDER_AUDIT));
    }

    // ------------------------------------------------------------------------------------------- sampling

    /**
     * Polls {@code GET /events/{id}/availability} continuously (from its own thread) while load runs, and records
     * every sample that breaks the invariant (counters adding up to the capacity, none negative, held tickets never
     * above the capacity). Close it before asserting.
     */
    public final class Sampler implements AutoCloseable {

        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicInteger samples = new AtomicInteger();
        private final List<String> violations = new CopyOnWriteArrayList<>();
        private final List<String> errors = new CopyOnWriteArrayList<>();
        private final Thread thread;

        private Sampler(String eventId) {
            thread = new Thread(() -> {
                while (running.get()) {
                    try {
                        Availability a = availability(eventId);
                        samples.incrementAndGet();
                        if (!a.consistent() || a.held() > a.capacity()) {
                            violations.add(a.toString());
                        }
                    } catch (RuntimeException | AssertionError e) {
                        errors.add(String.valueOf(e));
                    }
                }
            }, "availability-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        public int samples() {
            return samples.get();
        }

        public List<String> violations() {
            return List.copyOf(violations);
        }

        public List<String> errors() {
            return List.copyOf(errors);
        }

        @Override
        public void close() {
            running.set(false);
            try {
                thread.join(WAIT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public Sampler sample(String eventId) {
        return new Sampler(eventId);
    }

    // ------------------------------------------------------------------------------------------- queues

    /** Sends a raw body to a queue, exactly as a (possibly duplicated or hostile) producer would. */
    public static void send(SqsAsyncClient sqs, String queueUrl, String body) {
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(body).build()).join();
    }

    public static String orderMessage(String orderId) {
        return "{\"version\":1,\"orderId\":\"" + orderId + "\"}";
    }

    /** Reads (and deletes) every message currently in the queue; returns the bodies. */
    public static List<String> drain(SqsAsyncClient sqs, String queueUrl) {
        List<String> bodies = new ArrayList<>();
        while (true) {
            var batch = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl)
                    .maxNumberOfMessages(10).waitTimeSeconds(1).visibilityTimeout(30).build()).join().messages();
            if (batch.isEmpty()) {
                return bodies;
            }
            batch.forEach(m -> {
                bodies.add(m.body());
                sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl)
                        .receiptHandle(m.receiptHandle()).build()).join();
            });
        }
    }

    /** Waits until the queue holds no message at all (visible, in flight or delayed). */
    public static void awaitEmpty(SqsAsyncClient sqs, String queueUrl) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(SqsTestQueues.total(sqs, queueUrl)).as("messages left in %s", queueUrl).isZero());
    }
}
