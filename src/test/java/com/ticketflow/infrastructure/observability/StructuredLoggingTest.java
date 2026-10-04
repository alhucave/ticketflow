package com.ticketflow.infrastructure.observability;

import com.ticketflow.testsupport.TestTimeouts;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;

/**
 * Structured logging: the application's log lines are valid JSON (Spring Boot's ECS format), carry the
 * correlation id of the request in the MDC, name the service, and never contain a secret. No Docker needed
 * (the dependencies point at a closed port; the requests below never reach them).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "ticketflow.admin.api-key=" + StructuredLoggingTest.ADMIN_SECRET,
                "ticketflow.dynamodb.secret-access-key=" + StructuredLoggingTest.DYNAMO_SECRET,
                "ticketflow.sqs.secret-access-key=" + StructuredLoggingTest.SQS_SECRET,
                "ticketflow.dynamodb.access-key-id=AKIA-DYNAMO-ID-MUST-NOT-LEAK",
                "ticketflow.sqs.access-key-id=AKIA-SQS-ID-MUST-NOT-LEAK",
                "logging.structured.ecs.service.environment=test-env"})
class StructuredLoggingTest {

    static final String ADMIN_SECRET = "admin-secret-must-never-be-logged";
    static final String DYNAMO_SECRET = "dynamo-secret-must-never-be-logged";
    static final String SQS_SECRET = "sqs-secret-must-never-be-logged";
    private static final String WRONG_KEY = "client-supplied-wrong-key-xyz";
    private static final Logger LOG = LoggerFactory.getLogger(StructuredLoggingTest.class);
    private static final int CLOSED_PORT = closedPort();

    @DynamicPropertySource
    static void unreachableDependencies(DynamicPropertyRegistry registry) {
        registry.add("ticketflow.dynamodb.endpoint", () -> "http://127.0.0.1:" + CLOSED_PORT);
        registry.add("ticketflow.sqs.endpoint", () -> "http://127.0.0.1:" + CLOSED_PORT);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Autowired
    private com.ticketflow.infrastructure.config.SqsProperties sqsProperties;

    @Autowired
    private com.ticketflow.infrastructure.config.DynamoDbProperties dynamoProperties;

    @Autowired
    private com.ticketflow.infrastructure.web.AdminKeyWebFilter adminFilter;

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private int status(String method, String path, String correlationId, String adminKey) {
        return WebClient.create("http://127.0.0.1:" + port).method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path)
                .headers(headers -> {
                    if (correlationId != null) {
                        headers.set("X-Correlation-Id", correlationId);
                    }
                    if (adminKey != null) {
                        headers.set("X-Admin-Key", adminKey);
                    }
                })
                .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().value()))
                .block(TestTimeouts.WAIT);
    }

    private static List<JsonNode> withMessage(List<JsonNode> lines, String fragment) {
        return lines.stream().filter(line -> line.path("message").asString("").contains(fragment)).toList();
    }

    @Test
    void logs_requestLine_isJsonWithTheRequestCorrelationId() {
        try (var capture = new JsonLogCapture(environment)) {
            assertThat(status("GET", "/no-such-route", "corr-json-test-1", null)).isEqualTo(404);

            var rejected = withMessage(capture.lines(), "Request rejected");
            assertThat(rejected).hasSize(1);
            assertThat(rejected.get(0).path("correlationId").asString()).isEqualTo("corr-json-test-1");
            assertThat(rejected.get(0).path("log").path("level").asString()).isEqualTo("INFO");
            assertThat(rejected.get(0).path("@timestamp").asString()).isNotBlank();
            assertThat(rejected.get(0).path("log").path("logger").asString()).contains("ApiExceptionHandler");
        }
    }

    @Test
    void logs_differentRequests_eachLineKeepsItsOwnCorrelationId() {
        try (var capture = new JsonLogCapture(environment)) {
            status("GET", "/no-such-route-a", "corr-A", null);
            status("GET", "/no-such-route-b", "corr-B", null);

            var ids = withMessage(capture.lines(), "Request rejected").stream()
                    .map(line -> line.path("correlationId").asString()).toList();
            assertThat(ids).containsExactlyInAnyOrder("corr-A", "corr-B");
        }
    }

    @Test
    void logs_unsafeCorrelationIdFromTheClient_neverReachesTheLog() {
        try (var capture = new JsonLogCapture(environment)) {
            status("GET", "/no-such-route", "bad id with spaces", null);

            assertThat(capture.text()).doesNotContain("bad id with spaces");
            var rejected = withMessage(capture.lines(), "Request rejected");
            assertThat(rejected).hasSize(1);
            assertThat(rejected.get(0).path("correlationId").asString()).matches("[0-9a-f-]{36}");
        }
    }

    @Test
    void logs_serviceNameVersionAndEnvironment_comeFromConfiguration() {
        try (var capture = new JsonLogCapture(environment)) {
            LOG.info("service fields probe");

            var probe = withMessage(capture.lines(), "service fields probe");
            assertThat(probe).hasSize(1);
            assertThat(probe.get(0).path("service").path("name").asString()).isEqualTo("ticketflow");
            assertThat(probe.get(0).path("service").path("version").asString()).isNotBlank().doesNotContain("@");
            assertThat(probe.get(0).path("service").path("environment").asString()).isEqualTo("test-env");
        }
    }

    @Test
    void logs_mdcCorrelationId_isOnEveryLineOfAnInstrumentedBlock() {
        try (var capture = new JsonLogCapture(environment)) {
            MDC.put("correlationId", "corr-mdc-1");
            try {
                LOG.info("first");
                LOG.warn("second");
            } finally {
                MDC.remove("correlationId");
            }
            LOG.info("outside");

            var lines = capture.lines();
            assertThat(withMessage(lines, "first").get(0).path("correlationId").asString()).isEqualTo("corr-mdc-1");
            assertThat(withMessage(lines, "second").get(0).path("correlationId").asString()).isEqualTo("corr-mdc-1");
            assertThat(withMessage(lines, "outside").get(0).has("correlationId")).isFalse();
        }
    }

    @Test
    void logs_exception_hasTheStackTraceServerSideInTheJson() {
        try (var capture = new JsonLogCapture(environment)) {
            LOG.error("boom", new IllegalStateException("kaput"));

            var boom = withMessage(capture.lines(), "boom");
            assertThat(boom).hasSize(1);
            assertThat(boom.get(0).path("error").path("type").asString()).isEqualTo("java.lang.IllegalStateException");
            assertThat(boom.get(0).path("error").path("stack_trace").asString()).contains("IllegalStateException", "kaput",
                    "logs_exception_hasTheStackTraceServerSideInTheJson");
        }
    }

    @Test
    void logs_secrets_neverAppearInJsonLines() {
        try (var capture = new JsonLogCapture(environment)) {
            // A wrong and a missing admin key (the filter logs the rejection), plus the configuration objects
            // that carry the secrets being rendered the way a careless log statement would.
            assertThat(status("POST", "/events/evt-1/complimentary", "corr-secret-1", WRONG_KEY)).isEqualTo(401);
            assertThat(status("POST", "/events/evt-1/complimentary", "corr-secret-2", null)).isEqualTo(401);
            LOG.info("config {} {} {}", sqsProperties, dynamoProperties, adminFilter);
            LOG.info("env {}", environment.getProperty("ticketflow.sqs.endpoint"));

            String logged = capture.text();
            capture.lines(); // every line is valid JSON
            assertThat(logged).isNotBlank()
                    .doesNotContain(ADMIN_SECRET, DYNAMO_SECRET, SQS_SECRET, WRONG_KEY)
                    .doesNotContain("AKIA-DYNAMO-ID-MUST-NOT-LEAK", "AKIA-SQS-ID-MUST-NOT-LEAK");
        }
    }

    @Test
    void logs_consoleFormatDefault_isTheHumanReadablePattern() {
        assertThat(environment.getProperty("logging.structured.format.console")).isNull();
        assertThat(environment.getProperty("logging.pattern.correlation")).contains("%X{correlationId");
    }
}
