package com.ticketflow.infrastructure.web;

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
 * C12: complimentary issuance over HTTP against the whole application context with REAL DynamoDB Local
 * and LocalStack SQS (the purchase consumer is running, so complimentary orders prove they are never
 * processed): admin key enforcement, issuance, limits, idempotency, GET /orders, availability and
 * concurrency with purchases. Enabled with INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
class ComplimentaryApiEndToEndIT {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final String ADMIN_KEY = "e2e-admin-key-" + UUID.randomUUID();

    private static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000);
    private static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.14.0")).withServices("sqs");
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
        registry.add("ticketflow.admin.api-key", () -> ADMIN_KEY);
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

    private WebTestClient.ResponseSpec complimentary(
            String adminKey, String idempotencyKey, String eventId, String body) {
        var spec = client.post().uri("/events/{id}/complimentary", eventId).contentType(MediaType.APPLICATION_JSON);
        if (adminKey != null) {
            spec.header("X-Admin-Key", adminKey);
        }
        if (idempotencyKey != null) {
            spec.header("Idempotency-Key", idempotencyKey);
        }
        return spec.bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec complimentary(String eventId, int quantity, String key) {
        return complimentary(ADMIN_KEY, key, eventId, "{\"quantity\":%d,\"reason\":\"guest\"}".formatted(quantity));
    }

    private WebTestClient.ResponseSpec purchase(String key, String eventId, int quantity) {
        return client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key)
                .bodyValue("{\"eventId\":\"%s\",\"quantity\":%d}".formatted(eventId, quantity)).exchange();
    }

    private AvailabilityResponse availability(String eventId) {
        return client.get().uri("/events/{id}/availability", eventId).exchange().expectStatus().isOk()
                .expectBody(AvailabilityResponse.class).returnResult().getResponseBody();
    }

    private static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    @Test
    void complimentary_endToEnd_movesTicketsAndShowsThemWithoutCountingSales() {
        String eventId = createEvent(10);
        String key = newKey();

        var created = complimentary(eventId, 4, key).expectStatus().isCreated()
                .expectBody(ComplimentaryResponse.class).returnResult();
        String orderId = created.getResponseBody().orderId();
        assertThat(created.getResponseHeaders().getFirst("Location")).isEqualTo("/orders/" + orderId);
        assertThat(created.getResponseBody())
                .isEqualTo(new ComplimentaryResponse(orderId, eventId, 4, "COMPLIMENTARY"));

        assertThat(availability(eventId)).isEqualTo(new AvailabilityResponse(6, 0, 0, 0, 4, 10));
        client.get().uri("/events/{id}", eventId).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.inventory.complimentary").isEqualTo(4)
                .jsonPath("$.inventory.sold").isEqualTo(0).jsonPath("$.inventory.available").isEqualTo(6);
        client.get().uri("/orders/{id}", orderId).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("COMPLIMENTARY")
                .jsonPath("$.quantity").isEqualTo(4).jsonPath("$.eventId").isEqualTo(eventId)
                .jsonPath("$.reservationExpiresAt").doesNotExist();

        // Audit entry (actor + reason + AVAILABLE -> COMPLIMENTARY) directly in the real audit table.
        var audit = dynamo.query(q -> q.tableName(DynamoDbTables.ORDER_AUDIT)
                .keyConditionExpression("orderId = :id")
                .expressionAttributeValues(java.util.Map.of(":id",
                        software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s(orderId).build())))
                .join().items();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("from").s()).isEqualTo("AVAILABLE");
        assertThat(audit.get(0).get("to").s()).isEqualTo("COMPLIMENTARY");
        assertThat(audit.get(0).get("actor").s()).isEqualTo("complimentary-issuance");
        assertThat(audit.get(0).get("reason").s()).isEqualTo("guest");

        // Replay: same key and payload -> same body, nothing issued twice.
        complimentary(eventId, 4, key).expectStatus().isCreated()
                .expectBody().jsonPath("$.orderId").isEqualTo(orderId).jsonPath("$.status").isEqualTo("COMPLIMENTARY");
        assertThat(availability(eventId)).isEqualTo(new AvailabilityResponse(6, 0, 0, 0, 4, 10));
        // Same key, different payload.
        complimentary(eventId, 5, key).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.type").isEqualTo("urn:ticketflow:problem:idempotency-key-reused");
    }

    @Test
    void complimentary_cannotExceedAvailable_capacityTenBuyFourCompSixOkOneMoreConflicts() {
        String eventId = createEvent(10);
        String orderId = purchase(newKey(), eventId, 4).expectStatus().isAccepted()
                .expectBody(PurchaseAcceptedResponse.class).returnResult().getResponseBody().orderId();
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                client.get().uri("/orders/{id}", orderId).exchange().expectBody().jsonPath("$.status").isEqualTo("SOLD"));

        complimentary(eventId, 6, newKey()).expectStatus().isCreated();
        complimentary(eventId, 1, newKey()).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.type").isEqualTo("urn:ticketflow:problem:insufficient-inventory");

        assertThat(availability(eventId)).isEqualTo(new AvailabilityResponse(0, 0, 0, 4, 6, 10));
    }

    @Test
    void complimentary_adminKeyEnforcedEndToEnd() {
        String eventId = createEvent(5);
        String body = "{\"quantity\":1}";

        complimentary(null, newKey(), eventId, body).expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo("urn:ticketflow:problem:admin-unauthorized");
        complimentary("wrong-key", newKey(), eventId, body).expectStatus().isUnauthorized();
        complimentary("", newKey(), eventId, body).expectStatus().isUnauthorized();
        // Fails before the event lookup: no leak about unknown events either.
        complimentary("wrong-key", newKey(), "does-not-exist", body).expectStatus().isUnauthorized();

        assertThat(availability(eventId).complimentary()).isZero();
        complimentary(ADMIN_KEY, newKey(), eventId, body).expectStatus().isCreated();
        assertThat(availability(eventId).complimentary()).isEqualTo(1);
    }

    @Test
    void complimentary_errors_unknownEventAndValidation() {
        String eventId = createEvent(5);

        complimentary(ADMIN_KEY, newKey(), "does-not-exist", "{\"quantity\":1}").expectStatus().isNotFound()
                .expectBody().jsonPath("$.type").isEqualTo("urn:ticketflow:problem:event-not-found");
        complimentary(ADMIN_KEY, null, eventId, "{\"quantity\":1}").expectStatus().isBadRequest();
        complimentary(ADMIN_KEY, "bad key!", eventId, "{\"quantity\":1}").expectStatus().isBadRequest();
        complimentary(ADMIN_KEY, newKey(), eventId, "{\"quantity\":0}").expectStatus().isBadRequest();
        complimentary(ADMIN_KEY, newKey(), eventId, "{\"quantity\":1001}").expectStatus().isBadRequest();
        complimentary(ADMIN_KEY, newKey(), eventId, "{}").expectStatus().isBadRequest();
        complimentary(ADMIN_KEY, newKey(), eventId, "{\"quantity\":6}").expectStatus().isEqualTo(409);

        assertThat(availability(eventId)).isEqualTo(new AvailabilityResponse(5, 0, 0, 0, 0, 5));
    }

    @Test
    void complimentary_sameKeyTwentyInParallel_issuesExactlyOnce() throws Exception {
        String eventId = createEvent(30);
        String key = newKey();

        List<Future<String>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            for (int i = 0; i < 20; i++) {
                futures.add(pool.submit(() -> complimentary(eventId, 3, key).expectStatus().isCreated()
                        .expectBody(ComplimentaryResponse.class).returnResult().getResponseBody().orderId()));
            }
        }
        List<String> ids = new ArrayList<>();
        for (Future<String> future : futures) {
            ids.add(future.get());
        }
        assertThat(ids).hasSize(20).doesNotContainNull();
        assertThat(ids.stream().distinct()).hasSize(1);
        assertThat(availability(eventId)).isEqualTo(new AvailabilityResponse(27, 0, 0, 0, 3, 30));
    }

    @Test
    void complimentary_concurrentWithPurchases_neverOversellsAndComplimentaryIsNeverSold() throws Exception {
        int capacity = 12;
        String eventId = createEvent(capacity);

        List<Future<int[]>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(40)) {
            for (int i = 0; i < 40; i++) {
                boolean comp = i % 2 == 0;
                String key = newKey();
                futures.add(pool.submit(() -> {
                    int status = comp
                            ? complimentary(eventId, 1, key).returnResult(String.class).getStatus().value()
                            : purchase(key, eventId, 1).returnResult(String.class).getStatus().value();
                    return new int[] {comp ? 1 : 0, status};
                }));
            }
        }
        int compOk = 0;
        int buyOk = 0;
        for (Future<int[]> future : futures) {
            int[] outcome = future.get();
            if (outcome[1] == 201) {
                compOk++;
            } else if (outcome[1] == 202) {
                buyOk++;
            } else {
                assertThat(outcome[1]).isEqualTo(409);
            }
        }
        assertThat(compOk + buyOk).isEqualTo(capacity);
        int expectedSold = buyOk;
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(availability(eventId).sold()).isEqualTo(expectedSold));

        AvailabilityResponse state = availability(eventId);
        assertThat(state.available()).isZero();
        assertThat(state.complimentary()).isEqualTo(compOk);
        assertThat(state.sold()).isEqualTo(buyOk);
        assertThat(state.available() + state.reserved() + state.pendingConfirmation() + state.sold()
                + state.complimentary()).isEqualTo(capacity);
    }
}
