package com.ticketflow.infrastructure.web.ratelimit;

import java.time.Duration;

/**
 * Classic token bucket: holds up to {@code capacity} tokens and refills continuously at
 * {@code refillPerSecond}. Time is passed in (monotonic nanoseconds) so tests control it. Thread-safe.
 */
final class TokenBucket {

    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    private final double capacity;
    private final double refillPerNano;
    private double tokens;
    private long lastNanos;

    TokenBucket(double capacity, double refillPerSecond, long nowNanos) {
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / NANOS_PER_SECOND;
        this.tokens = capacity;
        this.lastNanos = nowNanos;
    }

    /** Takes one token if available. @return {@link Duration#ZERO} when taken, otherwise the wait for the next one */
    synchronized Duration tryConsume(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1) {
            tokens -= 1;
            return Duration.ZERO;
        }
        return waitForToken();
    }

    /** Looks without consuming. @return {@link Duration#ZERO} when a token is available, otherwise the wait */
    synchronized Duration waitTime(long nowNanos) {
        refill(nowNanos);
        return tokens >= 1 ? Duration.ZERO : waitForToken();
    }

    /** Takes one token unconditionally (never below zero): used to charge a failed attempt. */
    synchronized void consume(long nowNanos) {
        refill(nowNanos);
        tokens = Math.max(0, tokens - 1);
    }

    /** Time needed to refill from empty to full. */
    static Duration timeToFull(double capacity, double refillPerSecond) {
        return Duration.ofNanos((long) Math.ceil(capacity / refillPerSecond * NANOS_PER_SECOND));
    }

    private Duration waitForToken() {
        return Duration.ofNanos((long) Math.ceil((1 - tokens) / refillPerNano));
    }

    private void refill(long nowNanos) {
        long elapsed = Math.max(0, nowNanos - lastNanos);
        lastNanos = nowNanos;
        tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
    }
}
