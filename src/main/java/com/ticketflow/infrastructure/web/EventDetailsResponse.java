package com.ticketflow.infrastructure.web;

import java.time.Instant;

/** An event with its inventory snapshot, as returned by {@code GET /events/{id}}. */
public record EventDetailsResponse(
        String id, String name, Instant startsAt, String venue, int capacity, InventoryResponse inventory) {}
