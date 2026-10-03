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
        // A complimentary order holds no reservation: it is created with expiry == creation instant, so the
        // expiry may equal createdAt (its status is not in the expiry index, nothing ever sweeps it).
        boolean valid = status == TicketStatus.COMPLIMENTARY
                ? !reservationExpiresAt.isBefore(createdAt)
                : reservationExpiresAt.isAfter(createdAt);
        if (!valid) {
            throw new IllegalArgumentException(status == TicketStatus.COMPLIMENTARY
                    ? "Order reservationExpiresAt must not be before createdAt when COMPLIMENTARY"
                    : "Order reservationExpiresAt must be after createdAt");
        }
    }

    /** A complimentary order: final, no reservation to expire ({@code reservationExpiresAt == createdAt}). */
    public static Order complimentary(
            OrderId id, EventId eventId, Quantity quantity, IdempotencyKey idempotencyKey, Instant createdAt) {
        return new Order(id, eventId, quantity, TicketStatus.COMPLIMENTARY, idempotencyKey, createdAt, createdAt);
    }

    /** Returns a copy in the target status, enforcing the ticket state machine. */
    public Order transitionTo(TicketStatus target) {
        TicketStatus next = status.transitionTo(target);
        return new Order(id, eventId, quantity, next, idempotencyKey,
                next == TicketStatus.COMPLIMENTARY ? createdAt : reservationExpiresAt, createdAt);
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Order " + field + " must not be null");
        }
    }
}
