package com.ticketflow.infrastructure.web;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.OrderId;
import java.util.regex.Pattern;

/**
 * Validates the id taken from a URL path before it reaches a use case or a log line or an error body:
 * 1 to 64 characters of {@code [A-Za-z0-9._-]} (UUIDs and the ids this service generates fit). Anything
 * else cannot exist and is answered with a generic 404 ({@link InvalidPathIdException}), never echoed.
 */
final class PathIds {

    static final int MAX_LENGTH = 64;
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1," + MAX_LENGTH + "}");

    private PathIds() {}

    static EventId eventId(String raw) {
        return new EventId(validate(raw));
    }

    static OrderId orderId(String raw) {
        return new OrderId(validate(raw));
    }

    private static String validate(String raw) {
        if (raw == null || !VALID.matcher(raw).matches()) {
            throw new InvalidPathIdException();
        }
        return raw;
    }
}
