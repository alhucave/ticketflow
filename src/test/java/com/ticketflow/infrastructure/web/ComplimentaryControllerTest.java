package com.ticketflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.exception.IdempotencyKeyReusedException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.infrastructure.config.CorrelationConfig;
import com.ticketflow.infrastructure.web.error.ApiExceptionHandler;
import com.ticketflow.infrastructure.web.error.CorrelationIdWebFilter;
import com.ticketflow.infrastructure.web.error.ProblemWebExceptionHandler;
import com.ticketflow.usecase.IssueComplimentaryCommand;
import com.ticketflow.usecase.IssueComplimentaryResult;
import com.ticketflow.usecase.IssueComplimentaryUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** HTTP contract of the complimentary endpoint, with the real admin filter and error handlers. */
@WebFluxTest(controllers = ComplimentaryController.class)
@Import({ApiExceptionHandler.class, ProblemWebExceptionHandler.class, CorrelationIdWebFilter.class,
        CorrelationConfig.class, AdminKeyWebFilter.class})
@TestPropertySource(properties = "ticketflow.admin.api-key=test-admin-key")
class ComplimentaryControllerTest {

    private static final String BODY = "{\"quantity\":4,\"reason\":\"VIP guests\"}";
    private static final String PROBLEM = "urn:ticketflow:problem:";
    private static final OrderId ORDER = new OrderId("ord-c");

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private IssueComplimentaryUseCase useCase;

    private WebTestClient.ResponseSpec post(String adminKey, String idempotencyKey, String body) {
        return post("evt-1", adminKey, idempotencyKey, body);
    }

    private WebTestClient.ResponseSpec post(String eventId, String adminKey, String idempotencyKey, String body) {
        var spec = client.post().uri("/events/{id}/complimentary", eventId).contentType(MediaType.APPLICATION_JSON);
        if (adminKey != null) {
            spec.header("X-Admin-Key", adminKey);
        }
        if (idempotencyKey != null) {
            spec.header("Idempotency-Key", idempotencyKey);
        }
        return spec.bodyValue(body).exchange();
    }

    private static IssueComplimentaryResult result(boolean replayed) {
        return new IssueComplimentaryResult(ORDER, new EventId("evt-1"), new Quantity(4),
                TicketStatus.COMPLIMENTARY, replayed);
    }

    @Test
    void issue_validRequest_returns201WithLocationAndBody() {
        when(useCase.execute(any())).thenReturn(Mono.just(result(false)));

        post("test-admin-key", "key-1", BODY).expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "/orders/ord-c")
                .expectBody()
                .jsonPath("$.orderId").isEqualTo("ord-c")
                .jsonPath("$.eventId").isEqualTo("evt-1")
                .jsonPath("$.quantity").isEqualTo(4)
                .jsonPath("$.status").isEqualTo("COMPLIMENTARY")
                .jsonPath("$.reason").doesNotExist();

        var captor = ArgumentCaptor.forClass(IssueComplimentaryCommand.class);
        verify(useCase).execute(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo(new EventId("evt-1"));
        assertThat(captor.getValue().quantity()).isEqualTo(new Quantity(4));
        assertThat(captor.getValue().idempotencyKey()).isEqualTo(new IdempotencyKey("key-1"));
        assertThat(captor.getValue().reason()).isEqualTo("VIP guests");
    }

    @Test
    void issue_replay_returnsSameBodyAndStatus() {
        when(useCase.execute(any())).thenReturn(Mono.just(result(true)));

        post("test-admin-key", "key-1", "{\"quantity\":4}").expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "/orders/ord-c")
                .expectBody().jsonPath("$.orderId").isEqualTo("ord-c").jsonPath("$.status").isEqualTo("COMPLIMENTARY");
    }

    @Test
    void issue_missingAdminKey_is401ProblemAndNeverReachesUseCase() {
        post(null, "key-1", BODY).expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo(PROBLEM + "admin-unauthorized")
                .jsonPath("$.correlationId").exists();
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong", "test-admin-ke", "TEST-ADMIN-KEY", ""})
    void issue_wrongAdminKey_is401WithFixedMessage(String supplied) {
        var body = post(supplied, "key-1", BODY).expectStatus().isUnauthorized()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).doesNotContain("test-admin-key").contains("Valid admin credentials are required");
        if (!supplied.isEmpty()) {
            assertThat(body).doesNotContain(supplied);
        }
        verifyNoInteractions(useCase);
    }

    @Test
    void issue_adminCheckPrecedesValidation_invalidBodyWithoutKeyIs401Not400() {
        post(null, null, "{\"quantity\":0}").expectStatus().isUnauthorized();
        post("test-admin-key", "key-1", "{\"quantity\":0}").expectStatus().isBadRequest();
    }

    @Test
    void issue_otherMethodsOnAdminRoute_alsoRequireTheKey() {
        client.get().uri("/events/evt-1/complimentary").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void issue_missingOrInvalidIdempotencyKey_is400() {
        post("test-admin-key", null, BODY).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "invalid-idempotency-key");
        post("test-admin-key", "bad key!", BODY).expectStatus().isBadRequest();
        post("test-admin-key", "k".repeat(129), BODY).expectStatus().isBadRequest();
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"quantity\":0}", "{\"quantity\":1001}", "{\"quantity\":-1}",
            "{\"quantity\":1,\"reason\":\"bad\\u0007bell\"}", "not json"})
    void issue_invalidBody_is400(String body) {
        post("test-admin-key", "key-1", body).expectStatus().isBadRequest();
        verifyNoInteractions(useCase);
    }

    @Test
    void issue_reasonTooLong_is400WithViolation() {
        post("test-admin-key", "key-1", "{\"quantity\":1,\"reason\":\"" + "x".repeat(201) + "\"}")
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.violations[0].field").isEqualTo("reason");
    }

    @Test
    void issue_maxQuantity_isAccepted() {
        when(useCase.execute(any())).thenReturn(Mono.just(result(false)));
        post("test-admin-key", "key-1", "{\"quantity\":1000}").expectStatus().isCreated();
    }

    @Test
    void issue_unknownEvent_is404() {
        when(useCase.execute(any())).thenReturn(Mono.error(new EventNotFoundException(new EventId("evt-1"))));

        post("test-admin-key", "key-1", BODY).expectStatus().isNotFound()
                .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "event-not-found");
    }

    @Test
    void issue_insufficientInventory_is409() {
        when(useCase.execute(any())).thenReturn(
                Mono.error(new InsufficientInventoryException(new EventId("evt-1"), new Quantity(4))));

        post("test-admin-key", "key-1", BODY).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "insufficient-inventory");
    }

    @Test
    void issue_keyReusedWithDifferentPayload_is409() {
        when(useCase.execute(any())).thenReturn(
                Mono.error(new IdempotencyKeyReusedException(new IdempotencyKey("key-1"), ORDER)));

        post("test-admin-key", "key-1", BODY).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "idempotency-key-reused");
    }

    @Test
    void issue_unrelatedRoutes_doNotRequireTheKey() {
        client.get().uri("/events/evt-1/availability-not-here").exchange().expectStatus().isNotFound();
    }
}
