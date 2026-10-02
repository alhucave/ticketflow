package com.ticketflow.domain.model;

import java.util.UUID;

/** Identifier of an event. */
public record EventId(String value) {

    public EventId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("EventId must not be blank");
        }
    }

    public static EventId generate() {
        return new EventId(UUID.randomUUID().toString());
    }
}
