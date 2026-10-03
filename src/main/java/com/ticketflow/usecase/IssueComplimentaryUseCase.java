package com.ticketflow.usecase;

import com.ticketflow.domain.exception.IdempotencyKeyReusedException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.exception.OrderAlreadyExistsException;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderAuditEntry;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderPlacementRepository;
import com.ticketflow.domain.port.OrderRepository;
import java.time.Clock;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * Moves tickets {@code AVAILABLE -> COMPLIMENTARY}. COMPLIMENTARY is final and is never counted as
 * sold: the inventory counter {@code complimentary} is separate from {@code sold}.
 *
 * <ol>
 *   <li>Inventory move (conditional on {@code available >= quantity}, so it can never exceed what is
 *       available), the COMPLIMENTARY order record and the audit entry are one transaction
 *       ({@link OrderPlacementRepository#issueComplimentary}).</li>
 *   <li>The order id is derived from the idempotency key in a namespace of its own
 *       ({@link OrderId#complimentaryFromIdempotencyKey}), so the key maps to exactly one issuance,
 *       enforced by the order table's primary key even for concurrent requests, and can never collide
 *       with a purchase made with the same key.</li>
 *   <li>No reservation, no queue message: nothing is left to expire or to process.</li>
 * </ol>
 *
 * <p>A replay with the same key and the same payload (event, quantity, reason) returns the existing
 * issuance; with a different payload it fails with {@link IdempotencyKeyReusedException}.
 */
public class IssueComplimentaryUseCase {

    static final String ACTOR = "complimentary-issuance";

    private final OrderPlacementRepository placement;
    private final OrderRepository orders;
    private final Clock clock;
    private final BusinessMetrics metrics;

    public IssueComplimentaryUseCase(OrderPlacementRepository placement, OrderRepository orders, Clock clock) {
        this(placement, orders, clock, BusinessMetrics.NOOP);
    }

    public IssueComplimentaryUseCase(OrderPlacementRepository placement, OrderRepository orders, Clock clock,
                                     BusinessMetrics metrics) {
        this.metrics = metrics;
        this.placement = placement;
        this.orders = orders;
        this.clock = clock;
    }

    public Mono<IssueComplimentaryResult> execute(IssueComplimentaryCommand command) {
        return Mono.defer(() -> {
            Order order = Order.complimentary(OrderId.complimentaryFromIdempotencyKey(command.idempotencyKey()),
                    command.eventId(), command.quantity(), command.idempotencyKey(), clock.instant());
            return placement.issueComplimentary(order, ACTOR, command.reason())
                    .doOnNext(stored -> metrics.complimentaryIssued())
                    .map(stored -> IssueComplimentaryResult.of(stored, false))
                    .onErrorResume(OrderAlreadyExistsException.class, error -> replay(command, order))
                    .doOnError(InsufficientInventoryException.class, error -> metrics.conflict(
                            BusinessMetrics.ConflictType.INVENTORY_INSUFFICIENT, BusinessMetrics.Operation.COMPLIMENTARY));
        });
    }

    /** The key already issued: return it if the payload matches, reject otherwise. */
    private Mono<IssueComplimentaryResult> replay(IssueComplimentaryCommand command, Order attempted) {
        return orders.findById(attempted.id())
                .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                        "Order " + attempted.id().value() + " reported as existing but not found")))
                .flatMap(existing -> {
                    if (existing.status() != TicketStatus.COMPLIMENTARY
                            || !existing.eventId().equals(command.eventId())
                            || !existing.quantity().equals(command.quantity())) {
                        return Mono.<IssueComplimentaryResult>error(reused(command, existing));
                    }
                    return storedReason(existing)
                            .map(reason -> Objects.equals(reason, command.reason()))
                            .defaultIfEmpty(command.reason() == null)
                            .flatMap(sameReason -> sameReason
                                    ? Mono.just(IssueComplimentaryResult.of(existing, true))
                                    : Mono.<IssueComplimentaryResult>error(reused(command, existing)));
                });
    }

    /** The reason written with the issuance; empty when the audit entry carries none. */
    private Mono<String> storedReason(Order existing) {
        return orders.findAuditTrail(existing.id())
                .filter(entry -> entry.to() == TicketStatus.COMPLIMENTARY)
                .next()
                .mapNotNull(OrderAuditEntry::reason);
    }

    private static IdempotencyKeyReusedException reused(IssueComplimentaryCommand command, Order existing) {
        return new IdempotencyKeyReusedException(command.idempotencyKey(), existing.id());
    }
}
