package com.ticketflow.domain.port;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Inventory;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import reactor.core.publisher.Mono;

/** Output port for inventory counters. All mutations must be conditional writes. */
public interface InventoryRepository {

    /** Stores a new inventory; fails if one already exists for the event. */
    Mono<Inventory> create(Inventory inventory);

    /** Emits the inventory, or completes empty when absent. */
    Mono<Inventory> findByEventId(EventId eventId);

    /**
     * Atomically moves {@code quantity} tickets from state {@code from} to {@code to}, bumping the version.
     * The write is conditional on {@code version = expectedVersion} and on enough tickets in {@code from}.
     *
     * @throws com.ticketflow.domain.exception.InsufficientInventoryException when {@code from} lacks tickets
     * @throws com.ticketflow.domain.exception.ConcurrentModificationException when the version changed
     * @throws com.ticketflow.domain.exception.InvalidStateTransitionException for an invalid transition
     */
    Mono<Inventory> transition(
            EventId eventId, TicketStatus from, TicketStatus to, Quantity quantity, long expectedVersion);
}
