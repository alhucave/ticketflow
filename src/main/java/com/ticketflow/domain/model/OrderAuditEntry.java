package com.ticketflow.domain.model;

import com.ticketflow.domain.exception.InvalidStateTransitionException;
import java.time.Instant;

/**
 * Audit record of one order state transition. {@code reason} is optional free text explaining why
 * (for example a compensation or an expiry); {@code null} when the transition needs no explanation.
 */
public record OrderAuditEntry(
        OrderId orderId, Instant timestamp, TicketStatus from, TicketStatus to, String actor, String reason) {

    public OrderAuditEntry(OrderId orderId, Instant timestamp, TicketStatus from, TicketStatus to, String actor) {
        this(orderId, timestamp, from, to, actor, null);
    }

    public OrderAuditEntry {
        if (orderId == null || timestamp == null || from == null || to == null) {
            throw new IllegalArgumentException("OrderAuditEntry orderId, timestamp, from and to must not be null");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("OrderAuditEntry actor must not be blank");
        }
        if (!from.canTransitionTo(to)) {
            throw new InvalidStateTransitionException(from, to);
        }
        if (reason != null && reason.isBlank()) {
            throw new IllegalArgumentException("OrderAuditEntry reason must not be blank when present");
        }
    }
}
