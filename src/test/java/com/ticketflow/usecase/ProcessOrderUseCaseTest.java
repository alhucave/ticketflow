package com.ticketflow.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.exception.OrderNotFoundException;
import com.ticketflow.domain.exception.OrderStatusConflictException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderAuditEntry;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderFulfillmentRepository;
import com.ticketflow.domain.port.OrderPlacementRepository;
import com.ticketflow.domain.port.OrderRepository;
import com.ticketflow.usecase.ProcessOrderResult.AlreadyProcessed;
import com.ticketflow.usecase.ProcessOrderResult.OrderMissing;
import com.ticketflow.usecase.ProcessOrderResult.ReleasedAsExpired;
import com.ticketflow.usecase.ProcessOrderResult.Sold;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ProcessOrderUseCaseTest {

    private static final OrderId ID = new OrderId("o-1");
    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderFulfillmentRepository fulfillment = mock(OrderFulfillmentRepository.class);
    private final OrderPlacementRepository placement = mock(OrderPlacementRepository.class);
    private final ProcessOrderUseCase useCase =
            new ProcessOrderUseCase(orders, fulfillment, placement, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Order order(TicketStatus status, Instant expiresAt) {
        return new Order(ID, new EventId("e-1"), new Quantity(3), status, new IdempotencyKey("k"),
                expiresAt, NOW.minusSeconds(60));
    }

    private static Order valid(TicketStatus status) {
        return order(status, NOW.plusSeconds(300));
    }

    private static OrderAuditEntry audit(TicketStatus from, TicketStatus to) {
        return new OrderAuditEntry(ID, NOW, from, to, "order-processor");
    }

    private static OrderStatusConflictException conflict(TicketStatus expected, TicketStatus actual) {
        return new OrderStatusConflictException(ID, expected, actual);
    }

    private void stepsSucceed() {
        when(fulfillment.markPendingConfirmation(any(), any(), any()))
                .thenReturn(Mono.just(audit(TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION)));
        when(fulfillment.confirmSale(any(), any(), any()))
                .thenReturn(Mono.just(audit(TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD)));
    }

    @Test
    void execute_missingOrder_returnsOrderMissingWithoutWrites() {
        when(orders.findById(ID)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(ID)).expectNext(new OrderMissing(ID)).verifyComplete();
        verifyNoInteractions(fulfillment, placement);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"SOLD", "COMPLIMENTARY", "AVAILABLE"})
    void execute_orderInStateNotAdvanced_returnsAlreadyProcessedWithoutWrites(TicketStatus status) {
        when(orders.findById(ID)).thenReturn(Mono.just(valid(status)));

        StepVerifier.create(useCase.execute(ID)).expectNext(new AlreadyProcessed(ID, status)).verifyComplete();
        verifyNoInteractions(fulfillment, placement);
    }

    @Test
    void execute_reservedAndValid_marksPendingThenConfirmsSale() {
        var reserved = valid(TicketStatus.RESERVED);
        when(orders.findById(ID)).thenReturn(Mono.just(reserved));
        stepsSucceed();

        StepVerifier.create(useCase.execute(ID)).expectNext(new Sold(ID)).verifyComplete();

        var order = inOrder(fulfillment);
        order.verify(fulfillment).markPendingConfirmation(reserved, "order-processor", NOW);
        order.verify(fulfillment).confirmSale(reserved, "order-processor", NOW);
        verifyNoInteractions(placement);
    }

    @Test
    void execute_pendingAndValid_onlyConfirmsSale() {
        var pending = valid(TicketStatus.PENDING_CONFIRMATION);
        when(orders.findById(ID)).thenReturn(Mono.just(pending));
        stepsSucceed();

        StepVerifier.create(useCase.execute(ID)).expectNext(new Sold(ID)).verifyComplete();

        verify(fulfillment, never()).markPendingConfirmation(any(), any(), any());
        verify(fulfillment).confirmSale(pending, "order-processor", NOW);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"RESERVED", "PENDING_CONFIRMATION"})
    void execute_expiredReservation_releasesInsteadOfSelling(TicketStatus status) {
        var expired = order(status, NOW);
        when(orders.findById(ID)).thenReturn(Mono.just(expired));
        when(placement.releaseReservation(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(audit(status, TicketStatus.AVAILABLE)));

        StepVerifier.create(useCase.execute(ID)).expectNext(new ReleasedAsExpired(ID)).verifyComplete();

        verify(placement).releaseReservation(expired, status, "order-processor", "reservation expired", NOW);
        verifyNoInteractions(fulfillment);
    }

    @Test
    void execute_expiryOneNanoBeforeNow_isExpired_andOneAfterIsValid() {
        when(orders.findById(ID)).thenReturn(Mono.just(order(TicketStatus.RESERVED, NOW.minusNanos(1))));
        when(placement.releaseReservation(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(ID)).expectNext(new ReleasedAsExpired(ID)).verifyComplete();

        when(orders.findById(ID)).thenReturn(Mono.just(order(TicketStatus.PENDING_CONFIRMATION, NOW.plusNanos(1))));
        stepsSucceed();
        StepVerifier.create(useCase.execute(ID)).expectNext(new Sold(ID)).verifyComplete();
    }

    @Test
    void execute_loserOfFirstStepRace_rereadsAndEndsAlreadyProcessed() {
        when(orders.findById(ID)).thenReturn(Mono.just(valid(TicketStatus.RESERVED)),
                Mono.just(valid(TicketStatus.SOLD)));
        when(fulfillment.markPendingConfirmation(any(), any(), any()))
                .thenReturn(Mono.error(conflict(TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION)));

        StepVerifier.create(useCase.execute(ID))
                .expectNext(new AlreadyProcessed(ID, TicketStatus.SOLD)).verifyComplete();
        verify(fulfillment, never()).confirmSale(any(), any(), any());
    }

    @Test
    void execute_loserOfFirstStepFindsPending_helpsFinishTheSale() {
        when(orders.findById(ID)).thenReturn(Mono.just(valid(TicketStatus.RESERVED)),
                Mono.just(valid(TicketStatus.PENDING_CONFIRMATION)));
        when(fulfillment.markPendingConfirmation(any(), any(), any()))
                .thenReturn(Mono.error(conflict(TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION)));
        when(fulfillment.confirmSale(any(), any(), any()))
                .thenReturn(Mono.just(audit(TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD)));

        StepVerifier.create(useCase.execute(ID)).expectNext(new Sold(ID)).verifyComplete();
    }

    @Test
    void execute_loserOfConfirmRace_endsAlreadyProcessed() {
        when(orders.findById(ID)).thenReturn(Mono.just(valid(TicketStatus.PENDING_CONFIRMATION)),
                Mono.just(valid(TicketStatus.SOLD)));
        when(fulfillment.confirmSale(any(), any(), any()))
                .thenReturn(Mono.error(conflict(TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD)));

        StepVerifier.create(useCase.execute(ID))
                .expectNext(new AlreadyProcessed(ID, TicketStatus.SOLD)).verifyComplete();
    }

    @Test
    void execute_loserOfReleaseRace_endsAlreadyProcessed() {
        when(orders.findById(ID)).thenReturn(Mono.just(order(TicketStatus.RESERVED, NOW)),
                Mono.just(valid(TicketStatus.AVAILABLE)));
        when(placement.releaseReservation(any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(conflict(TicketStatus.RESERVED, TicketStatus.AVAILABLE)));

        StepVerifier.create(useCase.execute(ID))
                .expectNext(new AlreadyProcessed(ID, TicketStatus.AVAILABLE)).verifyComplete();
    }

    @Test
    void execute_orderVanishesDuringStep_returnsOrderMissing() {
        when(orders.findById(ID)).thenReturn(Mono.just(valid(TicketStatus.PENDING_CONFIRMATION)));
        when(fulfillment.confirmSale(any(), any(), any())).thenReturn(Mono.error(new OrderNotFoundException(ID)));

        StepVerifier.create(useCase.execute(ID)).expectNext(new OrderMissing(ID)).verifyComplete();
    }

    @Test
    void execute_endlessConflicts_failsAfterBoundedAttempts() {
        when(orders.findById(ID)).thenReturn(Mono.just(valid(TicketStatus.PENDING_CONFIRMATION)));
        when(fulfillment.confirmSale(any(), any(), any()))
                .thenReturn(Mono.error(conflict(TicketStatus.PENDING_CONFIRMATION, null)));

        StepVerifier.create(useCase.execute(ID)).expectError(OrderStatusConflictException.class).verify();
        verify(orders, times(ProcessOrderUseCase.MAX_ATTEMPTS)).findById(ID);
    }

    @Test
    void execute_transientInfrastructureError_propagates() {
        when(orders.findById(ID)).thenReturn(Mono.just(valid(TicketStatus.PENDING_CONFIRMATION)));
        when(fulfillment.confirmSale(any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("boom")));

        StepVerifier.create(useCase.execute(ID)).expectError(IllegalStateException.class).verify();
    }

    @Test
    void execute_readFails_propagates() {
        when(orders.findById(ID)).thenReturn(Mono.error(new IllegalStateException("down")));

        StepVerifier.create(useCase.execute(ID)).expectError(IllegalStateException.class).verify();
        verify(placement, never()).releaseReservation(any(), eq(TicketStatus.RESERVED), any(), any(), any());
    }

    @Test
    void result_everyVariantExposesOrderId() {
        assertThat(new Sold(ID).orderId()).isEqualTo(ID);
        assertThat(new ReleasedAsExpired(ID).orderId()).isEqualTo(ID);
        assertThat(new AlreadyProcessed(ID, TicketStatus.SOLD).orderId()).isEqualTo(ID);
        assertThat(new OrderMissing(ID).orderId()).isEqualTo(ID);
    }
}
