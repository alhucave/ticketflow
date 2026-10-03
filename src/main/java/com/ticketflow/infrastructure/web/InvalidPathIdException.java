package com.ticketflow.infrastructure.web;

/**
 * A path id does not have the shape of any id this service issues. Deliberately carries no message and
 * no input: the answer is a generic {@code 404} that never echoes what the client sent.
 */
public class InvalidPathIdException extends RuntimeException {

    public InvalidPathIdException() {
        super("Invalid path id", null, false, false);
    }
}
