package com.ticketflow.infrastructure.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ticketflow.infrastructure.config.CorrelationConfig;
import com.ticketflow.infrastructure.config.RateLimitConfig;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Error mappings, no-leak guarantees and correlation id behaviour over HTTP, with Spring Boot's real
 * error-handling auto-configuration present (so the order against its default handler is exercised).
 */
@WebFluxTest(controllers = ErrorProbeController.class)
@Import({ApiExceptionHandler.class, ProblemWebExceptionHandler.class, CorrelationIdWebFilter.class,
        CorrelationConfig.class, RateLimitConfig.class})
class ErrorHandlingWebTest {

    private static final String TYPE = "urn:ticketflow:problem:";

    @Autowired
    private WebTestClient client;

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
    }

    static Stream<Arguments> mappings() {
        return Stream.of(
                Arguments.of("invalid-event", 400, "invalid-event"),
                Arguments.of("invalid-key", 400, "invalid-idempotency-key"),
                Arguments.of("event-not-found", 404, "event-not-found"),
                Arguments.of("order-not-found", 404, "order-not-found"),
                Arguments.of("event-exists", 409, "event-already-exists"),
                Arguments.of("insufficient", 409, "insufficient-inventory"),
                Arguments.of("key-reused", 409, "idempotency-key-reused"),
                Arguments.of("not-active", 409, "idempotent-order-not-active"),
                Arguments.of("concurrent", 409, "concurrent-modification"),
                Arguments.of("transition", 409, "invalid-state-transition"),
                Arguments.of("status-conflict", 409, "order-status-conflict"),
                Arguments.of("order-exists", 409, "order-already-exists"),
                Arguments.of("expired", 410, "reservation-expired"),
                Arguments.of("rate-limit", 429, "rate-limit-exceeded"),
                Arguments.of("enqueue", 503, "order-enqueue-failed"),
                Arguments.of("bad-path-id", 404, "not-found"),
                Arguments.of("bad-field", 400, "validation-error"),
                Arguments.of("throttled", 503, "service-unavailable"),
                Arguments.of("aws-503", 503, "service-unavailable"),
                Arguments.of("aws-429", 503, "service-unavailable"),
                Arguments.of("tx-conflict", 503, "service-unavailable"),
                Arguments.of("sdk-client", 503, "service-unavailable"),
                Arguments.of("timeout", 503, "service-unavailable"),
                Arguments.of("retry-exhausted", 503, "service-unavailable"),
                Arguments.of("aws-400", 500, "internal-error"),
                Arguments.of("conditional-failed", 500, "internal-error"),
                Arguments.of("unexpected", 500, "internal-error"),
                Arguments.of("error", 500, "internal-error"),
                Arguments.of("status-503", 503, "internal-error"),
                Arguments.of("status-413", 413, "payload-too-large"),
                Arguments.of("status-400", 400, "bad-request"),
                Arguments.of("status-418", 418, "client-error"));
    }

    @ParameterizedTest
    @MethodSource("mappings")
    void throwing_eachException_mapsToDocumentedStatusAndOneProblemShape(String kind, int status, String type) {
        String correlationId = "corr-" + kind;
        EntityExchangeResult<byte[]> result = client.get().uri("/probe/throw/{kind}", kind)
                .header(CorrelationId.HEADER, correlationId)
                .exchange()
                .expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(CorrelationId.HEADER, correlationId)
                .expectBody()
                .jsonPath("$.type").isEqualTo(TYPE + type)
                .jsonPath("$.title").isNotEmpty()
                .jsonPath("$.status").isEqualTo(status)
                .jsonPath("$.detail").isNotEmpty()
                .jsonPath("$.instance").isEqualTo("urn:ticketflow:request:" + correlationId)
                .jsonPath("$.correlationId").isEqualTo(correlationId)
                .returnResult();
        assertNoLeak(new String(result.getResponseBodyContent()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unexpected", "unexpected-sync-cause", "error", "status-503", "status-418", "enqueue",
            "throttled", "aws-503", "tx-conflict", "sdk-client", "timeout", "retry-exhausted", "aws-400"})
    void throwing_internalDetails_neverAppearInBodyOrHeaders(String kind) {
        EntityExchangeResult<byte[]> result = client.get().uri("/probe/throw/{kind}", kind).exchange()
                .expectBody().returnResult();
        assertNoLeak(new String(result.getResponseBodyContent()));
        assertThat(result.getResponseHeaders().toString()).doesNotContain(ErrorProbeController.SECRET);
    }

    @ParameterizedTest
    @ValueSource(strings = {"throttled", "aws-503", "aws-429", "tx-conflict", "sdk-client", "timeout",
            "retry-exhausted"})
    void throwing_transientInfrastructureFailure_is503WithRetryAfterAndNoInternals(String kind) {
        client.get().uri("/probe/throw/{kind}", kind).exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "5")
                .expectBody()
                .jsonPath("$.detail").isEqualTo("A required service is temporarily unavailable; retry shortly");
        // Logged by class name only: neither the message (may hold endpoints/internals) nor a stack trace.
        ILoggingEvent line = logs.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("Dependency unavailable")).findFirst().orElseThrow();
        assertThat(line.getFormattedMessage()).doesNotContain(ErrorProbeController.SECRET);
        assertThat(line.getThrowableProxy()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"concurrent", "status-conflict", "insufficient", "key-reused", "order-exists"})
    void throwing_businessConflict_isNever503(String kind) {
        client.get().uri("/probe/throw/{kind}", kind).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.type").value(type ->
                        assertThat((String) type).doesNotContain("service-unavailable"));
    }

    @Test
    void throwing_invalidFieldAndPathId_useFixedTextsWithoutEchoingInput() {
        client.get().uri("/probe/throw/bad-field").exchange().expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.violations.length()").isEqualTo(1)
                .jsonPath("$.violations[0].field").isEqualTo("quantity")
                .jsonPath("$.violations[0].message").isEqualTo("must be less than or equal to 3");
        client.get().uri("/probe/throw/bad-path-id").exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.detail").isEqualTo("The requested resource was not found");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/probe/ok", "/probe/throw/unexpected", "/probe/throw/insufficient",
            "/probe/throw/throttled", "/probe/throw/rate-limit", "/definitely/not/a/route"})
    void everyResponse_successOrError_carriesTheSecurityHeaders(String path) {
        HttpHeaders headers = client.get().uri(path).exchange().returnResult(String.class).getResponseHeaders();
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
        assertThat(headers.getFirst("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
        assertThat(headers.get("X-Content-Type-Options")).hasSize(1); // set, never duplicated
        assertThat(headers.getFirst(CorrelationId.HEADER)).isNotBlank();
    }

    @Test
    void securityHeaders_alsoOnRoutingAndBodyErrors() {
        client.delete().uri("/probe/ok").exchange().expectStatus().isEqualTo(405)
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
        client.post().uri("/probe/body").contentType(MediaType.APPLICATION_JSON).bodyValue("{oops").exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer");
        client.post().uri("/probe/body").contentType(MediaType.TEXT_PLAIN).bodyValue("x").exchange()
                .expectStatus().isEqualTo(415)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @Test
    void throwing_unexpectedException_returnsFixedGenericDetailAndLogsRealErrorWithCorrelationId() {
        String correlationId = "log-me-1";
        client.get().uri("/probe/throw/unexpected").header(CorrelationId.HEADER, correlationId).exchange()
                .expectStatus().isEqualTo(500)
                .expectBody().jsonPath("$.detail").isEqualTo(ApiExceptionHandler.INTERNAL_ERROR_DETAIL);

        ILoggingEvent event = logs.list.stream().filter(e -> e.getLevel() == Level.ERROR)
                .filter(e -> e.getThrowableProxy() != null
                        && ErrorProbeController.SECRET.equals(e.getThrowableProxy().getMessage()))
                .findFirst().orElseThrow();
        assertThat(event.getMDCPropertyMap()).containsEntry(CorrelationId.KEY, correlationId);
        assertThat(event.getFormattedMessage()).contains(correlationId);
        assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
    }

    @Test
    void throwing_clientError_logsOneLineWithCorrelationIdAndNoExceptionMessage() {
        client.get().uri("/probe/throw/status-418").header(CorrelationId.HEADER, "client-err-1").exchange()
                .expectStatus().isEqualTo(418);

        List<ILoggingEvent> lines = logs.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("Request rejected")).toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().getMDCPropertyMap()).containsEntry(CorrelationId.KEY, "client-err-1");
        assertThat(lines.getFirst().getFormattedMessage()).doesNotContain(ErrorProbeController.SECRET)
                .contains("status=418");
    }

    @Test
    void throwing_rateLimitWithKnownWait_sendsRetryAfterRoundedUpToSeconds() {
        client.get().uri("/probe/throw/rate-limit").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "2");
    }

    @Test
    void throwing_rateLimitWithUnknownWait_omitsRetryAfter() {
        client.get().uri("/probe/throw/rate-limit-unknown").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER);
    }

    @Test
    void throwing_concurrentModification_hintsRetryAfter() {
        client.get().uri("/probe/throw/concurrent").exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1");
    }

    @Test
    void request_unknownRoute_returnsProblem404WithoutDefaultErrorAttributes() {
        EntityExchangeResult<byte[]> result = client.get().uri("/no/such/route/secret-path-xyz").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists(CorrelationId.HEADER)
                .expectBody()
                .jsonPath("$.type").isEqualTo(TYPE + "not-found")
                .jsonPath("$.correlationId").isNotEmpty()
                .jsonPath("$.path").doesNotExist()
                .jsonPath("$.trace").doesNotExist()
                .jsonPath("$.requestId").doesNotExist()
                .jsonPath("$.error").doesNotExist()
                .returnResult();
        String body = new String(result.getResponseBodyContent());
        assertThat(body).doesNotContain("secret-path-xyz").doesNotContain("Whitelabel");
    }

    @Test
    void request_wrongMethod_returns405WithAllowHeaderPreserved() {
        client.post().uri("/probe/ok").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueMatches(HttpHeaders.ALLOW, ".*GET.*")
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "method-not-allowed");
    }

    @Test
    void request_unsupportedContentType_returns415() {
        client.post().uri("/probe/body").contentType(MediaType.TEXT_PLAIN).bodyValue("hello").exchange()
                .expectStatus().isEqualTo(415)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "unsupported-media-type");
    }

    @Test
    void request_notAcceptable_returns406() {
        client.get().uri("/probe/ok").accept(MediaType.APPLICATION_XML).exchange()
                .expectStatus().isEqualTo(406)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "not-acceptable");
    }

    @Test
    void request_malformedJson_returns400Problem() {
        client.post().uri("/probe/body").contentType(MediaType.APPLICATION_JSON).bodyValue("{not json").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.type").isEqualTo(TYPE + "malformed-request")
                .jsonPath("$.correlationId").isNotEmpty();
    }

    @Test
    void request_withSafeCorrelationId_isEchoedOnSuccess() {
        client.get().uri("/probe/ok").header(CorrelationId.HEADER, "Abc-123_x.y").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(CorrelationId.HEADER, "Abc-123_x.y");
    }

    @Test
    void request_withoutCorrelationId_getsGeneratedUuid() {
        String id = client.get().uri("/probe/ok").exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst(CorrelationId.HEADER);
        assertThat(UUID.fromString(id)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "has space", "semi;colon", "<script>", "café", "a/b", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void request_withUnsafeCorrelationId_isReplacedAndNeverEchoed(String unsafe) {
        client.get().uri("/probe/throw/unexpected").header(CorrelationId.HEADER, unsafe).exchange()
                .expectStatus().isEqualTo(500)
                .expectHeader().value(CorrelationId.HEADER, value -> {
                    assertThat(value).isNotEqualTo(unsafe);
                    assertThat(UUID.fromString(value)).isNotNull();
                })
                .expectBody().jsonPath("$.correlationId").value(value -> assertThat(value).isNotEqualTo(unsafe));
    }

    @Test
    void request_withMaxLengthCorrelationId_isAccepted() {
        String id = "a".repeat(64);
        client.get().uri("/probe/ok").header(CorrelationId.HEADER, id).exchange()
                .expectHeader().valueEquals(CorrelationId.HEADER, id);
    }

    @Test
    void resolve_withControlCharacters_returnsGeneratedId() {
        assertThat(CorrelationId.resolve("abc\r\nSet-Cookie: x=1")).doesNotContain("\r", "\n", "Set-Cookie");
        assertThat(CorrelationId.resolve("abc\n")).isNotEqualTo("abc\n");
        assertThat(CorrelationId.resolve(null)).isNotBlank();
    }

    @Test
    void request_handled_logLinesCarryCorrelationIdInMdc() {
        client.get().uri("/probe/log/20").header(CorrelationId.HEADER, "mdc-1").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(CorrelationId.HEADER, "mdc-1");
        List<ILoggingEvent> lines = logs.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("probe-log-line")).toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().getMDCPropertyMap()).containsEntry(CorrelationId.KEY, "mdc-1");
    }

    private static void assertNoLeak(String body) {
        assertThat(body)
                .doesNotContain(ErrorProbeController.SECRET)
                .doesNotContain("Exception")
                .doesNotContain("StackOverflowError")
                .doesNotContain("IllegalStateException")
                .doesNotContain("com.ticketflow")
                .doesNotContain("\tat ")
                .doesNotContain("at com.")
                .doesNotContain("\"trace\"")
                .doesNotContain("\"path\"");
    }
}
