package com.ticketflow.infrastructure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Body of {@code POST /events}. Only shape constraints live here; business rules (such as the date
 * being in the future) are enforced by {@code CreateEventUseCase}.
 *
 * @param startsAt ISO-8601 instant, e.g. {@code 2030-01-01T20:00:00Z}
 */
public record CreateEventRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull Instant startsAt,
        @NotBlank @Size(max = 200) String venue,
        @NotNull @Min(1) @Max(MAX_CAPACITY) Integer capacity) {

    public static final int MAX_CAPACITY = 1_000_000;
}
