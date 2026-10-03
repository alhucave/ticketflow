package com.ticketflow.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.exception.IdempotencyKeyReusedException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.exception.OrderAlreadyExistsException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderAuditEntry;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderPlacementRepository;
import com.ticketflow.domain.port.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class IssueComplimentaryUseCaseTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final EventId EVENT = new EventId("e-1");
    private static final IdempotencyKey KEY = new IdempotencyKey("key-1");
    private static final OrderId ORDER_ID = OrderId.complimentaryFromIdempotencyKey(KEY);
    private static final IssueComplimentaryCommand COMMAND =
            new IssueComplimentaryCommand(EVENT, new Quantity(3), KEY, "VIP guests");

    private OrderPlacementRepository placement;
    private OrderRepository orders;
    private IssueComplimentaryUseCase useCase;

    @BeforeEach
    void setUp() {
        placement = mock(OrderPlacementRepository.class);
        orders = mock(OrderRepository.class);
        useCase = new IssueComplimentaryUseCase(placement, orders, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Order existing(EventId event, int quantity, TicketStatus status) {
        return status == TicketStatus.COMPLIMENTARY
                ? Order.complimentary(ORDER_ID, event, new Quantity(quantity), KEY, NOW.minusSeconds(5))
                : new Order(ORDER_ID, event, new Quantity(quantity), status, KEY, NOW, NOW.minusSeconds(5));
    }

    private static OrderAuditEntry audit(String reason) {
        return new OrderAuditEntry(ORDER_ID, NOW, TicketStatus.AVAILABLE, TicketStatus.COMPLIMENTARY, "a", reason);
    }

    @Test
    void execute_validRequest_issuesComplimentaryOrderWithActorAndReason() {
        when(placement.issueComplimentary(any(), any(), any())).thenAnswer(i -> Mono.just(i.getArgument(0)));

        StepVerifier.create(useCase.execute(COMMAND))
                .assertNext(result -> {
                    assertThat(result.orderId()).isEqualTo(ORDER_ID);
                    assertThat(result.eventId()).isEqualTo(EVENT);
                    assertThat(result.quantity()).isEqualTo(new Quantity(3));
                    assertThat(result.status()).isEqualTo(TicketStatus.COMPLIMENTARY);
                    assertThat(result.replayed()).isFalse();
                })
                .verifyComplete();

        var placed = ArgumentCaptor.forClass(Order.class);
        verify(placement).issueComplimentary(placed.capture(), eq(IssueComplimentaryUseCase.ACTOR), eq("VIP guests"));
        assertThat(placed.getValue().status()).isEqualTo(TicketStatus.COMPLIMENTARY);
        assertThat(placed.getValue().createdAt()).isEqualTo(NOW);
        assertThat(placed.getValue().reservationExpiresAt()).isEqualTo(NOW);
        verifyNoInteractions(orders);
    }

    @Test
    void execute_orderIdIsNamespacedAwayFromPurchases() {
        assertThat(ORDER_ID).isNotEqualTo(OrderId.fromIdempotencyKey(KEY));
        assertThat(OrderId.complimentaryFromIdempotencyKey(new IdempotencyKey("key-1"))).isEqualTo(ORDER_ID);
    }

    @Test
    void execute_noReason_passesNullReason() {
        when(placement.issueComplimentary(any(), any(), any())).thenAnswer(i -> Mono.just(i.getArgument(0)));

        StepVerifier.create(useCase.execute(new IssueComplimentaryCommand(EVENT, new Quantity(1), KEY, "  ")))
                .expectNextCount(1).verifyComplete();

        verify(placement).issueComplimentary(any(), any(), eq(null));
    }

    @Test
    void execute_insufficientInventory_propagates() {
        when(placement.issueComplimentary(any(), any(), any()))
                .thenReturn(Mono.error(new InsufficientInventoryException(EVENT, new Quantity(3))));

        StepVerifier.create(useCase.execute(COMMAND)).expectError(InsufficientInventoryException.class).verify();
        verifyNoInteractions(orders);
    }

    @Test
    void execute_unknownEvent_propagates() {
        when(placement.issueComplimentary(any(), any(), any())).thenReturn(Mono.error(new EventNotFoundException(EVENT)));

        StepVerifier.create(useCase.execute(COMMAND)).expectError(EventNotFoundException.class).verify();
    }

    @Test
    void execute_replaySamePayload_returnsExistingMarkedReplayed() {
        when(placement.issueComplimentary(any(), any(), any())).thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(existing(EVENT, 3, TicketStatus.COMPLIMENTARY)));
        when(orders.findAuditTrail(ORDER_ID)).thenReturn(Flux.just(audit("VIP guests")));

        StepVerifier.create(useCase.execute(COMMAND))
                .assertNext(result -> {
                    assertThat(result.orderId()).isEqualTo(ORDER_ID);
                    assertThat(result.replayed()).isTrue();
                    assertThat(result.status()).isEqualTo(TicketStatus.COMPLIMENTARY);
                })
                .verifyComplete();
    }

    @Test
    void execute_replayNoReasonOnBothSides_returnsExisting() {
        var noReason = new IssueComplimentaryCommand(EVENT, new Quantity(3), KEY, null);
        when(placement.issueComplimentary(any(), any(), any())).thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(existing(EVENT, 3, TicketStatus.COMPLIMENTARY)));
        when(orders.findAuditTrail(ORDER_ID)).thenReturn(Flux.just(audit(null)));

        StepVerifier.create(useCase.execute(noReason)).assertNext(r -> assertThat(r.replayed()).isTrue()).verifyComplete();
    }

    @Test
    void execute_replayStoredReasonButCommandHasNone_failsReused() {
        var noReason = new IssueComplimentaryCommand(EVENT, new Quantity(3), KEY, null);
        when(placement.issueComplimentary(any(), any(), any())).thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(existing(EVENT, 3, TicketStatus.COMPLIMENTARY)));
        when(orders.findAuditTrail(ORDER_ID)).thenReturn(Flux.just(audit("VIP guests")));

        StepVerifier.create(useCase.execute(noReason)).expectError(IdempotencyKeyReusedException.class).verify();
    }

    @Test
    void execute_replayCommandHasReasonButNoneStored_failsReused() {
        when(placement.issueComplimentary(any(), any(), any())).thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(existing(EVENT, 3, TicketStatus.COMPLIMENTARY)));
        when(orders.findAuditTrail(ORDER_ID)).thenReturn(Flux.empty());

        StepVerifier.create(useCase.execute(COMMAND)).expectError(IdempotencyKeyReusedException.class).verify();
    }

    @Test
    void execute_replayDifferentReason_failsReused() {
        when(placement.issueComplimentary(any(), any(), any())).thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(existing(EVENT, 3, TicketStatus.COMPLIMENTARY)));
        when(orders.findAuditTrail(ORDER_ID)).thenReturn(Flux.just(audit("someone else")));

        StepVerifier.create(useCase.execute(COMMAND)).expectError(IdempotencyKeyReusedException.class).verify();
    }

    @Test
    void execute_replayDifferentQuantityOrEvent_failsReused() {
        when(placement.issueComplimentary(any(), any(), any())).thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(existing(EVENT, 5, TicketStatus.COMPLIMENTARY)));
        StepVerifier.create(useCase.execute(COMMAND)).expectError(IdempotencyKeyReusedException.class).verify();

        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(existing(new EventId("other"), 3, TicketStatus.COMPLIMENTARY)));
        StepVerifier.create(useCase.execute(COMMAND)).expectError(IdempotencyKeyReusedException.class).verify();
    }

    @Test
    void execute_replayFoundOrderNotComplimentary_failsReused() {
        when(placement.issueComplimentary(any(), any(), any())).thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(existing(EVENT, 3, TicketStatus.RESERVED)));

        StepVerifier.create(useCase.execute(COMMAND)).expectError(IdempotencyKeyReusedException.class).verify();
    }

    @Test
    void execute_replayButOrderVanished_failsWithIllegalState() {
        when(placement.issueComplimentary(any(), any(), any())).thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
        when(orders.findById(ORDER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(COMMAND)).expectError(IllegalStateException.class).verify();
    }

    @Test
    void execute_unexpectedError_propagates() {
        when(placement.issueComplimentary(any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("boom")));

        StepVerifier.create(useCase.execute(COMMAND)).expectError(IllegalStateException.class).verify();
    }

    @Test
    void command_invalidValues_areRejected() {
        var q = new Quantity(1);
        assertThatThrownBy(() -> new IssueComplimentaryCommand(null, q, KEY, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IssueComplimentaryCommand(EVENT, null, KEY, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IssueComplimentaryCommand(EVENT, q, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IssueComplimentaryCommand(EVENT, new Quantity(1001), KEY, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IssueComplimentaryCommand(EVENT, q, KEY, "x".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new IssueComplimentaryCommand(EVENT, new Quantity(1000), KEY, " padded ").reason()).isEqualTo("padded");
    }
}
