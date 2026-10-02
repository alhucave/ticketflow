package com.ticketflow.domain.port;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Inventory;
import com.ticketflow.domain.model.Quantity;
import reactor.core.publisher.Mono;

/**
 * Output port for inventory counters. Every mutation is a single atomic conditional write that
 * moves tickets between counters, increments {@code version} and emits the resulting inventory.
 * The invariant available + reserved + pendingConfirmation + sold + complimentary = capacity
 * holds after every operation.
 *
 * <p>Each mutation fails with {@link com.ticketflow.domain.exception.InsufficientInventoryException}
 * when the source counter holds fewer than {@code quantity} tickets, and with
 * {@link com.ticketflow.domain.exception.EventNotFoundException} when no inventory exists.
 */
public interface InventoryRepository {

    /**
     * Stores a new inventory.
     *
     * @throws com.ticketflow.domain.exception.EventAlreadyExistsException if one already exists for the event
     */
    Mono<Inventory> create(Inventory inventory);

    /** Emits the inventory, or completes empty when absent. */
    Mono<Inventory> findByEventId(EventId eventId);

    /** Moves {@code quantity} tickets {@code available -> reserved}. */
    Mono<Inventory> reserve(EventId eventId, Quantity quantity);

    /** Moves {@code quantity} tickets {@code reserved -> sold}. */
    Mono<Inventory> confirmSale(EventId eventId, Quantity quantity);

    /** Moves {@code quantity} tickets {@code reserved -> available} (cancellation or expiry). */
    Mono<Inventory> release(EventId eventId, Quantity quantity);

    /** Moves {@code quantity} tickets {@code available -> complimentary}. */
    Mono<Inventory> issueComplimentary(EventId eventId, Quantity quantity);
}
