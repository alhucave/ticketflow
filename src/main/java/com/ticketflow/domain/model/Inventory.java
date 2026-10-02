package com.ticketflow.domain.model;

/**
 * Per-event ticket counters. Invariant:
 * available + reserved + pendingConfirmation + sold + complimentary = capacity.
 * {@code version} is the optimistic-locking token used by conditional writes.
 */
public record Inventory(
        EventId eventId,
        int available,
        int reserved,
        int pendingConfirmation,
        int sold,
        int complimentary,
        int capacity,
        long version) {

    public Inventory {
        if (eventId == null) {
            throw new IllegalArgumentException("Inventory eventId must not be null");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("Inventory capacity must be greater than 0: " + capacity);
        }
        requireNonNegative("available", available);
        requireNonNegative("reserved", reserved);
        requireNonNegative("pendingConfirmation", pendingConfirmation);
        requireNonNegative("sold", sold);
        requireNonNegative("complimentary", complimentary);
        if (version < 0) {
            throw new IllegalArgumentException("Inventory version must not be negative: " + version);
        }
        long total = (long) available + reserved + pendingConfirmation + sold + complimentary;
        if (total != capacity) {
            throw new IllegalArgumentException(
                    "Inventory counters must add up to capacity: sum=" + total + ", capacity=" + capacity);
        }
    }

    /** Brand-new inventory: every ticket available, version 0. */
    public static Inventory initial(EventId eventId, int capacity) {
        return new Inventory(eventId, capacity, 0, 0, 0, 0, capacity, 0);
    }

    private static void requireNonNegative(String field, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Inventory " + field + " must not be negative: " + value);
        }
    }
}
