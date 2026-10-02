package com.ticketflow.usecase;

import java.time.Instant;

/** Input of {@link CreateEventUseCase}. */
public record CreateEventCommand(String name, Instant startsAt, String venue, int capacity) {}
