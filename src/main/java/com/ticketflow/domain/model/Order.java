package com.ticketflow.domain.model;

import java.time.Instant;

/** A purchase order for a quantity of tickets of one event. */
public record Order(
        OrderId id,
        EventId eventId,
        Quantity quantity,
        TicketStatus status,
        IdempotencyKey idempotencyKey,
        Instant reservationExpiresAt,
        Instant createdAt) {

    public Order {
        requireNonNull(id, "id");
        requireNonNull(eventId, "eventId");
        requireNonNull(quantity, "quantity");
        requireNonNull(status, "status");
        requireNonNull(idempotencyKey, "idempotencyKey");
        requireNonNull(reservationExpiresAt, "reservationExpiresAt");
        requireNonNull(createdAt, "createdAt");
        if (!reservationExpiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Order reservationExpiresAt must be after createdAt");
        }
    }

    /** Returns a copy in the target status, enforcing the ticket state machine. */
    public Order transitionTo(TicketStatus target) {
        return new Order(id, eventId, quantity, status.transitionTo(target), idempotencyKey,
                reservationExpiresAt, createdAt);
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Order " + field + " must not be null");
        }
    }
}
