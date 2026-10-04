package com.ticketflow.infrastructure.web;

import com.ticketflow.testsupport.TestTimeouts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketflow.infrastructure.messaging.SqsTestQueues;
import com.ticketflow.infrastructure.persistence.DynamoDbTables;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * C12: rate limiting over HTTP against the whole application context with REAL DynamoDB Local and LocalStack
 * SQS. Clients are told apart by {@code X-Forwarded-For} (trust-forwarded-for is on; every request really comes
 * from loopback), and each test uses its own client addresses because the budgets live as long as the context.
 * The refill is so slow that no budget refills during a test. Enabled with INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class RateLimitEndToEndIT {

    private static final Duration WAIT = TestTimeouts.WAIT;
    private static final String ADMIN_KEY = "e2e-admin-key-" + UUID.randomUUID();
    private static final String SETUP_CLIENT = "203.0.113.250";
    private static final String TYPE = "urn:ticketflow:problem:";
    /** Each event creation (a limited POST) comes from a fresh address so setup never spends a test budget. */
    private static final java.util.concurrent.atomic.AtomicInteger SETUP_SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger();
    private static SqsTestQueues.Queues queues;

    @BeforeAll
    static void startInfrastructure() {
        queues = E2eContainers.newQueue("orders-ratelimit");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        E2eContainers.register(registry, queues.name());
        registry.add("ticketflow.admin.api-key", () -> ADMIN_KEY);
        registry.add("ticketflow.rate-limit.trust-forwarded-for", () -> "true");
        registry.add("ticketflow.rate-limit.capacity", () -> "10");
        registry.add("ticketflow.rate-limit.refill-per-second", () -> "0.01");
        registry.add("ticketflow.rate-limit.admin-failure-capacity", () -> "3");
        registry.add("ticketflow.rate-limit.admin-failure-refill-per-second", () -> "0.01");
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
                .header("X-Forwarded-For", "198.51.100." + SETUP_SEQUENCE.incrementAndGet())
                .bodyValue("""
                        {"name":"Concert","startsAt":"%s","venue":"Arena","capacity":%d}"""
                        .formatted(Instant.now().plus(Duration.ofDays(30)), capacity))
                .exchange().expectStatus().isCreated()
                .expectBody().returnResult().getResponseHeaders().getFirst("Location");
        return location.substring("/events/".length());
    }

    private WebTestClient.ResponseSpec purchase(String clientIp, String eventId, int quantity) {
        return client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "9.9.9.9, " + clientIp) // the leading entry is spoofable and ignored
                .header("Idempotency-Key", "key-" + UUID.randomUUID())
                .bodyValue("{\"eventId\":\"%s\",\"quantity\":%d}".formatted(eventId, quantity)).exchange();
    }

    private WebTestClient.ResponseSpec complimentary(String clientIp, String adminKey, String eventId) {
        var spec = client.post().uri("/events/{id}/complimentary", eventId).contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", clientIp).header("Idempotency-Key", "key-" + UUID.randomUUID());
        if (adminKey != null) {
            spec.header("X-Admin-Key", adminKey);
        }
        return spec.bodyValue("{\"quantity\":1,\"reason\":\"guest\"}").exchange();
    }

    private AvailabilityResponse availability(String eventId) {
        return client.get().uri("/events/{id}/availability", eventId).header("X-Forwarded-For", SETUP_CLIENT)
                .exchange().expectStatus().isOk().expectBody(AvailabilityResponse.class).returnResult()
                .getResponseBody();
    }

    @Test
    void purchase_clientExceedsItsBudget_gets429WithRetryAfterWhileOtherClientsAndReadsAreUnaffected() {
        String eventId = createEvent(50);
        String greedy = "198.51.100.201";

        for (int i = 0; i < 10; i++) {
            purchase(greedy, eventId, 1).expectStatus().isAccepted();
        }
        var limited = purchase(greedy, eventId, 1).expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().value(HttpHeaders.RETRY_AFTER, value ->
                        assertThat(Long.parseLong(value)).isBetween(1L, 200L))
                .expectHeader().exists("X-Correlation-Id")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody()
                .jsonPath("$.type").isEqualTo(TYPE + "rate-limit-exceeded")
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.correlationId").isNotEmpty()
                .returnResult();
        assertThat(new String(limited.getResponseBodyContent())).doesNotContain("Exception");

        // Another client is unaffected, and so are reads by the exhausted one.
        purchase("198.51.100.202", eventId, 1).expectStatus().isAccepted();
        client.get().uri("/events/{id}", eventId).header("X-Forwarded-For", greedy).exchange().expectStatus().isOk();
        // The rejected request reserved nothing: 10 + 1 purchases of one ticket each.
        assertThat(availability(eventId)).isEqualTo(new AvailabilityResponse(39, 11, 0, 0, 0, 50));
    }

    @Test
    void complimentary_clientExceedsItsBudget_gets429() {
        String eventId = createEvent(50);
        String client = "198.51.100.203";
        for (int i = 0; i < 10; i++) {
            complimentary(client, ADMIN_KEY, eventId).expectStatus().isCreated();
        }
        complimentary(client, ADMIN_KEY, eventId).expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "rate-limit-exceeded");
        assertThat(availability(eventId).complimentary()).isEqualTo(10);
    }

    @Test
    void adminKeyBruteForce_failedAttemptsExhaustTheBudget_thenEvenTheRightKeyIs429() {
        String eventId = createEvent(10);
        String attacker = "198.51.100.204";

        for (int i = 0; i < 3; i++) {
            complimentary(attacker, "guess-" + i, eventId).expectStatus().isUnauthorized()
                    .expectBody().jsonPath("$.type").isEqualTo(TYPE + "admin-unauthorized");
        }
        complimentary(attacker, "guess-4", eventId).expectStatus().isEqualTo(429)
                .expectHeader().value(HttpHeaders.RETRY_AFTER, value ->
                        assertThat(Long.parseLong(value)).isGreaterThan(1L))
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "rate-limit-exceeded");
        // The brute-forcer cannot even confirm a correct guess during the lockout.
        complimentary(attacker, ADMIN_KEY, eventId).expectStatus().isEqualTo(429);
        assertThat(availability(eventId).complimentary()).isZero();

        // A different client with the right key is not locked out.
        complimentary("198.51.100.205", ADMIN_KEY, eventId).expectStatus().isCreated();
        assertThat(availability(eventId).complimentary()).isEqualTo(1);
    }

    @Test
    void adminKeyMissing_countsAsFailedAttempt() {
        String eventId = createEvent(10);
        String attacker = "198.51.100.206";
        for (int i = 0; i < 3; i++) {
            complimentary(attacker, null, eventId).expectStatus().isUnauthorized();
        }
        complimentary(attacker, null, eventId).expectStatus().isEqualTo(429);
    }
}
