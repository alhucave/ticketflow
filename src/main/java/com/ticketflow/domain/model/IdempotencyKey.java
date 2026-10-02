package com.ticketflow.domain.model;

/** Client-supplied key that makes order creation safe to retry. */
public record IdempotencyKey(String value) {

    public static final int MAX_LENGTH = 128;

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("IdempotencyKey must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("IdempotencyKey must be at most " + MAX_LENGTH + " characters");
        }
    }
}
