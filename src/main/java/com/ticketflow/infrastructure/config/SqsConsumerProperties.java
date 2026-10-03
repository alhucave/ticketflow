package com.ticketflow.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Order consumer settings bound from {@code ticketflow.sqs.consumer.*} (env:
 * {@code TICKETFLOW_SQS_CONSUMER_*}).
 *
 * <ul>
 *   <li>{@code enabled}: the consumer only starts when {@code true} (default {@code false}, so
 *       contexts without SQS never poll);</li>
 *   <li>{@code batchSize}: messages per {@code ReceiveMessage} (1..10);</li>
 *   <li>{@code waitTime}: long-polling wait (1..20 s);</li>
 *   <li>{@code visibilityTimeout}: how long a received message stays hidden; must exceed the time
 *       needed to process a batch;</li>
 *   <li>{@code concurrency}: maximum messages processed in parallel;</li>
 *   <li>{@code shutdownTimeout}: how long a graceful stop waits for in-flight messages;</li>
 *   <li>{@code minBackoff}/{@code maxBackoff}: capped exponential backoff after a failed
 *       {@code ReceiveMessage}; the loop retries forever.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "ticketflow.sqs.consumer")
public record SqsConsumerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("10") int batchSize,
        @DefaultValue("20s") Duration waitTime,
        @DefaultValue("30s") Duration visibilityTimeout,
        @DefaultValue("4") int concurrency,
        @DefaultValue("25s") Duration shutdownTimeout,
        @DefaultValue("1s") Duration minBackoff,
        @DefaultValue("30s") Duration maxBackoff) {

    public SqsConsumerProperties {
        require(batchSize >= 1 && batchSize <= 10, "batch-size must be between 1 and 10");
        require(waitTime.toSeconds() >= 1 && waitTime.toSeconds() <= 20, "wait-time must be between 1s and 20s");
        require(visibilityTimeout.toSeconds() >= 1 && visibilityTimeout.toHours() <= 12,
                "visibility-timeout must be between 1s and 12h");
        require(concurrency >= 1, "concurrency must be at least 1");
        require(!shutdownTimeout.isNegative(), "shutdown-timeout must not be negative");
        require(!minBackoff.isNegative() && !minBackoff.isZero(), "min-backoff must be positive");
        require(maxBackoff.compareTo(minBackoff) >= 0, "max-backoff must not be lower than min-backoff");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("ticketflow.sqs.consumer." + message);
        }
    }
}
