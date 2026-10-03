package com.ticketflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketflow.infrastructure.persistence.DynamoDbTables;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * C12 for the readiness probe with REAL DynamoDB Local (tables provisioned) but an orders queue that does not
 * exist in the REAL LocalStack SQS: readiness is DOWN because of SQS alone (DynamoDB is fine), liveness is UP.
 * Enabled with INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReadinessDownMissingQueueEndToEndIT {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        E2eContainers.start();
        E2eContainers.register(registry, "missing-queue-" + UUID.randomUUID());
        registry.add("ticketflow.observability.health.cache-ttl", () -> "0s");
    }

    @Autowired
    private DynamoDbAsyncClient dynamo;

    @LocalManagementPort
    private int managementPort;

    private String body(String path) {
        return WebClient.create("http://127.0.0.1:" + managementPort).get().uri(path)
                .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                        .map(body -> response.statusCode().value() + " " + body))
                .block(Duration.ofSeconds(20));
    }

    @Test
    void readiness_queueMissingButDynamoDbUp_isDownWhileLivenessStaysUp() {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(dynamo.listTables().join().tableNames()).contains(DynamoDbTables.ORDERS));
        await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(body("/actuator/health/readiness")).isEqualTo("503 {\"status\":\"DOWN\"}"));
        assertThat(body("/actuator/health/liveness")).isEqualTo("200 {\"status\":\"UP\"}");
    }
}
