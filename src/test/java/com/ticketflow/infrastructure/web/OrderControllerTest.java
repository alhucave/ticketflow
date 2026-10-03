package com.ticketflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.exception.IdempotencyKeyReusedException;
import com.ticketflow.domain.exception.IdempotentOrderNotActiveException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.exception.OrderEnqueueFailedException;
import com.ticketflow.domain.exception.OrderNotFoundException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.infrastructure.web.error.ApiExceptionHandler;
import com.ticketflow.usecase.GetOrderStatusUseCase;
import com.ticketflow.usecase.OrderStatusView;
import com.ticketflow.usecase.RequestPurchaseCommand;
import com.ticketflow.usecase.RequestPurchaseResult;
import com.ticketflow.usecase.RequestPurchaseUseCase;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import reactor.core.publisher.Mono;

/** Contract tests of the orders API with mocked use cases (real controller, advice and validation). */
class OrderControllerTest {

    private static final OrderId ORDER = new OrderId("ord-1");
    private static final Instant EXPIRES = Instant.parse("2030-01-01T10:10:00Z");
    private static final Instant CREATED = Instant.parse("2030-01-01T10:00:00Z");
    private static final String BODY = "{\"eventId\":\"evt-1\",\"quantity\":2}";

    private final RequestPurchaseUseCase purchase = mock(RequestPurchaseUseCase.class);
    private final GetOrderStatusUseCase status = mock(GetOrderStatusUseCase.class);
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        client = WebTestClient.bindToController(new OrderController(purchase, status))
                .controllerAdvice(new ApiExceptionHandler())
                .validator(validator)
                .build();
    }

    private WebTestClient.ResponseSpec post(String key, String body) {
        var spec = client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            spec.header("Idempotency-Key", key);
        }
        return spec.bodyValue(body).exchange();
    }

    private static RequestPurchaseResult accepted() {
        return new RequestPurchaseResult(ORDER, TicketStatus.RESERVED, EXPIRES, false);
    }

    @Test
    void purchase_validRequest_returns202WithLocationAndBody() {
        when(purchase.execute(any())).thenReturn(Mono.just(accepted()));

        post("key-1", BODY).expectStatus().isAccepted()
                .expectHeader().valueEquals("Location", "/orders/ord-1")
                .expectBody()
                .jsonPath("$.orderId").isEqualTo("ord-1")
                .jsonPath("$.status").isEqualTo("RESERVED")
                .jsonPath("$.reservationExpiresAt").isEqualTo("2030-01-01T10:10:00Z");

        ArgumentCaptor<RequestPurchaseCommand> captor = ArgumentCaptor.forClass(RequestPurchaseCommand.class);
        verify(purchase).execute(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new RequestPurchaseCommand(
                new EventId("evt-1"), new Quantity(2), new IdempotencyKey("key-1")));
    }

    @Test
    void purchase_replayOfSoldOrder_returns202WithoutExpiry() {
        when(purchase.execute(any()))
                .thenReturn(Mono.just(new RequestPurchaseResult(ORDER, TicketStatus.SOLD, EXPIRES, true)));

        post("key-1", BODY).expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.orderId").isEqualTo("ord-1")
                .jsonPath("$.status").isEqualTo("SOLD")
                .jsonPath("$.reservationExpiresAt").doesNotExist();
    }

    @Test
    void purchase_missingKey_returns400WithoutCallingUseCase() {
        post(null, BODY).expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo("urn:ticketflow:problem:invalid-idempotency-key");
        verifyNoInteractions(purchase);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "has space", "semi;colon", "slash/key", "ünicode", "a,b"})
    void purchase_blankOrBadCharsetKey_returns400(String key) {
        post(key, BODY).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.status").isEqualTo(400);
        verifyNoInteractions(purchase);
    }

    @Test
    void purchase_keyOver128Chars_returns400() {
        post("a".repeat(129), BODY).expectStatus().isBadRequest();
        verifyNoInteractions(purchase);
    }

    @Test
    void purchase_keyOfExactly128AllowedChars_isAccepted() {
        when(purchase.execute(any())).thenReturn(Mono.just(accepted()));
        post("aZ09._:-".repeat(16), BODY).expectStatus().isAccepted();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"eventId\":\"evt-1\",\"quantity\":0}",
            "{\"eventId\":\"evt-1\",\"quantity\":-3}",
            "{\"eventId\":\"evt-1\",\"quantity\":11}",
            "{\"eventId\":\"evt-1\"}",
            "{\"eventId\":\"\",\"quantity\":1}",
            "{\"eventId\":\"  \",\"quantity\":1}",
            "{\"quantity\":1}"})
    void purchase_invalidBody_returns400WithViolations(String body) {
        post("key-1", body).expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:ticketflow:problem:validation-error")
                .jsonPath("$.violations.length()").isEqualTo(1);
        verifyNoInteractions(purchase);
    }

    @Test
    void purchase_quantityAtLimit10_isAccepted() {
        when(purchase.execute(any())).thenReturn(Mono.just(accepted()));
        post("key-1", "{\"eventId\":\"evt-1\",\"quantity\":10}").expectStatus().isAccepted();
    }

    @Test
    void purchase_malformedJson_returns400() {
        post("key-1", "{oops").expectStatus().isBadRequest();
        verifyNoInteractions(purchase);
    }

    @Test
    void purchase_insufficientInventory_returns409WithoutInternals() {
        when(purchase.execute(any())).thenReturn(Mono.error(
                new InsufficientInventoryException(new EventId("evt-1"), new Quantity(2))));

        post("key-1", BODY).expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:ticketflow:problem:insufficient-inventory")
                .jsonPath("$.detail").isEqualTo("Not enough tickets are available for the requested quantity");
    }

    @Test
    void purchase_keyReusedWithDifferentPayload_returns409() {
        when(purchase.execute(any())).thenReturn(Mono.error(
                new IdempotencyKeyReusedException(new IdempotencyKey("key-1"), ORDER)));

        post("key-1", BODY).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.type").isEqualTo("urn:ticketflow:problem:idempotency-key-reused");
    }

    @Test
    void purchase_releasedOrderOnReplay_returns409() {
        when(purchase.execute(any())).thenReturn(Mono.error(
                new IdempotentOrderNotActiveException(new IdempotencyKey("key-1"), ORDER)));

        post("key-1", BODY).expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:ticketflow:problem:idempotent-order-not-active")
                .jsonPath("$.detail").value(detail -> assertThat((String) detail).contains("new Idempotency-Key"));
    }

    @Test
    void purchase_enqueueFailed_returns503WithoutLeakingCause() {
        when(purchase.execute(any())).thenReturn(Mono.error(
                new OrderEnqueueFailedException(ORDER, true, new RuntimeException("sqs-internal-host:4566 down"))));

        post("key-1", BODY).expectStatus().isEqualTo(503)
                .expectBody(String.class).value(body -> {
                    assertThat(body).contains("order-enqueue-failed").contains("new Idempotency-Key");
                    assertThat(body).doesNotContain("sqs-internal").doesNotContain("ord-1").doesNotContain("Exception");
                });
    }

    @Test
    void get_existingOrder_returns200WithExpiryWhileReserved() {
        when(status.execute(ORDER)).thenReturn(Mono.just(view(TicketStatus.RESERVED)));

        client.get().uri("/orders/ord-1").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.orderId").isEqualTo("ord-1")
                .jsonPath("$.eventId").isEqualTo("evt-1")
                .jsonPath("$.quantity").isEqualTo(2)
                .jsonPath("$.status").isEqualTo("RESERVED")
                .jsonPath("$.reservationExpiresAt").isEqualTo("2030-01-01T10:10:00Z")
                .jsonPath("$.createdAt").isEqualTo("2030-01-01T10:00:00Z");
    }

    @Test
    void get_pendingConfirmation_keepsExpiry() {
        when(status.execute(ORDER)).thenReturn(Mono.just(view(TicketStatus.PENDING_CONFIRMATION)));

        client.get().uri("/orders/ord-1").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("PENDING_CONFIRMATION")
                .jsonPath("$.reservationExpiresAt").isEqualTo("2030-01-01T10:10:00Z");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOLD", "COMPLIMENTARY", "AVAILABLE"})
    void get_stateWithoutLiveReservation_omitsExpiry(String state) {
        when(status.execute(ORDER)).thenReturn(Mono.just(view(TicketStatus.valueOf(state))));

        client.get().uri("/orders/ord-1").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo(state)
                .jsonPath("$.reservationExpiresAt").doesNotExist();
    }

    @Test
    void get_unknownOrder_returns404Problem() {
        when(status.execute(any())).thenReturn(Mono.error(new OrderNotFoundException(new OrderId("nope"))));

        client.get().uri("/orders/nope").exchange().expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo("urn:ticketflow:problem:order-not-found");
    }

    private static OrderStatusView view(TicketStatus state) {
        return new OrderStatusView(ORDER, new EventId("evt-1"), new Quantity(2), state, EXPIRES, CREATED);
    }
}
