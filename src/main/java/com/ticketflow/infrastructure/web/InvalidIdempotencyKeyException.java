package com.ticketflow.infrastructure.web;

/** The {@code Idempotency-Key} header is missing or malformed; the message is safe to show to clients. */
public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException(String message) {
        super(message);
    }
}
