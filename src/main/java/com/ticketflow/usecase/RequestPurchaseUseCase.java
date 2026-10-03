package com.ticketflow.usecase;

import com.ticketflow.domain.exception.IdempotencyKeyReusedException;
import com.ticketflow.domain.exception.IdempotentOrderNotActiveException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.exception.OrderAlreadyExistsException;
import com.ticketflow.domain.exception.OrderEnqueueFailedException;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderPlacementRepository;
import com.ticketflow.domain.port.OrderQueuePublisher;
import com.ticketflow.domain.port.OrderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Accepts a purchase: reserves the tickets for a limited time, records the order and enqueues it
 * for asynchronous processing, returning the order id without waiting for that processing.
 *
 * <ol>
 *   <li>The order id is derived from the idempotency key, so the key maps to exactly one order and
 *       the order table's primary-key guard enforces it, even for concurrent requests.</li>
 *   <li>Inventory reservation, order creation and the initial audit entry are one transaction
 *       ({@link OrderPlacementRepository#placeReservation}); there is no window where tickets are
 *       reserved without an order.</li>
 *   <li>The message is published after the transaction. If publishing fails, the reservation is
 *       released in a second transaction and the failure is surfaced, never swallowed.</li>
 * </ol>
 *
 * <p>A replayed key returns the existing order as it stands, unless that order was released
 * (AVAILABLE): then {@link IdempotentOrderNotActiveException} is raised and the client must use a new
 * key. If the existing order is still RESERVED, its message is published again, best effort: the first
 * attempt may have died between the transaction and the publish, which would leave a reservation with
 * no message. A duplicate message is harmless (the consumer is idempotent per order); a failed
 * republish neither fails the replay nor releases anything (the reservation expires on its own). Orders
 * past RESERVED were already picked up from the queue and are never republished.
 */
public class RequestPurchaseUseCase {

    static final String ACTOR = "purchase-request";
    static final String PUBLISH_FAILED_REASON = "enqueue failed";

    private static final Logger LOG = LoggerFactory.getLogger(RequestPurchaseUseCase.class);

    private final OrderPlacementRepository placement;
    private final OrderRepository orders;
    private final OrderQueuePublisher queue;
    private final Clock clock;
    private final Duration reservationTtl;
    private final BusinessMetrics metrics;

    public RequestPurchaseUseCase(OrderPlacementRepository placement, OrderRepository orders,
                                  OrderQueuePublisher queue, Clock clock, Duration reservationTtl) {
        this(placement, orders, queue, clock, reservationTtl, BusinessMetrics.NOOP);
    }

    public RequestPurchaseUseCase(OrderPlacementRepository placement, OrderRepository orders,
                                  OrderQueuePublisher queue, Clock clock, Duration reservationTtl,
                                  BusinessMetrics metrics) {
        if (reservationTtl == null || reservationTtl.isZero() || reservationTtl.isNegative()) {
            throw new IllegalArgumentException("Reservation TTL must be positive: " + reservationTtl);
        }
        this.placement = placement;
        this.orders = orders;
        this.queue = queue;
        this.clock = clock;
        this.reservationTtl = reservationTtl;
        this.metrics = metrics;
    }

    public Mono<RequestPurchaseResult> execute(RequestPurchaseCommand command) {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            Order order = new Order(OrderId.fromIdempotencyKey(command.idempotencyKey()), command.eventId(),
                    command.quantity(), TicketStatus.RESERVED, command.idempotencyKey(),
                    now.plus(reservationTtl), now);
            return placement.placeReservation(order, ACTOR)
                    .doOnNext(placed -> {
                        metrics.orderPlaced();
                        // Runs under the request's correlation id (MDC): the first line of an order's trail.
                        LOG.info("Order {} placed: {} ticket(s) reserved until {}", placed.id().value(),
                                placed.quantity().value(), placed.reservationExpiresAt());
                    })
                    .flatMap(this::enqueue)
                    .onErrorResume(OrderAlreadyExistsException.class, error -> replay(command, order))
                    .doOnError(InsufficientInventoryException.class, error -> {
                        metrics.purchaseRejected(BusinessMetrics.PurchaseRejection.INSUFFICIENT_INVENTORY);
                        metrics.conflict(BusinessMetrics.ConflictType.INVENTORY_INSUFFICIENT,
                                BusinessMetrics.Operation.PURCHASE);
                    });
        });
    }

    private Mono<RequestPurchaseResult> enqueue(Order order) {
        return queue.publish(order)
                .thenReturn(RequestPurchaseResult.of(order, false))
                .onErrorResume(publishError -> compensate(order, publishError));
    }

    private Mono<RequestPurchaseResult> compensate(Order order, Throwable publishError) {
        LOG.error("Publishing order {} failed; releasing its reservation", order.id().value(), publishError);
        metrics.purchaseRejected(BusinessMetrics.PurchaseRejection.ENQUEUE_FAILED);
        return placement.releaseReservation(order, TicketStatus.RESERVED, ACTOR, PUBLISH_FAILED_REASON, clock.instant())
                .doOnNext(released -> metrics.orderReleased(BusinessMetrics.ReleaseReason.PUBLISH_FAILED))
                .thenReturn(new OrderEnqueueFailedException(order.id(), true, publishError))
                .onErrorResume(releaseError -> {
                    LOG.error("Releasing the reservation of order {} failed; the expiry sweep will reclaim it",
                            order.id().value(), releaseError);
                    var failure = new OrderEnqueueFailedException(order.id(), false, publishError);
                    failure.addSuppressed(releaseError);
                    return Mono.just(failure);
                })
                .flatMap(Mono::<RequestPurchaseResult>error);
    }

    /** The key already created an order: return it if the payload matches, reject otherwise. */
    private Mono<RequestPurchaseResult> replay(RequestPurchaseCommand command, Order attempted) {
        return orders.findById(attempted.id())
                .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                        "Order " + attempted.id().value() + " reported as existing but not found")))
                .flatMap(existing -> {
                    if (!existing.eventId().equals(command.eventId())
                            || !existing.quantity().equals(command.quantity())) {
                        metrics.purchaseRejected(BusinessMetrics.PurchaseRejection.IDEMPOTENCY_KEY_REUSED);
                        return Mono.<RequestPurchaseResult>error(
                                new IdempotencyKeyReusedException(command.idempotencyKey(), existing.id()));
                    }
                    if (existing.status() == TicketStatus.AVAILABLE) {
                        metrics.purchaseRejected(BusinessMetrics.PurchaseRejection.ORDER_NOT_ACTIVE);
                        return Mono.<RequestPurchaseResult>error(
                                new IdempotentOrderNotActiveException(command.idempotencyKey(), existing.id()));
                    }
                    metrics.purchaseReplayed();
                    if (existing.status() == TicketStatus.RESERVED) {
                        return republish(existing).thenReturn(RequestPurchaseResult.of(existing, true));
                    }
                    return Mono.just(RequestPurchaseResult.of(existing, true));
                });
    }

    /** Best effort: never fails, never compensates. Logs the failure class only, no internals. */
    private Mono<Void> republish(Order existing) {
        return Mono.defer(() -> queue.publish(existing))
                .doOnSuccess(done -> LOG.info("Replay of order {} republished its message", existing.id().value()))
                .onErrorResume(error -> {
                    LOG.warn("Replay of order {} could not republish its message ({}); the reservation stays "
                            + "and expires on its own", existing.id().value(), error.getClass().getSimpleName());
                    return Mono.empty();
                });
    }
}
