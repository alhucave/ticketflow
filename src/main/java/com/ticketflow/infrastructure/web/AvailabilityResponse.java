package com.ticketflow.infrastructure.web;

/**
 * Availability snapshot of an event. {@code available} excludes reserved and pending-confirmation
 * tickets, which are reported separately; only {@code sold} counts as sales.
 */
public record AvailabilityResponse(
        int available, int reserved, int pendingConfirmation, int sold, int complimentary, int capacity) {}
