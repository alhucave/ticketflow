package com.ticketflow.domain.model;

import java.time.Instant;

/** A ticketed event with a fixed capacity. */
public record Event(EventId id, String name, Instant startsAt, String venue, int capacity) {

    public Event {
        if (id == null) {
            throw new IllegalArgumentException("Event id must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Event name must not be blank");
        }
        if (startsAt == null) {
            throw new IllegalArgumentException("Event startsAt must not be null");
        }
        if (venue == null || venue.isBlank()) {
            throw new IllegalArgumentException("Event venue must not be blank");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("Event capacity must be greater than 0: " + capacity);
        }
    }
}
