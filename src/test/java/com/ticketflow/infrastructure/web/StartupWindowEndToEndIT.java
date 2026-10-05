package com.ticketflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ticketflow.infrastructure.persistence.DynamoDbTableProvisioner;
import com.ticketflow.infrastructure.persistence.DynamoDbTables;
import com.ticketflow.testsupport.TestTimeouts;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * Startup-window regression (F-034, DP-038): the web server is already answering while the DynamoDB tables do not
 * exist yet (provisioning runs at ApplicationReady, after Netty accepts traffic). The window is held open on purpose:
 * provisioning is disabled and the test owns a DynamoDB Local container that no other test class has touched, so the
 * tables are really missing. Every API route that touches DynamoDB must answer {@code 503} + {@code Retry-After}
 * (a temporary, expected condition), never the generic {@code 500}; the cause stays in the server log, readiness
 * stays DOWN and nothing internal leaks. The later tests create the tables and prove the API is not stuck in 503
 * and that business errors (404/409) are unchanged. Methods are ordered: the window ones must run first.
 * Enabled with INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StartupWindowEndToEndIT {

    private static final Duration WAIT = TestTimeouts.WAIT;
    private static final String TYPE = "urn:ticketflow:problem:";
    private static final String ADMIN_KEY = "startup-window-admin-key-" + UUID.randomUUID();

    /** Own container (not the shared E2eContainers one): another class would already have created the tables. */
    private static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000)
            .withStartupTimeout(TestTimeouts.CONTAINER_STARTUP);
    private static String queueName;

    @BeforeAll
    static void startInfrastructure() {
        DYNAMO.start();
        queueName = E2eContainers.newQueue("startup-window").name();
    }

    @AfterAll
    static void stopInfrastructure() {
        DYNAMO.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        E2eContainers.register(registry, queueName);
        registry.add("ticketflow.dynamodb.endpoint",
                () -> "http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000));
        // The window stays open: nobody creates the tables until the test does it.
        registry.add("ticketflow.dynamodb.provisioning-enabled", () -> "false");
        registry.add("ticketflow.admin.api-key", () -> ADMIN_KEY);
        registry.add("ticketflow.observability.health.cache-ttl", () -> "0s");
        E2eContainers.generousRateLimits(registry);
    }

    @Autowired
    private WebTestClient client;

    @Autowired
    private DynamoDbAsyncClient dynamo;

    @Autowired
    private MeterRegistry meters;

    @LocalManagementPort
    private int managementPort;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger root;

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(logs);
        logs.stop();
    }

    private double unavailableCount() {
        var counter = meters.find("ticketflow.dependency.unavailable").counter();
        return counter == null ? 0 : counter.count();
    }

    private String readiness() {
        return WebClient.create("http://127.0.0.1:" + managementPort).get().uri("/actuator/health/readiness")
                .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                        .map(body -> response.statusCode().value() + " " + body))
                .block(WAIT);
    }

    private static String eventJson() {
        return """
                {"name":"Concert","startsAt":"%s","venue":"Arena","capacity":10}"""
                .formatted(Instant.now().plus(Duration.ofDays(30)));
    }

    /** The one expected answer of the window: 503 + Retry-After + problem+json that reveals nothing internal. */
    private void expectUnavailable(Supplier<WebTestClient.ResponseSpec> request) {
        double before = unavailableCount();
        String body = new String(request.get().expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "5")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo(TYPE + "service-unavailable")
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.correlationId").isNotEmpty()
                .returnResult().getResponseBody());
        assertThat(body).doesNotContain("non-existent", "ResourceNotFound", "dynamodb", "amazonaws",
                DynamoDbTables.EVENTS, DynamoDbTables.ORDERS, DynamoDbTables.INVENTORY, "Exception");
        assertThat(unavailableCount()).isEqualTo(before + 1);
    }

    @Test
    @Order(1)
    void tablesDoNotExistYet() {
        assertThat(dynamo.listTables().join().tableNames()).isEmpty();
    }

    @Test
    @Order(2)
    void window_postEvent_is503NotGeneric500() {
        expectUnavailable(() -> client.post().uri("/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(eventJson()).exchange());
    }

    @Test
    @Order(3)
    void window_listEvents_is503() {
        expectUnavailable(() -> client.get().uri("/events").exchange());
    }

    @Test
    @Order(4)
    void window_getEvent_is503() {
        expectUnavailable(() -> client.get().uri("/events/{id}", UUID.randomUUID().toString()).exchange());
    }

    @Test
    @Order(5)
    void window_availability_is503() {
        expectUnavailable(() -> client.get().uri("/events/{id}/availability", UUID.randomUUID().toString()).exchange());
    }

    @Test
    @Order(6)
    void window_availabilityStream_is503BeforeTheStreamStarts() {
        expectUnavailable(() -> client.get().uri("/events/{id}/availability/stream", UUID.randomUUID().toString())
                .accept(MediaType.TEXT_EVENT_STREAM).exchange());
    }

    @Test
    @Order(7)
    void window_postOrder_is503() {
        expectUnavailable(() -> client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue("{\"eventId\":\"%s\",\"quantity\":1}".formatted(UUID.randomUUID())).exchange());
    }

    @Test
    @Order(8)
    void window_getOrder_is503() {
        expectUnavailable(() -> client.get().uri("/orders/{id}", UUID.randomUUID().toString()).exchange());
    }

    @Test
    @Order(9)
    void window_complimentary_is503() {
        expectUnavailable(() -> client.post().uri("/events/{id}/complimentary", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Admin-Key", ADMIN_KEY)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue("{\"quantity\":1,\"reason\":\"guest\"}").exchange());
    }

    @Test
    @Order(10)
    void window_keepsReadinessDownAndLogsTheFullCause() {
        client.get().uri("/events").exchange().expectStatus().isEqualTo(503);

        assertThat(readiness()).isEqualTo("503 {\"status\":\"DOWN\"}");
        // The real cause is not lost: the request-time log carries the original exception (message and stack).
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLoggerName()).contains("ApiExceptionHandler");
            assertThat(event.getFormattedMessage()).contains("tables are missing");
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(ResourceNotFoundException.class.getName());
            assertThat(event.getThrowableProxy().getMessage()).contains("non-existent table");
        });
    }

    /** Provisioning creates the tables one by one: a transaction can find the events table but not the inventory. */
    @Test
    @Order(11)
    void window_onlyEventsTableCreated_writesAcrossTablesAreStill503() {
        var events = DynamoDbTables.definitions().stream()
                .filter(definition -> definition.tableName().equals(DynamoDbTables.EVENTS)).findFirst().orElseThrow();
        dynamo.createTable(events).join();

        expectUnavailable(() -> client.post().uri("/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(eventJson()).exchange());
        client.get().uri("/events").exchange().expectStatus().isOk();
    }

    @Test
    @Order(12)
    void afterTablesExist_sameRoutesWorkAndBusinessErrorsAreUnchanged() {
        new DynamoDbTableProvisioner(dynamo, 60, Duration.ofMillis(200)).provision().block(WAIT);

        String location = client.post().uri("/events").contentType(MediaType.APPLICATION_JSON).bodyValue(eventJson())
                .exchange().expectStatus().isCreated()
                .expectBody().returnResult().getResponseHeaders().getFirst("Location");
        String eventId = location.substring("/events/".length());
        client.get().uri("/events").exchange().expectStatus().isOk();
        client.get().uri("/events/{id}", eventId).exchange().expectStatus().isOk();
        client.get().uri("/events/{id}/availability", eventId).exchange().expectStatus().isOk();
        // Take the first element and cancel: an open stream would keep the context's graceful shutdown waiting.
        assertThat(client.get().uri("/events/{id}/availability/stream", eventId).accept(MediaType.TEXT_EVENT_STREAM)
                .exchange().expectStatus().isOk().returnResult(AvailabilityResponse.class).getResponseBody()
                .blockFirst(WAIT)).isNotNull();
        client.post().uri("/events/{id}/complimentary", eventId).contentType(MediaType.APPLICATION_JSON)
                .header("X-Admin-Key", ADMIN_KEY).header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue("{\"quantity\":1,\"reason\":\"guest\"}").exchange().expectStatus().is2xxSuccessful();
        String orderId = client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue("{\"eventId\":\"%s\",\"quantity\":2}".formatted(eventId))
                .exchange().expectStatus().isAccepted()
                .expectBody(PurchaseAcceptedResponse.class).returnResult().getResponseBody().orderId();
        client.get().uri("/orders/{id}", orderId).exchange().expectStatus().isOk();

        // Business errors keep their own statuses: they are not reclassified as an outage.
        double before = unavailableCount();
        client.get().uri("/events/{id}", UUID.randomUUID().toString()).exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "event-not-found");
        client.get().uri("/orders/{id}", UUID.randomUUID().toString()).exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "order-not-found");
        client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue("{\"eventId\":\"%s\",\"quantity\":10}".formatted(eventId))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "insufficient-inventory");
        assertThat(unavailableCount()).isEqualTo(before);

        await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(readiness()).isEqualTo("200 {\"status\":\"UP\"}"));
    }
}
