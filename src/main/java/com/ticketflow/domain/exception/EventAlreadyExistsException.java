package com.ticketflow.domain.exception;

import com.ticketflow.domain.model.EventId;

/** An event with the same id already exists; it is never overwritten. */
public class EventAlreadyExistsException extends RuntimeException {

    private final EventId eventId;

    public EventAlreadyExistsException(EventId eventId) {
        super("Event already exists: " + eventId.value());
        this.eventId = eventId;
    }

    public EventId eventId() {
        return eventId;
    }
}
