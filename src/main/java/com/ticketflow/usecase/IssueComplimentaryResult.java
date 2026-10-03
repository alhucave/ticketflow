package com.ticketflow.usecase;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;

/**
 * Outcome of a complimentary issuance. The status is always COMPLIMENTARY (final). {@code replayed}
 * is true when the idempotency key had already issued these tickets (nothing was moved again).
 */
public record IssueComplimentaryResult(
        OrderId orderId, EventId eventId, Quantity quantity, TicketStatus status, boolean replayed) {

    static IssueComplimentaryResult of(Order order, boolean replayed) {
        return new IssueComplimentaryResult(order.id(), order.eventId(), order.quantity(), order.status(), replayed);
    }
}
