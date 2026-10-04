package com.ticketflow;

import com.ticketflow.testsupport.TestTimeouts;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Actuator on its own (management) port, with no Docker: DynamoDB and SQS point at a port nobody listens on,
 * so the readiness group is deterministically DOWN while liveness (which never depends on them) stays UP.
 * Also pins what is exposed: only health, info and prometheus, on the management port only, UP/DOWN only.
 */
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "ticketflow.observability.health.timeout=500ms"})
class ObservabilityEndpointsTest {

    private static final int CLOSED_PORT = closedPort();

    @DynamicPropertySource
    static void unreachableDependencies(DynamicPropertyRegistry registry) {
        registry.add("ticketflow.dynamodb.endpoint", () -> "http://127.0.0.1:" + CLOSED_PORT);
        registry.add("ticketflow.dynamodb.access-key-id", () -> "test");
        registry.add("ticketflow.dynamodb.secret-access-key", () -> "test");
        registry.add("ticketflow.sqs.endpoint", () -> "http://127.0.0.1:" + CLOSED_PORT);
        registry.add("ticketflow.sqs.access-key-id", () -> "test");
        registry.add("ticketflow.sqs.secret-access-key", () -> "test");
    }

    @LocalServerPort
    private int publicPort;

    @LocalManagementPort
    private int managementPort;

    private WebClient management() {
        return WebClient.create("http://127.0.0.1:" + managementPort);
    }

    private WebClient publicApi() {
        return WebClient.create("http://127.0.0.1:" + publicPort);
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Reply(int status, String body) { }

    private static Reply get(WebClient client, String path) {
        return client.get().uri(path).exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("").map(body -> new Reply(response.statusCode().value(), body)))
                .block(TestTimeouts.WAIT);
    }

    @Test
    void managementPort_isDifferentFromPublicPort() {
        assertThat(managementPort).isPositive().isNotEqualTo(publicPort);
    }

    @Test
    void liveness_dependenciesDown_staysUp() {
        Reply liveness = get(management(), "/actuator/health/liveness");
        assertThat(liveness.status()).isEqualTo(200);
        assertThat(liveness.body()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void readiness_dynamoDbUnreachable_isDownWithoutDetails() {
        Reply readiness = get(management(), "/actuator/health/readiness");
        assertThat(readiness.status()).isEqualTo(503);
        assertThat(readiness.body()).isEqualTo("{\"status\":\"DOWN\"}");
    }

    @Test
    void health_aggregate_showsOnlyTheStatus() {
        Reply health = get(management(), "/actuator/health");
        assertThat(health.status()).isEqualTo(503);
        assertThat(health.body()).contains("\"status\":\"DOWN\"")
                .doesNotContain("components", "details", "127.0.0.1", "dynamodb", "sqs");
    }

    @Test
    void prometheus_onManagementPort_exposesBusinessMetricsStartingAtZero() {
        Reply scrape = get(management(), "/actuator/prometheus");
        assertThat(scrape.status()).isEqualTo(200);
        assertThat(scrape.body())
                .contains("ticketflow_orders_placed_total{application=\"ticketflow\"} 0.0")
                .contains("ticketflow_orders_sold_total")
                .contains("ticketflow_purchases_rejected_total{application=\"ticketflow\",reason=\"insufficient_inventory\"} 0.0")
                .contains("ticketflow_consumer_messages_total")
                .contains("http_server_requests");
    }

    @Test
    void info_isServedButEmpty() {
        assertThat(get(management(), "/actuator/info").status()).isEqualTo(200);
    }

    @Test
    void otherActuatorEndpoints_areNotExposed() {
        for (String endpoint : new String[] {"env", "beans", "heapdump", "loggers", "metrics", "configprops",
                "mappings", "threaddump", "shutdown"}) {
            assertThat(get(management(), "/actuator/" + endpoint).status()).as(endpoint).isEqualTo(404);
        }
    }

    @Test
    void publicPort_actuator_isNotServed() {
        for (String path : new String[] {"/actuator", "/actuator/health", "/actuator/health/liveness",
                "/actuator/prometheus", "/actuator/info"}) {
            Reply reply = get(publicApi(), path);
            assertThat(reply.status()).as(path).isEqualTo(404);
            assertThat(reply.body()).as(path).doesNotContain("\"status\":\"UP\"", "ticketflow_orders");
        }
    }
}
