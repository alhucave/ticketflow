package com.ticketflow.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Body of the {@code 202} answer of {@code POST /orders}. A replay of the same key returns the same
 * order, whose {@code status} may have advanced since the first answer. {@code reservationExpiresAt}
 * is omitted once the order is in a state with no live reservation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PurchaseAcceptedResponse(String orderId, String status, Instant reservationExpiresAt) {}
