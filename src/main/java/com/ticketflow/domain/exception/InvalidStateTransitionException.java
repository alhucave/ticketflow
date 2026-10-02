package com.ticketflow.domain.exception;

import com.ticketflow.domain.model.TicketStatus;

/** Thrown when a ticket is asked to move between two states that are not connected. */
public class InvalidStateTransitionException extends RuntimeException {

    private final TicketStatus from;
    private final TicketStatus to;

    public InvalidStateTransitionException(TicketStatus from, TicketStatus to) {
        super("Invalid ticket state transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public TicketStatus from() {
        return from;
    }

    public TicketStatus to() {
        return to;
    }
}
