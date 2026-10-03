package com.ticketflow.infrastructure.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ticketflow.infrastructure.messaging.SqsTestQueues;
import com.ticketflow.infrastructure.persistence.DynamoDbTables;
import com.ticketflow.infrastructure.web.PurchaseAcceptedResponse;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * C12: error handling and correlation id through the WHOLE application context (real controllers, use
 * cases, DynamoDB and SQS adapters, Spring Boot's own error infrastructure) against a REAL DynamoDB Local
 * and a REAL LocalStack SQS. The queue consumer is disabled so the test can read the published message
 * and check that the client's {@code X-Correlation-Id} travelled as its {@code correlationId} attribute.
 * Enabled with INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
class ErrorHandlingEndToEndIT {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final String TYPE = "urn:ticketflow:problem:";

    private static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000);
    private static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.14.0")).withServices("sqs");
    private static SqsTestQueues.Queues queues;
    private static SqsAsyncClient sqs;

    @BeforeAll
    static void startInfrastructure() {
        DYNAMO.start();
        LOCALSTACK.start();
        sqs = SqsAsyncClient.builder().endpointOverride(LOCALSTACK.getEndpoint()).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        queues = SqsTestQueues.create(sqs, "orders-" + UUID.randomUUID());
    }

    @AfterAll
    static void stopInfrastructure() {
        sqs.close();
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
        registry.add("ticketflow.sqs.consumer.enabled", () -> "false");
        registry.add("ticketflow.expiration.enabled", () -> "false");
    }

    @Autowired
    private WebTestClient client;

    @Autowired
    private DynamoDbAsyncClient dynamo;

    @LocalServerPort
    private int port;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger root;

    @BeforeEach
    void awaitTablesAndCaptureLogs() {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(dynamo.listTables().join().tableNames())
                        .contains(DynamoDbTables.EVENTS, DynamoDbTables.INVENTORY, DynamoDbTables.ORDERS));
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(logs);
    }

    private String createEvent(int capacity) {
        String location = client.post().uri("/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"Concert","startsAt":"%s","venue":"Arena","capacity":%d}"""
                        .formatted(Instant.now().plus(Duration.ofDays(30)), capacity))
                .exchange().expectStatus().isCreated()
                .expectHeader().exists(CorrelationId.HEADER)
                .expectBody().returnResult().getResponseHeaders().getFirst("Location");
        return location.substring("/events/".length());
    }

    @Test
    void unknownRoute_returnsProblemJson404WithCorrelationIdAndNoDefaultErrorAttributes() {
        String body = new String(client.get().uri("/definitely/not/a/route").header(CorrelationId.HEADER, "route-1")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(CorrelationId.HEADER, "route-1")
                .expectBody()
                .jsonPath("$.type").isEqualTo(TYPE + "not-found")
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.correlationId").isEqualTo("route-1")
                .jsonPath("$.instance").isEqualTo("urn:ticketflow:request:route-1")
                .jsonPath("$.path").doesNotExist()
                .jsonPath("$.trace").doesNotExist()
                .jsonPath("$.requestId").doesNotExist()
                .returnResult().getResponseBodyContent(), StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("Whitelabel").doesNotContain("/definitely/not/a/route");
    }

    @Test
    void wrongMethod_returns405WithAllowHeader() {
        client.delete().uri("/events").exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists(HttpHeaders.ALLOW)
                .expectHeader().exists(CorrelationId.HEADER)
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "method-not-allowed")
                .jsonPath("$.correlationId").isNotEmpty();
    }

    @Test
    void unsupportedContentType_returns415() {
        client.post().uri("/events").contentType(MediaType.TEXT_PLAIN).bodyValue("name=x").exchange()
                .expectStatus().isEqualTo(415)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "unsupported-media-type")
                .jsonPath("$.correlationId").isNotEmpty();
    }

    @Test
    void malformedJson_returns400Problem() {
        client.post().uri("/events").contentType(MediaType.APPLICATION_JSON).bodyValue("{not json").exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "malformed-request")
                .jsonPath("$.correlationId").isNotEmpty();
    }

    @Test
    void oversizedBody_isRejectedWithProblemJsonAndNoInternals() {
        String huge = "{\"name\":\"" + "x".repeat(600_000) + "\"}";
        byte[] body = client.post().uri("/events").contentType(MediaType.APPLICATION_JSON).bodyValue(huge).exchange()
                .expectStatus().isEqualTo(413)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "payload-too-large")
                .returnResult().getResponseBodyContent();
        assertThat(new String(body, StandardCharsets.UTF_8)).doesNotContain("DataBuffer").doesNotContain("Exception");
    }

    @Test
    void earlierErrorPaths_keepTheirStatusAndGainCorrelationId() {
        client.get().uri("/events/nope").header(CorrelationId.HEADER, "reg-1").exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals(CorrelationId.HEADER, "reg-1")
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "event-not-found")
                .jsonPath("$.correlationId").isEqualTo("reg-1");
        client.get().uri("/orders/{id}", UUID.randomUUID().toString()).exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "order-not-found")
                .jsonPath("$.correlationId").isNotEmpty();
        client.post().uri("/events").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"\"}").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "validation-error")
                .jsonPath("$.violations").isArray()
                .jsonPath("$.correlationId").isNotEmpty();

        String eventId = createEvent(2);
        String key = "key-" + UUID.randomUUID();
        client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key)
                .bodyValue("{\"eventId\":\"%s\",\"quantity\":1}".formatted(eventId)).exchange()
                .expectStatus().isAccepted();
        client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key)
                .bodyValue("{\"eventId\":\"%s\",\"quantity\":2}".formatted(eventId)).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "idempotency-key-reused")
                .jsonPath("$.correlationId").isNotEmpty();
        client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-" + UUID.randomUUID())
                .bodyValue("{\"eventId\":\"%s\",\"quantity\":3}".formatted(eventId)).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "insufficient-inventory");
        client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"eventId\":\"%s\",\"quantity\":1}".formatted(eventId)).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "invalid-idempotency-key");
    }

    @Test
    void correlationId_unsafeValue_isReplacedByGeneratedUuid() {
        client.get().uri("/events").header(CorrelationId.HEADER, "bad value with spaces").exchange()
                .expectStatus().isOk()
                .expectHeader().value(CorrelationId.HEADER, value -> assertThat(UUID.fromString(value)).isNotNull());
        client.get().uri("/events").header(CorrelationId.HEADER, "x".repeat(65)).exchange()
                .expectHeader().value(CorrelationId.HEADER, value -> assertThat(value).hasSize(36));
    }

    @Test
    void purchase_withClientCorrelationId_arrivesAsSqsMessageAttribute() {
        String eventId = createEvent(5);
        String correlationId = "e2e-" + UUID.randomUUID();

        PurchaseAcceptedResponse accepted = client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-" + UUID.randomUUID())
                .header(CorrelationId.HEADER, correlationId)
                .bodyValue("{\"eventId\":\"%s\",\"quantity\":2}".formatted(eventId)).exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals(CorrelationId.HEADER, correlationId)
                .expectBody(PurchaseAcceptedResponse.class).returnResult().getResponseBody();

        Message message = await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).until(() ->
                sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queues.url()).messageAttributeNames("All")
                                .maxNumberOfMessages(10).waitTimeSeconds(1).visibilityTimeout(1).build()).join()
                        .messages().stream()
                        .filter(m -> accepted.orderId().equals(m.messageAttributes().get("orderId").stringValue()))
                        .findFirst().orElse(null), java.util.Objects::nonNull);
        assertThat(message.messageAttributes().get("correlationId").stringValue()).isEqualTo(correlationId);
    }

    @Test
    void correlationId_headerInjectionAttempt_isNeverEchoedNorLogged() throws Exception {
        // Raw socket: an HTTP client library would refuse to send this header value.
        String request = "GET /events HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                + "X-Correlation-Id: evilvalue\nSet-Cookie: injected=1\r\n\r\n";
        String response;
        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(out);
            response = out.toString(StandardCharsets.ISO_8859_1);
        }
        // Whatever the server decides (reject or ignore), the injected header must never come back.
        assertThat(response).doesNotContainIgnoringCase("Set-Cookie: injected");
        assertThat(response).doesNotContain("X-Correlation-Id: evilvalue");
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains("evilvalue")
                || e.getMDCPropertyMap().containsValue("evilvalue"));
    }
}
