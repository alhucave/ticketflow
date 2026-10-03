package com.ticketflow.usecase;

import com.ticketflow.domain.exception.OrderStatusConflictException;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.port.OrderPlacementRepository;
import com.ticketflow.domain.port.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * One sweep of the reservation-expiration job: finds the reservations that went past their limit
 * without being confirmed and returns their tickets to the available inventory.
 *
 * <p>An order is expired when {@code reservationExpiresAt <= now} (same boundary as
 * {@link ProcessOrderUseCase}). Each order is released on its own through
 * {@link OrderPlacementRepository#releaseReservation}: a single transaction (conditional order update
 * to AVAILABLE, audit entry with the reason, inventory {@code reserved/pendingConfirmation ->
 * available}). There is no EXPIRED state; the reason lives in the audit entry.
 *
 * <p><b>Safety.</b> The expired-order query is index based and eventually consistent, so it is only a
 * list of candidates: the conditional write decides. Several instances sweeping at once, or a consumer
 * processing the same order, race on the order status; exactly one wins and the others get an
 * {@link OrderStatusConflictException}, which is benign (counted in
 * {@link Summary#skippedConflicts()}). An order the query misses (just created, stale index) is simply
 * picked up by the next sweep. A candidate whose status changed between the read and the release
 * (RESERVED -> PENDING_CONFIRMATION) also ends as a benign conflict and is retried next sweep.
 *
 * <p><b>Robustness.</b> A failure on one order is logged and counted ({@link Summary#failed()}), never
 * aborting the sweep. Work per sweep is bounded: at most {@code maxPerSweep} candidates, released with
 * at most {@code concurrency} transactions in flight. A failure of the query itself propagates (the
 * scheduler logs it and tries again at the next interval).
 */
public class ReleaseExpiredReservationsUseCase {

    static final String ACTOR = "reservation-expirer";

    private static final Logger LOG = LoggerFactory.getLogger(ReleaseExpiredReservationsUseCase.class);

    private final OrderRepository orders;
    private final OrderPlacementRepository placement;
    private final Clock clock;
    private final int concurrency;
    private final int maxPerSweep;
    private final BusinessMetrics metrics;

    public ReleaseExpiredReservationsUseCase(OrderRepository orders, OrderPlacementRepository placement,
                                             Clock clock, int concurrency, int maxPerSweep) {
        this(orders, placement, clock, concurrency, maxPerSweep, BusinessMetrics.NOOP);
    }

    public ReleaseExpiredReservationsUseCase(OrderRepository orders, OrderPlacementRepository placement,
                                             Clock clock, int concurrency, int maxPerSweep,
                                             BusinessMetrics metrics) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be at least 1");
        }
        if (maxPerSweep < 1) {
            throw new IllegalArgumentException("maxPerSweep must be at least 1");
        }
        this.orders = orders;
        this.placement = placement;
        this.clock = clock;
        this.concurrency = concurrency;
        this.maxPerSweep = maxPerSweep;
        this.metrics = metrics;
    }

    /** Runs one sweep; the clock is read once so every order is judged against the same instant. */
    public Mono<Summary> execute() {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            return orders.findExpiredReservations(now)
                    // an order can show up under both statuses if it moves between the two queries
                    .distinct(Order::id)
                    .take(maxPerSweep)
                    .flatMap(order -> release(order, now), concurrency)
                    .reduce(Summary.EMPTY, Summary::plus)
                    .doOnNext(summary -> log(summary, now));
        });
    }

    private Mono<Summary> release(Order order, Instant now) {
        return Mono.defer(() -> placement.releaseReservation(order, order.status(), ACTOR,
                        ProcessOrderUseCase.EXPIRED_REASON, now))
                .doOnNext(entry -> metrics.orderReleased(BusinessMetrics.ReleaseReason.EXPIRED))
                .thenReturn(Summary.RELEASED)
                .onErrorResume(OrderStatusConflictException.class, error -> {
                    metrics.conflict(BusinessMetrics.ConflictType.ORDER_STATUS,
                            BusinessMetrics.Operation.EXPIRATION_SWEEP);
                    LOG.debug("Order {} changed concurrently ({}); skipping", order.id().value(),
                            error.getMessage());
                    return Mono.just(Summary.CONFLICT);
                })
                .onErrorResume(error -> {
                    LOG.warn("Could not release expired order {}: {}: {}", order.id().value(),
                            error.getClass().getSimpleName(), error.getMessage());
                    return Mono.just(Summary.FAILED);
                });
    }

    private static void log(Summary summary, Instant now) {
        if (summary.examined() == 0) {
            LOG.debug("Expiration sweep at {}: no expired reservations", now);
        } else {
            LOG.info("Expiration sweep at {}: examined={}, released={}, skippedConflicts={}, failed={}", now,
                    summary.examined(), summary.released(), summary.skippedConflicts(), summary.failed());
        }
    }

    /**
     * Outcome of a sweep.
     *
     * @param examined expired candidates processed
     * @param released orders this sweep moved back to AVAILABLE
     * @param skippedConflicts orders someone else (another sweep or a consumer) resolved first
     * @param failed orders whose release failed for any other reason (retried next sweep)
     */
    public record Summary(int examined, int released, int skippedConflicts, int failed) {

        static final Summary EMPTY = new Summary(0, 0, 0, 0);
        private static final Summary RELEASED = new Summary(1, 1, 0, 0);
        private static final Summary CONFLICT = new Summary(1, 0, 1, 0);
        private static final Summary FAILED = new Summary(1, 0, 0, 1);

        Summary plus(Summary other) {
            return new Summary(examined + other.examined, released + other.released,
                    skippedConflicts + other.skippedConflicts, failed + other.failed);
        }
    }
}
