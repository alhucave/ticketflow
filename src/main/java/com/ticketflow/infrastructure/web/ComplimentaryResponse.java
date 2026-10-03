package com.ticketflow.infrastructure.web;

/** Body of the {@code 201} answer of {@code POST /events/{id}/complimentary}; a replay returns the same body. */
public record ComplimentaryResponse(String orderId, String eventId, int quantity, String status) {}
