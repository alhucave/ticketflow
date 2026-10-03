package com.ticketflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketflow.infrastructure.messaging.SqsTestQueues;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * C12 for the readiness probe with REAL SQS but an UNREACHABLE DynamoDB (an address nobody listens on): readiness
 * is DOWN (503, status only), while liveness stays UP because it never depends on an external system. The mirror
 * case, DynamoDB real and the queue missing, is covered by {@link ReadinessDownMissingQueueEndToEndIT}.
 * Enabled with INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReadinessDownEndToEndIT {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static SqsTestQueues.Queues queues;

    @BeforeAll
    static void createQueue() {
        queues = E2eContainers.newQueue("ready-down");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        E2eContainers.register(registry, queues.name());
        // DynamoDB: a closed port (connection refused), after the containers' value was registered.
        registry.add("ticketflow.dynamodb.endpoint", () -> "http://127.0.0.1:" + closedPort());
        registry.add("ticketflow.dynamodb.provisioning-enabled", () -> "false");
        registry.add("ticketflow.observability.health.timeout", () -> "1s");
        registry.add("ticketflow.observability.health.cache-ttl", () -> "0s");
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @LocalManagementPort
    private int managementPort;

    private String body(String path) {
        return WebClient.create("http://127.0.0.1:" + managementPort).get().uri(path)
                .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                        .map(body -> response.statusCode().value() + " " + body))
                .block(Duration.ofSeconds(20));
    }

    @Test
    void readiness_dynamoDbUnreachableButSqsUp_isDownWhileLivenessStaysUp() {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(body("/actuator/health/readiness")).isEqualTo("503 {\"status\":\"DOWN\"}"));
        assertThat(body("/actuator/health/liveness")).isEqualTo("200 {\"status\":\"UP\"}");
        assertThat(body("/actuator/health")).startsWith("503 ").doesNotContain("components", "dynamodb");
    }
}
