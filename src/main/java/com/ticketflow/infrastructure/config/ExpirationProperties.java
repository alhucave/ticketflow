package com.ticketflow.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Reservation-expiration job settings bound from {@code ticketflow.expiration.*} (env:
 * {@code TICKETFLOW_EXPIRATION_*}).
 *
 * <ul>
 *   <li>{@code enabled}: the scheduler runs unless set to {@code false} (default {@code true}, DP-037; an API-only
 *       instance sets it explicitly to {@code false});</li>
 *   <li>{@code interval}: pause between the end of a sweep and the start of the next one;</li>
 *   <li>{@code initialDelay}: wait before the first sweep after startup;</li>
 *   <li>{@code concurrency}: orders released in parallel within a sweep;</li>
 *   <li>{@code maxPerSweep}: upper bound of orders handled per sweep (the rest wait for the next one);</li>
 *   <li>{@code shutdownTimeout}: how long a graceful stop waits for the sweep in progress.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "ticketflow.expiration")
public record ExpirationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT1M") Duration interval,
        @DefaultValue("PT10S") Duration initialDelay,
        @DefaultValue("4") int concurrency,
        @DefaultValue("500") int maxPerSweep,
        @DefaultValue("PT20S") Duration shutdownTimeout) {

    public ExpirationProperties {
        require(!interval.isNegative() && !interval.isZero(), "interval must be positive");
        require(!initialDelay.isNegative(), "initial-delay must not be negative");
        require(concurrency >= 1, "concurrency must be at least 1");
        require(maxPerSweep >= 1, "max-per-sweep must be at least 1");
        require(!shutdownTimeout.isNegative(), "shutdown-timeout must not be negative");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("ticketflow.expiration." + message);
        }
    }
}
