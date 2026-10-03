package com.ticketflow.infrastructure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /orders}. At most {@link #MAX_QUANTITY} tickets can be bought per order, which
 * bounds how much inventory a single request can hold in reservation.
 */
public record PurchaseRequest(
        @NotBlank String eventId,
        @NotNull @Min(1) @Max(MAX_QUANTITY) Integer quantity) {

    public static final int MAX_QUANTITY = 10;
}
