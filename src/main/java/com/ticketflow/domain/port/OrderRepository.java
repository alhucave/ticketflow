package com.ticketflow.domain.port;

import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderAuditEntry;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.TicketStatus;
import java.time.Instant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Output port for orders and their audit trail. */
public interface OrderRepository {

    /**
     * Creates the order; never overwrites.
     *
     * @throws com.ticketflow.domain.exception.OrderAlreadyExistsException when the id is taken
     */
    Mono<Order> save(Order order);

    /**
     * Atomically moves the order from {@code expected} to {@code target} and appends the audit entry
     * (conditional on the expected current status; both writes succeed or neither does).
     *
     * @return the audit entry written
     * @throws com.ticketflow.domain.exception.InvalidStateTransitionException when expected -> target is not allowed
     * @throws com.ticketflow.domain.exception.OrderNotFoundException when the order does not exist
     * @throws com.ticketflow.domain.exception.OrderStatusConflictException when the order is not in {@code expected}
     */
    Mono<OrderAuditEntry> transition(OrderId id, TicketStatus expected, TicketStatus target, String actor, Instant at);

    /** Emits the order, or completes empty when absent. */
    Mono<Order> findById(OrderId id);

    /** Looks an order up by its idempotency key (empty when none). */
    Mono<Order> findByIdempotencyKey(IdempotencyKey key);

    /** Orders in RESERVED or PENDING_CONFIRMATION whose reservation expired before {@code now}. */
    Flux<Order> findExpiredReservations(Instant now);

    Mono<OrderAuditEntry> saveAuditEntry(OrderAuditEntry entry);

    Flux<OrderAuditEntry> findAuditTrail(OrderId id);
}
