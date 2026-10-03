package com.ticketflow.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** Body of {@code GET /orders/{id}}; {@code reservationExpiresAt} only while a reservation is live. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderStatusResponse(
        String orderId,
        String eventId,
        int quantity,
        String status,
        Instant reservationExpiresAt,
        Instant createdAt) {}
