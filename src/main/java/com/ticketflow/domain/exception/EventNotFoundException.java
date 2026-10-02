package com.ticketflow.domain.exception;

import com.ticketflow.domain.model.EventId;

/** The requested event does not exist. */
public class EventNotFoundException extends RuntimeException {

    private final EventId eventId;

    public EventNotFoundException(EventId eventId) {
        super("Event not found: " + eventId.value());
        this.eventId = eventId;
    }

    public EventId eventId() {
        return eventId;
    }
}
