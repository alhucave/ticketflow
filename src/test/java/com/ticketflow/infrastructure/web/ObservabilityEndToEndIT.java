package com.ticketflow.infrastructure.web;

import com.ticketflow.testsupport.TestTimeouts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketflow.infrastructure.messaging.SqsTestQueues;
import com.ticketflow.infrastructure.observability.JsonLogCapture;
import com.ticketflow.infrastructure.observability.PrometheusScrape;
import com.ticketflow.infrastructure.persistence.DynamoDbTables;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.JsonNode;

/**
 * C12 for observability: the whole application against a REAL DynamoDB Local and a REAL LocalStack SQS (queue with
 * a redrive policy to a DLQ). One purchase is followed from the API to the consumer and read back through the
 * management port: business counters, consumer counters, queue-depth gauges, readiness/liveness, JSON logs with the
 * correlation id on the API line AND on the consumer line, the poison message reaching the DLQ, and the public
 * port not serving actuator. No fixed sleeps: every asynchronous outcome is awaited. Enabled with
 * INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
// The tests share one application context (and its counters and queues): the clean-slate scenario runs first, the
// poison message (which leaves a message in the DLQ for good) second.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ObservabilityEndToEndIT {

    private static final Duration WAIT = TestTimeouts.WAIT;
    private static SqsTestQueues.Queues queues;

    @BeforeAll
    static void startInfrastructure() {
        queues = E2eContainers.newQueue("obs");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        E2eContainers.register(registry, queues.name());
        E2eContainers.generousRateLimits(registry);
        registry.add("ticketflow.sqs.consumer.enabled", () -> "true");
        registry.add("ticketflow.sqs.consumer.wait-time", () -> "1s");
        // A short visibility timeout so the poison message is redelivered (and redriven to the DLQ) within seconds.
        registry.add("ticketflow.sqs.consumer.visibility-timeout", () -> "2s");
        registry.add("ticketflow.observability.queue-metrics.enabled", () -> "true");
        registry.add("ticketflow.observability.queue-metrics.interval", () -> "1s");
        registry.add("ticketflow.observability.health.cache-ttl", () -> "1s");
    }

    @Autowired
    private WebTestClient client;

    @Autowired
    private DynamoDbAsyncClient dynamo;

    @Autowired
    private Environment environment;

    @LocalServerPort
    private int publicPort;

    @LocalManagementPort
    private int managementPort;

    private JsonLogCapture logs;

    @BeforeEach
    void captureLogs() {
        logs = new JsonLogCapture(environment);
    }

    @AfterEach
    void releaseLogs() {
        logs.close();
    }

    private record Reply(int status, String body) { }

    private Reply management(String path) {
        return WebClient.create("http://127.0.0.1:" + managementPort).get().uri(path)
                .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                        .map(body -> new Reply(response.statusCode().value(), body)))
                .block(TestTimeouts.WAIT);
    }

    private PrometheusScrape scrape() {
        Reply reply = management("/actuator/prometheus");
        assertThat(reply.status()).isEqualTo(200);
        return new PrometheusScrape(reply.body());
    }

    private void awaitProvisionedAndReady() {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(dynamo.listTables().join().tableNames())
                        .contains(DynamoDbTables.EVENTS, DynamoDbTables.INVENTORY, DynamoDbTables.ORDERS));
        await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(management("/actuator/health/readiness"))
                        .isEqualTo(new Reply(200, "{\"status\":\"UP\"}")));
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

    private WebTestClient.ResponseSpec purchase(String key, String correlationId, String eventId, int quantity) {
        return client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).header("X-Correlation-Id", correlationId)
                .bodyValue("{\"eventId\":\"%s\",\"quantity\":%d}".formatted(eventId, quantity)).exchange();
    }

    private static List<JsonNode> withMessage(List<JsonNode> lines, String fragment) {
        return lines.stream().filter(line -> line.path("message").asString("").contains(fragment)).toList();
    }

    @Test
    @Order(1)
    void purchase_followedFromApiToConsumer_isVisibleInMetricsProbesAndLogs() {
        awaitProvisionedAndReady();
        // Everything starts at zero (series are registered up front) and nothing is queued.
        PrometheusScrape before = scrape();
        assertThat(before.valueOrNaN("ticketflow_orders_placed_total")).isZero();
        assertThat(before.valueOrNaN("ticketflow_orders_sold_total")).isZero();

        String correlationId = "corr-e2e-" + UUID.randomUUID();
        String eventId = createEvent(10);
        String key = "key-" + UUID.randomUUID();

        var accepted = purchase(key, correlationId, eventId, 3).expectStatus().isAccepted()
                .expectHeader().valueEquals("X-Correlation-Id", correlationId)
                .expectBody().returnResult();
        String orderId = new String(accepted.getResponseBody()).replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1");

        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(client.get().uri("/orders/{id}", orderId).exchange().expectStatus().isOk()
                        .expectBody(OrderStatusResponse.class).returnResult().getResponseBody().status())
                        .isEqualTo("SOLD"));

        // A replay, a reused key and an oversell attempt: each lands in its own series.
        purchase(key, "corr-replay", eventId, 3).expectStatus().isAccepted();
        purchase(key, "corr-reuse", eventId, 4).expectStatus().isEqualTo(409);
        purchase("key-" + UUID.randomUUID(), "corr-oversell", eventId, 8).expectStatus().isEqualTo(409);

        // Counters (the consumer counts after it deletes the message, so await the processed series).
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            PrometheusScrape metrics = scrape();
            assertThat(metrics.valueOrNaN("ticketflow_orders_placed_total")).isEqualTo(1);
            assertThat(metrics.valueOrNaN("ticketflow_orders_sold_total")).isEqualTo(1);
            assertThat(metrics.valueOrNaN("ticketflow_orders_processed_total", "outcome=sold")).isEqualTo(1);
            assertThat(metrics.valueOrNaN("ticketflow_consumer_messages_total", "outcome=processed")).isEqualTo(1);
            assertThat(metrics.valueOrNaN("ticketflow_consumer_messages_total", "outcome=failed")).isZero();
            assertThat(metrics.valueOrNaN("ticketflow_consumer_messages_total", "outcome=poison")).isZero();
            assertThat(metrics.valueOrNaN("ticketflow_consumer_processing_duration_seconds_count")).isEqualTo(1);
            assertThat(metrics.valueOrNaN("ticketflow_queue_publish_total", "outcome=ok")).isEqualTo(1);
            assertThat(metrics.valueOrNaN("ticketflow_queue_publish_total", "outcome=failed")).isZero();
            assertThat(metrics.valueOrNaN("ticketflow_purchases_replayed_total")).isEqualTo(1);
            assertThat(metrics.valueOrNaN("ticketflow_purchases_rejected_total", "reason=idempotency_key_reused"))
                    .isEqualTo(1);
            assertThat(metrics.valueOrNaN("ticketflow_purchases_rejected_total", "reason=insufficient_inventory"))
                    .isEqualTo(1);
            assertThat(metrics.valueOrNaN("ticketflow_conflicts_total", "type=inventory_insufficient",
                    "operation=purchase")).isEqualTo(1);
            assertThat(metrics.valueOrNaN("ticketflow_orders_released_total", "reason=expired")).isZero();
            assertThat(metrics.valueOrNaN("ticketflow_dependency_unavailable_total")).isZero();
            assertThat(metrics.text()).contains("http_server_requests_seconds_count");
        });

        // Queue-depth gauges answer from memory and show an empty queue and an empty DLQ once processed.
        await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            PrometheusScrape metrics = scrape();
            assertThat(metrics.valueOrNaN("ticketflow_queue_messages", "queue=orders", "state=visible")).isZero();
            assertThat(metrics.valueOrNaN("ticketflow_queue_messages", "queue=orders", "state=in_flight")).isZero();
            assertThat(metrics.valueOrNaN("ticketflow_queue_messages", "queue=dlq", "state=visible")).isZero();
            assertThat(metrics.valueOrNaN("ticketflow_queue_stats_refreshes_total", "result=ok")).isPositive();
            assertThat(metrics.valueOrNaN("ticketflow_queue_stats_refreshes_total", "result=error")).isZero();
            assertThat(metrics.valueOrNaN("ticketflow_queue_stats_age_seconds")).isBetween(0.0, 30.0);
        });

        // Probes: UP, UP, and only the status is shown.
        assertThat(management("/actuator/health/liveness")).isEqualTo(new Reply(200, "{\"status\":\"UP\"}"));
        assertThat(management("/actuator/health/readiness")).isEqualTo(new Reply(200, "{\"status\":\"UP\"}"));
        assertThat(management("/actuator/health").body()).contains("\"status\":\"UP\"")
                .doesNotContain("components", "details");

        // Logs: valid JSON; the API line and the consumer line of this purchase carry the same correlation id.
        List<JsonNode> lines = logs.lines();
        List<JsonNode> placed = withMessage(lines, "Order " + orderId + " placed");
        List<JsonNode> processed = withMessage(lines, "Order " + orderId + " processed as Sold");
        assertThat(placed).hasSize(1);
        assertThat(placed.get(0).path("correlationId").asString()).isEqualTo(correlationId);
        assertThat(processed).hasSize(1);
        assertThat(processed.get(0).path("correlationId").asString()).isEqualTo(correlationId);
        assertThat(processed.get(0).path("log").path("logger").asString()).contains("SqsOrderConsumer");
        assertThat(placed.get(0).path("service").path("name").asString()).isEqualTo("ticketflow");
        assertThat(logs.text()).as("no stack trace or secret on a healthy run").doesNotContain("stack_trace");
    }

    @Test
    @Order(2)
    void poisonMessage_isCountedRedrivenToTheDlqAndShownInTheGauge() {
        awaitProvisionedAndReady();
        SqsAsyncClient sqs = E2eContainers.start();
        String correlationId = "corr-poison-" + UUID.randomUUID();
        double poisonBefore = scrape().valueOrNaN("ticketflow_consumer_messages_total", "outcome=poison");

        sqs.sendMessage(SendMessageRequest.builder().queueUrl(queues.url()).messageBody("this is not json {")
                .messageAttributes(java.util.Map.of("correlationId", MessageAttributeValue.builder()
                        .dataType("String").stringValue(correlationId).build())).build()).join();

        await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(scrape().valueOrNaN("ticketflow_consumer_messages_total", "outcome=poison"))
                        .isGreaterThan(poisonBefore));
        // After maxReceiveCount failed deliveries SQS moves it to the DLQ; the depth poller reports it.
        await().atMost(WAIT).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(scrape().valueOrNaN("ticketflow_queue_messages", "queue=dlq", "state=visible"))
                        .isEqualTo(1));
        await().atMost(WAIT).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(scrape().valueOrNaN("ticketflow_queue_messages", "queue=orders", "state=visible"))
                        .isZero());

        // The poison warning logs restored the correlation id of the message, without echoing its body.
        var discards = withMessage(logs.lines(), "Discarding poison message");
        assertThat(discards).isNotEmpty().allSatisfy(line ->
                assertThat(line.path("correlationId").asString()).isEqualTo(correlationId));
        assertThat(logs.text()).doesNotContain("this is not json");
        assertThat(SqsTestQueues.total(sqs, queues.dlqUrl())).isEqualTo(1);
    }

    @Test
    @Order(3)
    void publicPort_neverServesActuator_andManagementPortExposesOnlyTheAllowedEndpoints() {
        assertThat(managementPort).isNotEqualTo(publicPort);
        for (String path : new String[] {"/actuator", "/actuator/health", "/actuator/health/readiness",
                "/actuator/health/liveness", "/actuator/prometheus", "/actuator/info", "/actuator/env"}) {
            client.get().uri(path).exchange().expectStatus().isNotFound();
        }
        for (String endpoint : new String[] {"env", "beans", "heapdump", "loggers", "metrics", "threaddump"}) {
            assertThat(management("/actuator/" + endpoint).status()).as(endpoint).isEqualTo(404);
        }
        assertThat(management("/actuator/info").status()).isEqualTo(200);
    }
}
