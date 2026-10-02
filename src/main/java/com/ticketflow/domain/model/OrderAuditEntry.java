package com.ticketflow.domain.model;

import com.ticketflow.domain.exception.InvalidStateTransitionException;
import java.time.Instant;

/** Audit record of one order state transition. */
public record OrderAuditEntry(OrderId orderId, Instant timestamp, TicketStatus from, TicketStatus to, String actor) {

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
    }
}
