package com.ticketflow.domain.exception;

import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.TicketStatus;

/**
 * A status transition was rejected because the order was no longer in the expected status
 * (for example a concurrent transition won the race).
 */
public class OrderStatusConflictException extends RuntimeException {

    private final OrderId orderId;
    private final TicketStatus expected;
    private final TicketStatus actual;

    /** @param actual the status found at write time, or {@code null} when it could not be determined */
    public OrderStatusConflictException(OrderId orderId, TicketStatus expected, TicketStatus actual) {
        super("Order " + orderId.value() + " expected in status " + expected + " but was "
                + (actual == null ? "unknown" : actual.name()));
        this.orderId = orderId;
        this.expected = expected;
        this.actual = actual;
    }

    public OrderId orderId() {
        return orderId;
    }

    public TicketStatus expected() {
        return expected;
    }

    public TicketStatus actual() {
        return actual;
    }
}
