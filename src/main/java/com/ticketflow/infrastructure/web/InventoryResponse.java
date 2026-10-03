package com.ticketflow.infrastructure.web;

/**
 * Ticket inventory snapshot. The counters add up to the event capacity. The optimistic-locking
 * version is internal and deliberately not exposed.
 */
public record InventoryResponse(int available, int reserved, int pendingConfirmation, int sold, int complimentary) {}
