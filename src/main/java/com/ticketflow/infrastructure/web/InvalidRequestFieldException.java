package com.ticketflow.infrastructure.web;

/**
 * A request field violates a rule that is enforced by the controller because it is configurable (for
 * example the maximum quantity per order). Rendered like a bean-validation failure: {@code 400} with one
 * violation. The message is fixed text built by the controller and never contains client input.
 */
public class InvalidRequestFieldException extends RuntimeException {

    private final String field;

    public InvalidRequestFieldException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
