package com.ticketflow.domain.exception;

import com.ticketflow.domain.model.EventId;

/** An optimistic-locking conditional write failed because the version changed. */
public class ConcurrentModificationException extends RuntimeException {

    private final EventId eventId;
    private final long expectedVersion;

    public ConcurrentModificationException(EventId eventId, long expectedVersion) {
        super("Concurrent modification of inventory for event " + eventId.value()
                + " (expected version " + expectedVersion + ")");
        this.eventId = eventId;
        this.expectedVersion = expectedVersion;
    }

    public EventId eventId() {
        return eventId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }
}
