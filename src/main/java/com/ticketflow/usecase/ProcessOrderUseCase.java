package com.ticketflow.usecase;

import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.exception.OrderNotFoundException;
import com.ticketflow.domain.exception.OrderStatusConflictException;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.port.OrderFulfillmentRepository;
import com.ticketflow.domain.port.OrderPlacementRepository;
import com.ticketflow.domain.port.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Processes an order message (SQS delivers at least once, possibly concurrently): validates that the
 * reservation is still valid and turns it into a sale.
 *
 * <p>It is a state machine driven by the order's current status (read consistently); every step is
 * one atomic transaction (order status + audit + inventory counters) and is idempotent:
 * <ul>
 *   <li>missing order: {@link ProcessOrderResult.OrderMissing}, no writes;</li>
 *   <li>SOLD, COMPLIMENTARY, AVAILABLE: {@link ProcessOrderResult.AlreadyProcessed}, no writes;</li>
 *   <li>RESERVED or PENDING_CONFIRMATION with an expired reservation: released
 *       ({@link ProcessOrderResult.ReleasedAsExpired}), never sold;</li>
 *   <li>RESERVED: {@code -> PENDING_CONFIRMATION}, then {@code -> SOLD};</li>
 *   <li>PENDING_CONFIRMATION (e.g. a previous attempt crashed between the two steps): {@code -> SOLD}.</li>
 * </ul>
 * Expiry is judged when each decision is taken, once per read; a reservation that was valid when it
 * entered PENDING_CONFIRMATION is confirmed in the same run.
 *
 * <p>Concurrent workers race on the conditional status check: exactly one wins each step. A loser
 * ({@link OrderStatusConflictException}) is benign: the order is re-read and the state machine is
 * evaluated again, so it ends as {@code AlreadyProcessed} or helps finish the remaining step; the
 * number of re-reads is bounded because every conflict means another worker made progress. Transient
 * infrastructure errors propagate so the message is redelivered.
 */
public class ProcessOrderUseCase {

    static final String ACTOR = "order-processor";
    static final String EXPIRED_REASON = "reservation expired";
    /** Each conflict means progress and an order has at most two forward steps, plus the release race. */
    static final int MAX_ATTEMPTS = 5;

    private static final Logger LOG = LoggerFactory.getLogger(ProcessOrderUseCase.class);

    private final OrderRepository orders;
    private final OrderFulfillmentRepository fulfillment;
    private final OrderPlacementRepository placement;
    private final Clock clock;
    private final BusinessMetrics metrics;

    public ProcessOrderUseCase(OrderRepository orders, OrderFulfillmentRepository fulfillment,
                               OrderPlacementRepository placement, Clock clock) {
        this(orders, fulfillment, placement, clock, BusinessMetrics.NOOP);
    }

    public ProcessOrderUseCase(OrderRepository orders, OrderFulfillmentRepository fulfillment,
                               OrderPlacementRepository placement, Clock clock, BusinessMetrics metrics) {
        this.metrics = metrics;
        this.orders = orders;
        this.fulfillment = fulfillment;
        this.placement = placement;
        this.clock = clock;
    }

    public Mono<ProcessOrderResult> execute(OrderId orderId) {
        return attempt(orderId, MAX_ATTEMPTS)
                .doOnNext(result -> metrics.orderProcessed(BusinessMetrics.ProcessOutcome.of(result)))
                // A source counter that cannot cover the move would break the inventory invariant: surface it.
                .doOnError(InsufficientInventoryException.class, error -> metrics.conflict(
                        BusinessMetrics.ConflictType.INVENTORY_INSUFFICIENT, BusinessMetrics.Operation.PROCESS_ORDER));
    }

    private Mono<ProcessOrderResult> attempt(OrderId orderId, int attemptsLeft) {
        return Mono.defer(() -> orders.findById(orderId)
                .flatMap(this::advance)
                .switchIfEmpty(Mono.fromSupplier(() -> new ProcessOrderResult.OrderMissing(orderId)))
                .onErrorResume(OrderNotFoundException.class,
                        error -> Mono.just(new ProcessOrderResult.OrderMissing(orderId)))
                .onErrorResume(OrderStatusConflictException.class, error -> {
                    metrics.conflict(BusinessMetrics.ConflictType.ORDER_STATUS, BusinessMetrics.Operation.PROCESS_ORDER);
                    if (attemptsLeft <= 1) {
                        return Mono.error(error);
                    }
                    LOG.debug("Order {} changed concurrently ({}); re-reading", orderId.value(), error.getMessage());
                    return attempt(orderId, attemptsLeft - 1);
                }));
    }

    private Mono<ProcessOrderResult> advance(Order order) {
        Instant now = clock.instant();
        return switch (order.status()) {
            case SOLD, COMPLIMENTARY, AVAILABLE ->
                    Mono.just(new ProcessOrderResult.AlreadyProcessed(order.id(), order.status()));
            case RESERVED -> isExpired(order, now)
                    ? release(order, now)
                    : fulfillment.markPendingConfirmation(order, ACTOR, now).then(sell(order));
            case PENDING_CONFIRMATION -> isExpired(order, now) ? release(order, now) : sell(order);
        };
    }

    private Mono<ProcessOrderResult> sell(Order order) {
        return Mono.defer(() -> fulfillment.confirmSale(order, ACTOR, clock.instant())
                .doOnNext(entry -> metrics.orderSold())
                .thenReturn(new ProcessOrderResult.Sold(order.id())));
    }

    private Mono<ProcessOrderResult> release(Order order, Instant now) {
        return placement.releaseReservation(order, order.status(), ACTOR, EXPIRED_REASON, now)
                .doOnNext(entry -> metrics.orderReleased(BusinessMetrics.ReleaseReason.EXPIRED))
                .thenReturn(new ProcessOrderResult.ReleasedAsExpired(order.id()));
    }

    private static boolean isExpired(Order order, Instant now) {
        return !order.reservationExpiresAt().isAfter(now);
    }
}
