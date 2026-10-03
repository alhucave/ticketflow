package com.ticketflow.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import com.ticketflow.domain.port.OrderPlacementRepository;
import com.ticketflow.domain.port.OrderRepository;
import com.ticketflow.usecase.ReleaseExpiredReservationsUseCase.Summary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ReleaseExpiredReservationsUseCaseTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderPlacementRepository placement = mock(OrderPlacementRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private ReleaseExpiredReservationsUseCase useCase(int concurrency, int max) {
        return new ReleaseExpiredReservationsUseCase(orders, placement, clock, concurrency, max);
    }

    private static Order order(String id, TicketStatus status) {
        return new Order(new OrderId(id), new EventId("e-1"), new Quantity(2), status, new IdempotencyKey("k-" + id),
                NOW.minusSeconds(1), NOW.minusSeconds(601));
    }

    private static OrderAuditEntry audit(Order order) {
        return new OrderAuditEntry(order.id(), NOW, order.status(), TicketStatus.AVAILABLE, "reservation-expirer",
                "reservation expired");
    }

    private void releasesSucceed() {
        when(placement.releaseReservation(any(), any(), any(), any(), any()))
                .thenAnswer(i -> Mono.just(audit(i.getArgument(0))));
    }

    @Test
    void execute_nothingExpired_returnsEmptySummaryWithoutWrites() {
        when(orders.findExpiredReservations(NOW)).thenReturn(Flux.empty());

        StepVerifier.create(useCase(2, 10).execute()).expectNext(new Summary(0, 0, 0, 0)).verifyComplete();
        verify(placement, never()).releaseReservation(any(), any(), any(), any(), any());
    }

    @Test
    void execute_expiredOrders_releasedWithOwnStatusReasonAndNow() {
        var reserved = order("o-1", TicketStatus.RESERVED);
        var pending = order("o-2", TicketStatus.PENDING_CONFIRMATION);
        when(orders.findExpiredReservations(NOW)).thenReturn(Flux.just(reserved, pending));
        releasesSucceed();

        StepVerifier.create(useCase(2, 10).execute()).expectNext(new Summary(2, 2, 0, 0)).verifyComplete();

        verify(placement).releaseReservation(reserved, TicketStatus.RESERVED, "reservation-expirer",
                "reservation expired", NOW);
        verify(placement).releaseReservation(pending, TicketStatus.PENDING_CONFIRMATION, "reservation-expirer",
                "reservation expired", NOW);
    }

    @Test
    void execute_lostRace_isBenignConflictNotFailure() {
        var lost = order("o-1", TicketStatus.RESERVED);
        var won = order("o-2", TicketStatus.RESERVED);
        when(orders.findExpiredReservations(NOW)).thenReturn(Flux.just(lost, won));
        when(placement.releaseReservation(eq(lost), any(), any(), any(), any())).thenReturn(Mono.error(
                new OrderStatusConflictException(lost.id(), TicketStatus.RESERVED, TicketStatus.SOLD)));
        when(placement.releaseReservation(eq(won), any(), any(), any(), any())).thenReturn(Mono.just(audit(won)));

        StepVerifier.create(useCase(1, 10).execute()).expectNext(new Summary(2, 1, 1, 0)).verifyComplete();
    }

    @Test
    void execute_perOrderFailure_isContainedAndSweepContinues() {
        var bad = order("o-1", TicketStatus.RESERVED);
        var missing = order("o-2", TicketStatus.RESERVED);
        var good = order("o-3", TicketStatus.PENDING_CONFIRMATION);
        when(orders.findExpiredReservations(NOW)).thenReturn(Flux.just(bad, missing, good));
        when(placement.releaseReservation(eq(bad), any(), any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("throttled")));
        when(placement.releaseReservation(eq(missing), any(), any(), any(), any()))
                .thenReturn(Mono.error(new OrderNotFoundException(missing.id())));
        when(placement.releaseReservation(eq(good), any(), any(), any(), any())).thenReturn(Mono.just(audit(good)));

        StepVerifier.create(useCase(1, 10).execute()).expectNext(new Summary(3, 1, 0, 2)).verifyComplete();
    }

    @Test
    void execute_synchronousFailureOfPort_isContainedToo() {
        var bad = order("o-1", TicketStatus.RESERVED);
        when(orders.findExpiredReservations(NOW)).thenReturn(Flux.just(bad));
        when(placement.releaseReservation(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        StepVerifier.create(useCase(1, 10).execute()).expectNext(new Summary(1, 0, 0, 1)).verifyComplete();
    }

    @Test
    void execute_orderListedUnderBothStatuses_isReleasedOnce() {
        var asReserved = order("o-1", TicketStatus.RESERVED);
        var asPending = order("o-1", TicketStatus.PENDING_CONFIRMATION);
        when(orders.findExpiredReservations(NOW)).thenReturn(Flux.just(asReserved, asPending));
        releasesSucceed();

        StepVerifier.create(useCase(2, 10).execute()).expectNext(new Summary(1, 1, 0, 0)).verifyComplete();
        verify(placement, times(1)).releaseReservation(any(), any(), any(), any(), any());
    }

    @Test
    void execute_moreCandidatesThanMax_handlesOnlyMaxAndStopsReadingPages() {
        var reads = new AtomicInteger();
        // a long (paginated, lazy) source: only what is needed must be consumed
        when(orders.findExpiredReservations(NOW)).thenReturn(Flux.range(0, 1_000_000)
                .doOnNext(i -> reads.incrementAndGet())
                .map(i -> order("o-" + i, TicketStatus.RESERVED)));
        releasesSucceed();

        StepVerifier.create(useCase(4, 5).execute()).expectNext(new Summary(5, 5, 0, 0)).verifyComplete();
        verify(placement, times(5)).releaseReservation(any(), any(), any(), any(), any());
        assertThat(reads.get()).isLessThan(1_000);
    }

    @Test
    void execute_concurrencyIsBounded() {
        var inFlight = new AtomicInteger();
        var peak = new AtomicInteger();
        List<Order> many = IntStream.range(0, 12).mapToObj(i -> order("o-" + i, TicketStatus.RESERVED)).toList();
        when(orders.findExpiredReservations(NOW)).thenReturn(Flux.fromIterable(many));
        when(placement.releaseReservation(any(), any(), any(), any(), any())).thenAnswer(i -> Mono.defer(() -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            return Mono.delay(Duration.ofSeconds(1)).thenReturn(audit(i.getArgument(0)))
                    .doOnTerminate(inFlight::decrementAndGet);
        }));

        StepVerifier.withVirtualTime(() -> useCase(3, 100).execute())
                .thenAwait(Duration.ofSeconds(10))
                .expectNext(new Summary(12, 12, 0, 0))
                .verifyComplete();
        assertThat(peak.get()).isEqualTo(3);
    }

    @Test
    void execute_queryFailure_propagatesForTheSchedulerToLog() {
        when(orders.findExpiredReservations(NOW)).thenReturn(Flux.error(new IllegalStateException("gsi down")));

        StepVerifier.create(useCase(1, 10).execute()).expectErrorMessage("gsi down").verify();
    }

    @Test
    void execute_readsTheClockOncePerSweepAndPassesItToTheQuery() {
        var clockMock = mock(Clock.class);
        when(clockMock.instant()).thenReturn(NOW);
        when(orders.findExpiredReservations(NOW)).thenReturn(Flux.empty());

        StepVerifier.create(new ReleaseExpiredReservationsUseCase(orders, placement, clockMock, 1, 1).execute())
                .expectNextCount(1).verifyComplete();
        verify(clockMock, times(1)).instant();
    }

    @Test
    void constructor_invalidBounds_rejected() {
        assertThatThrownBy(() -> useCase(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
