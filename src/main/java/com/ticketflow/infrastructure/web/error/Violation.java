package com.ticketflow.infrastructure.web.error;

/** A single field-level validation failure, listed under {@code violations} in a problem response. */
public record Violation(String field, String message) {}
