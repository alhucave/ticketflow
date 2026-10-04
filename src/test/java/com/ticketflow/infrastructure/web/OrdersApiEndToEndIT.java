package com.ticketflow.infrastructure.web;

import com.ticketflow.testsupport.TestTimeouts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketflow.infrastructure.messaging.SqsTestQueues;
import com.ticketflow.infrastructure.persistence.DynamoDbTables;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.Disposable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * C12: the whole application context (controllers, use cases, DynamoDB and SQS adapters, SQS consumer)
 * against a REAL DynamoDB Local and a REAL LocalStack SQS whose queue has a redrive policy to a DLQ,
 * exercised over HTTP: asynchronous purchase, status polling, availability, idempotent replay,
 * validation errors, SSE and a concurrent burst that must never oversell. No fixed sleeps: every
 * asynchronous outcome is awaited. Enabled with INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class OrdersApiEndToEndIT {

    private static final Duration WAIT = TestTimeouts.WAIT;

    private static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000)
            .withStartupTimeout(TestTimeouts.CONTAINER_STARTUP);
    private static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.14.0")).withServices("sqs")
            .withStartupTimeout(TestTimeouts.CONTAINER_STARTUP);
    private static SqsTestQueues.Queues queues;

    @BeforeAll
    static void startInfrastructure() {
        // Started eagerly (not via @Container) because @DynamicPropertySource needs the mapped ports
        // and the queue must exist before the consumer starts.
        DYNAMO.start();
        LOCALSTACK.start();
        try (SqsAsyncClient sqs = SqsAsyncClient.builder().endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            queues = SqsTestQueues.create(sqs, "orders-" + UUID.randomUUID());
        }
    }

    @AfterAll
    static void stopInfrastructure() {
        LOCALSTACK.stop();
        DYNAMO.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ticketflow.dynamodb.endpoint",
                () -> "http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000));
        registry.add("ticketflow.dynamodb.access-key-id", () -> "test");
        registry.add("ticketflow.dynamodb.secret-access-key", () -> "test");
        registry.add("ticketflow.dynamodb.provisioning-enabled", () -> "true");
        registry.add("ticketflow.sqs.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("ticketflow.sqs.access-key-id", () -> "test");
        registry.add("ticketflow.sqs.secret-access-key", () -> "test");
        registry.add("ticketflow.sqs.orders-queue-name", () -> queues.name());
        registry.add("ticketflow.sqs.consumer.enabled", () -> "true");
        registry.add("ticketflow.sqs.consumer.wait-time", () -> "1s");
        registry.add("ticketflow.expiration.enabled", () -> "false");
        E2eContainers.generousRateLimits(registry);
        registry.add("ticketflow.availability.poll-interval", () -> "200ms");
    }

    @Autowired
    private WebTestClient client;

    @Autowired
    private DynamoDbAsyncClient dynamo;

    @BeforeEach
    void awaitTableProvisioning() {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(dynamo.listTables().join().tableNames())
                        .contains(DynamoDbTables.EVENTS, DynamoDbTables.INVENTORY, DynamoDbTables.ORDERS));
    }

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
        var spec = client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            spec.header("Idempotency-Key", key);
        }
        return spec.bodyValue("{\"eventId\":\"%s\",\"quantity\":%d}".formatted(eventId, quantity)).exchange();
    }

    private String status(String orderId) {
        return client.get().uri("/orders/{id}", orderId).exchange().expectStatus().isOk()
                .expectBody(OrderStatusResponse.class).returnResult().getResponseBody().status();
    }

    private void awaitSold(String orderId) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("SOLD"));
    }

    private AvailabilityResponse availability(String eventId) {
        return client.get().uri("/events/{id}/availability", eventId).exchange().expectStatus().isOk()
                .expectBody(AvailabilityResponse.class).returnResult().getResponseBody();
    }

    private static void assertInvariant(AvailabilityResponse a) {
        assertThat(a.available() + a.reserved() + a.pendingConfirmation() + a.sold() + a.complimentary())
                .isEqualTo(a.capacity());
    }

    private static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    @Test
    void purchase_endToEnd_acceptedThenSoldWithIdempotentReplayAndErrors() {
        String eventId = createEvent(10);
        String key = newKey();

        var accepted = purchase(key, eventId, 3).expectStatus().isAccepted()
                .expectBody(PurchaseAcceptedResponse.class).returnResult();
        String orderId = accepted.getResponseBody().orderId();
        assertThat(orderId).isNotBlank();
        assertThat(accepted.getResponseHeaders().getFirst("Location")).isEqualTo("/orders/" + orderId);

        awaitSold(orderId);
        client.get().uri("/orders/{id}", orderId).exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.eventId").isEqualTo(eventId)
                .jsonPath("$.quantity").isEqualTo(3)
                .jsonPath("$.status").isEqualTo("SOLD")
                .jsonPath("$.reservationExpiresAt").doesNotExist();
        AvailabilityResponse afterSale = availability(eventId);
        assertThat(afterSale).isEqualTo(new AvailabilityResponse(7, 0, 0, 3, 0, 10));

        // Replay: same key and payload returns the same order and sells nothing more.
        purchase(key, eventId, 3).expectStatus().isAccepted()
                .expectBody().jsonPath("$.orderId").isEqualTo(orderId);
        assertThat(availability(eventId).sold()).isEqualTo(3);

        // Same key, different payload.
        purchase(key, eventId, 4).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.type").isEqualTo("urn:ticketflow:problem:idempotency-key-reused");

        // More than what remains (7 left).
        purchase(newKey(), eventId, 8).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.type").isEqualTo("urn:ticketflow:problem:insufficient-inventory");

        // Header validation.
        purchase(null, eventId, 1).expectStatus().isBadRequest();
        purchase("", eventId, 1).expectStatus().isBadRequest();
        purchase("bad key!", eventId, 1).expectStatus().isBadRequest();
        purchase("k".repeat(129), eventId, 1).expectStatus().isBadRequest();
        // Body validation.
        purchase(newKey(), eventId, 11).expectStatus().isBadRequest();
        purchase(newKey(), eventId, 0).expectStatus().isBadRequest();

        // Nothing leaked from the rejected requests.
        AvailabilityResponse finalState = availability(eventId);
        assertThat(finalState).isEqualTo(new AvailabilityResponse(7, 0, 0, 3, 0, 10));

        client.get().uri("/orders/{id}", UUID.randomUUID().toString()).exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.type").isEqualTo("urn:ticketflow:problem:order-not-found");
        client.get().uri("/events/nope/availability").exchange().expectStatus().isNotFound();
    }

    @Test
    void availabilityStream_purchaseHappens_emitsCurrentValueThenChange() {
        String eventId = createEvent(5);
        List<AvailabilityResponse> received = new CopyOnWriteArrayList<>();

        Disposable subscription = client.get().uri("/events/{id}/availability/stream", eventId)
                .accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(AvailabilityResponse.class).getResponseBody()
                .subscribe(received::add);
        try {
            await().atMost(WAIT).untilAsserted(() ->
                    assertThat(received).first().isEqualTo(new AvailabilityResponse(5, 0, 0, 0, 0, 5)));

            String orderId = purchase(newKey(), eventId, 2).expectStatus().isAccepted()
                    .expectBody(PurchaseAcceptedResponse.class).returnResult().getResponseBody().orderId();
            awaitSold(orderId);

            await().atMost(WAIT).untilAsserted(() ->
                    assertThat(received).last().isEqualTo(new AvailabilityResponse(3, 0, 0, 2, 0, 5)));
            // Reserved tickets are counted too, whichever intermediate states the stream observed.
            received.forEach(OrdersApiEndToEndIT::assertInvariant);
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void availabilityStream_unknownEvent_returns404JsonProblemBeforeStreaming() {
        client.get().uri("/events/nope/availability/stream").accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo("urn:ticketflow:problem:event-not-found");
    }

    @Test
    void purchase_parallelBurstOverCapacity_acceptsExactlyCapacityAndNeverOversells() throws Exception {
        int capacity = 15;
        int requests = 40;
        String eventId = createEvent(capacity);

        List<Future<PurchaseOutcome>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            for (int i = 0; i < requests; i++) {
                String key = newKey();
                futures.add(pool.submit(() -> {
                    var result = purchase(key, eventId, 1).returnResult(PurchaseAcceptedResponse.class);
                    var body = result.getResponseBody().blockFirst(WAIT);
                    return new PurchaseOutcome(result.getStatus().value(), body == null ? null : body.orderId());
                }));
            }
        }
        List<String> acceptedOrders = new ArrayList<>();
        int rejected = 0;
        for (Future<PurchaseOutcome> future : futures) {
            PurchaseOutcome outcome = future.get();
            if (outcome.status() == 202) {
                acceptedOrders.add(outcome.orderId());
            } else {
                assertThat(outcome.status()).isEqualTo(409);
                rejected++;
            }
        }
        assertThat(acceptedOrders).hasSize(capacity).doesNotHaveDuplicates();
        assertThat(rejected).isEqualTo(requests - capacity);

        acceptedOrders.forEach(this::awaitSold);

        AvailabilityResponse finalState = availability(eventId);
        assertThat(finalState).isEqualTo(new AvailabilityResponse(0, 0, 0, capacity, 0, capacity));
        assertInvariant(finalState);
    }

    private record PurchaseOutcome(int status, String orderId) {}
}
