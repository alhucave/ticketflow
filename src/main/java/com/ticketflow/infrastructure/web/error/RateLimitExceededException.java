package com.ticketflow.infrastructure.web.error;

import java.time.Duration;

/**
 * A client exceeded its request budget. Thrown by web-level protections (for example the rate-limit
 * filter) and rendered as {@code 429} with a {@code Retry-After} header when the wait is known.
 */
public class RateLimitExceededException extends RuntimeException {

    private final transient Duration retryAfter;

    /** Wait time is unknown: no {@code Retry-After} header is sent. */
    public RateLimitExceededException() {
        this(null);
    }

    /** @param retryAfter how long the client should wait, or {@code null} when unknown */
    public RateLimitExceededException(Duration retryAfter) {
        super("Rate limit exceeded");
        this.retryAfter = retryAfter;
    }

    /** @return the suggested wait, or {@code null} when unknown */
    public Duration retryAfter() {
        return retryAfter;
    }
}
