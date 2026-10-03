package com.ticketflow.infrastructure.observability;

import com.ticketflow.usecase.ReleaseExpiredReservationsUseCase;
import java.time.Duration;

/**
 * Metrics of the infrastructure adapters (SQS consumer and publisher, expiration job, rate limiting, queue
 * depth polling, dependency outages). Adapters receive {@link #NOOP} by default.
 *
 * <p><b>Cardinality rule.</b> As in {@code BusinessMetrics}, tag values come from the fixed enums below only;
 * no method accepts an id, address or any user input.
 */
public interface OperationalMetrics {

    OperationalMetrics NOOP = new OperationalMetrics() { };

    /** What happened to one received queue message. */
    enum ConsumerOutcome {
        /** The use case finished and the message was deleted. */
        PROCESSED("processed"),
        /** The use case or the delete failed: the message stays and SQS redelivers it. */
        FAILED("failed"),
        /** Invalid body (not JSON, wrong version, no orderId): never processed, SQS redrives it to the DLQ. */
        POISON("poison");

        private final String tag;

        ConsumerOutcome(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    enum PublishOutcome {
        OK("ok"),
        FAILED("failed");

        private final String tag;

        PublishOutcome(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /** Which limiter refused a request. */
    enum Limiter {
        /** Per-client budget of the write routes. */
        WRITE("write"),
        /** Per-client lockout after wrong or missing admin keys. */
        ADMIN_FAILURE("admin_failure");

        private final String tag;

        Limiter(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /** Orders queue or its dead-letter queue. */
    enum QueueKind {
        ORDERS("orders"),
        DLQ("dlq");

        private final String tag;

        QueueKind(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /** One handled queue message with the time it took (receive-to-acknowledge for processed ones). */
    default void consumerMessage(ConsumerOutcome outcome, Duration elapsed) { }

    default void publishCompleted(PublishOutcome outcome) { }

    /** One retry of a transient publish error. */
    default void publishRetried() { }

    default void rateLimitRejected(Limiter limiter) { }

    /** A request was answered 503 because a dependency is unavailable. */
    default void dependencyUnavailable() { }

    /** A finished expiration sweep (the per-order counters come from the summary). */
    default void expirationSweepCompleted(ReleaseExpiredReservationsUseCase.Summary summary, Duration elapsed) { }

    /** A sweep that failed as a whole (the expired-order query failed). */
    default void expirationSweepFailed(Duration elapsed) { }

    /** Result of one {@code GetQueueAttributes} refresh: whether it worked. */
    default void queueStatsRefreshed(boolean success) { }
}
