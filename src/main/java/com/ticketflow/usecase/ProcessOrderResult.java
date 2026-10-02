package com.ticketflow.usecase;

import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.TicketStatus;

/**
 * Outcome of processing one order message. Every variant is a terminal, successful outcome for the
 * message (the consumer may acknowledge it); transient infrastructure failures are errors instead,
 * so the message is redelivered.
 */
public sealed interface ProcessOrderResult {

    OrderId orderId();

    /** This invocation completed the sale (the order is SOLD). */
    record Sold(OrderId orderId) implements ProcessOrderResult { }

    /** The reservation had expired: it was released and the tickets are available again. */
    record ReleasedAsExpired(OrderId orderId) implements ProcessOrderResult { }

    /** Nothing to do: the order was already in a state this use case does not advance (idempotent redelivery). */
    record AlreadyProcessed(OrderId orderId, TicketStatus status) implements ProcessOrderResult { }

    /** The order does not exist (nothing to process). */
    record OrderMissing(OrderId orderId) implements ProcessOrderResult { }
}
