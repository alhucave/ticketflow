package com.ticketflow.usecase;

import com.ticketflow.domain.exception.IdempotencyKeyReusedException;
import com.ticketflow.domain.exception.IdempotentOrderNotActiveException;
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
 * <p>A replayed key returns the existing order as it stands and does not publish again, unless that
 * order was released (AVAILABLE): then {@link IdempotentOrderNotActiveException} is raised and the
 * client must use a new key. If the
 * first attempt crashed between the transaction and the publish, the reservation is reclaimed when
 * it expires.
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

    public RequestPurchaseUseCase(OrderPlacementRepository placement, OrderRepository orders,
                                  OrderQueuePublisher queue, Clock clock, Duration reservationTtl) {
        if (reservationTtl == null || reservationTtl.isZero() || reservationTtl.isNegative()) {
            throw new IllegalArgumentException("Reservation TTL must be positive: " + reservationTtl);
        }
        this.placement = placement;
        this.orders = orders;
        this.queue = queue;
        this.clock = clock;
        this.reservationTtl = reservationTtl;
    }

    public Mono<RequestPurchaseResult> execute(RequestPurchaseCommand command) {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            Order order = new Order(OrderId.fromIdempotencyKey(command.idempotencyKey()), command.eventId(),
                    command.quantity(), TicketStatus.RESERVED, command.idempotencyKey(),
                    now.plus(reservationTtl), now);
            return placement.placeReservation(order, ACTOR)
                    .flatMap(this::enqueue)
                    .onErrorResume(OrderAlreadyExistsException.class, error -> replay(command, order));
        });
    }

    private Mono<RequestPurchaseResult> enqueue(Order order) {
        return queue.publish(order)
                .thenReturn(RequestPurchaseResult.of(order, false))
                .onErrorResume(publishError -> compensate(order, publishError));
    }

    private Mono<RequestPurchaseResult> compensate(Order order, Throwable publishError) {
        LOG.error("Publishing order {} failed; releasing its reservation", order.id().value(), publishError);
        return placement.releaseReservation(order, TicketStatus.RESERVED, ACTOR, PUBLISH_FAILED_REASON, clock.instant())
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
                        return Mono.<RequestPurchaseResult>error(
                                new IdempotencyKeyReusedException(command.idempotencyKey(), existing.id()));
                    }
                    if (existing.status() == TicketStatus.AVAILABLE) {
                        return Mono.<RequestPurchaseResult>error(
                                new IdempotentOrderNotActiveException(command.idempotencyKey(), existing.id()));
                    }
                    return Mono.just(RequestPurchaseResult.of(existing, true));
                });
    }
}
