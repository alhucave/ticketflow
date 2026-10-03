package com.ticketflow.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rate-limit settings bound from {@code ticketflow.rate-limit.*} (env: {@code TICKETFLOW_RATE_LIMIT_*}).
 *
 * <ul>
 *   <li>{@code enabled}: on by default;</li>
 *   <li>{@code capacity} / {@code refillPerSecond}: burst size and sustained rate of the budget each
 *       client has for write routes ({@code POST /orders}, {@code POST /events},
 *       {@code POST /events/{id}/complimentary});</li>
 *   <li>{@code adminFailureCapacity} / {@code adminFailureRefillPerSecond}: tighter budget of FAILED
 *       {@code X-Admin-Key} attempts per client (brute-force protection); once spent, even the right
 *       key is refused until a token refills;</li>
 *   <li>{@code maxClients}: bound of tracked clients (memory) and {@code idleTtl}: when an idle client
 *       is forgotten;</li>
 *   <li>{@code trustForwardedFor}: identify clients by the last {@code X-Forwarded-For} entry instead of
 *       the socket address. Only enable it behind exactly one trusted reverse proxy that appends the
 *       address of its peer; otherwise a client could pick its own identity.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "ticketflow.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20") int capacity,
        @DefaultValue("1") double refillPerSecond,
        @DefaultValue("5") int adminFailureCapacity,
        @DefaultValue("0.05") double adminFailureRefillPerSecond,
        @DefaultValue("10000") long maxClients,
        @DefaultValue("15m") Duration idleTtl,
        @DefaultValue("false") boolean trustForwardedFor) {

    public RateLimitProperties {
        require(capacity >= 1, "capacity must be at least 1");
        require(refillPerSecond > 0, "refill-per-second must be positive");
        require(adminFailureCapacity >= 1, "admin-failure-capacity must be at least 1");
        require(adminFailureRefillPerSecond > 0, "admin-failure-refill-per-second must be positive");
        require(maxClients >= 1, "max-clients must be at least 1");
        require(!idleTtl.isNegative() && !idleTtl.isZero(), "idle-ttl must be positive");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("ticketflow.rate-limit." + message);
        }
    }
}
