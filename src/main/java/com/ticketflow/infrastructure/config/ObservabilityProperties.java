package com.ticketflow.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Observability settings bound from {@code ticketflow.observability.*} (env:
 * {@code TICKETFLOW_OBSERVABILITY_*}).
 *
 * <ul>
 *   <li>{@code queueMetrics.enabled}: poll the orders queue and its DLQ for the depth gauges (default
 *       {@code false}, so contexts without SQS never poll; docker-compose turns it on);</li>
 *   <li>{@code queueMetrics.interval}: time between polls (default 15 s);</li>
 *   <li>{@code queueMetrics.timeout}: a poll that takes longer is abandoned (counted as an error);</li>
 *   <li>{@code health.timeout}: longest a readiness probe of DynamoDB/SQS waits (a slower dependency is DOWN);</li>
 *   <li>{@code health.cacheTtl}: how long a readiness result is reused, so probes never hammer AWS.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "ticketflow.observability")
public record ObservabilityProperties(
        @DefaultValue QueueMetrics queueMetrics,
        @DefaultValue Health health) {

    public record QueueMetrics(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("15s") Duration interval,
            @DefaultValue("5s") Duration timeout) {

        public QueueMetrics {
            require(interval.toMillis() >= 1000, "queue-metrics.interval must be at least 1s");
            require(timeout.toMillis() >= 100, "queue-metrics.timeout must be at least 100ms");
        }
    }

    public record Health(
            @DefaultValue("2s") Duration timeout,
            @DefaultValue("5s") Duration cacheTtl) {

        public Health {
            require(timeout.toMillis() >= 100, "health.timeout must be at least 100ms");
            require(!cacheTtl.isNegative(), "health.cache-ttl must not be negative");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("ticketflow.observability." + message);
        }
    }
}
