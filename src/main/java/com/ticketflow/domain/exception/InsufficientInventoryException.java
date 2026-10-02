package com.ticketflow.domain.exception;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Quantity;

/** Not enough tickets in the source state to satisfy the request. */
public class InsufficientInventoryException extends RuntimeException {

    private final EventId eventId;
    private final Quantity requested;

    public InsufficientInventoryException(EventId eventId, Quantity requested) {
        super("Insufficient inventory for event " + eventId.value() + ": requested " + requested.value());
        this.eventId = eventId;
        this.requested = requested;
    }

    public EventId eventId() {
        return eventId;
    }

    public Quantity requested() {
        return requested;
    }
}
