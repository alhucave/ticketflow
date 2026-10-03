package com.ticketflow.infrastructure.web;

import java.time.Instant;

/** An event as returned by {@code POST /events} and {@code GET /events}. */
public record EventResponse(String id, String name, Instant startsAt, String venue, int capacity) {}
