package com.ticketflow.infrastructure.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /orders}. The upper bound of {@code quantity} is configurable
 * ({@code ticketflow.orders.max-quantity}, default 10) and enforced by {@link OrderController}: it
 * bounds how much inventory a single request can hold in reservation.
 */
public record PurchaseRequest(
        @NotBlank String eventId,
        @NotNull @Min(1) Integer quantity) {}
