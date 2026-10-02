package com.ticketflow.domain.port;

import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderAuditEntry;
import com.ticketflow.domain.model.OrderId;
import java.time.Instant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Output port for orders and their audit trail. */
public interface OrderRepository {

    Mono<Order> save(Order order);

    /** Emits the order, or completes empty when absent. */
    Mono<Order> findById(OrderId id);

    /** Looks an order up by its idempotency key (empty when none). */
    Mono<Order> findByIdempotencyKey(IdempotencyKey key);

    /** Orders in RESERVED or PENDING_CONFIRMATION whose reservation expired before {@code now}. */
    Flux<Order> findExpiredReservations(Instant now);

    Mono<OrderAuditEntry> saveAuditEntry(OrderAuditEntry entry);

    Flux<OrderAuditEntry> findAuditTrail(OrderId id);
}
