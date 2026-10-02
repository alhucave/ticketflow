package com.ticketflow.domain.exception;

/** The data supplied to create an event violates a business rule. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
