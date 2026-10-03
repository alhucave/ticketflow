package com.ticketflow.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.infrastructure.observability.OperationalMetrics.ConsumerOutcome;
import com.ticketflow.infrastructure.observability.OperationalMetrics.Limiter;
import com.ticketflow.infrastructure.observability.OperationalMetrics.PublishOutcome;
import com.ticketflow.infrastructure.observability.OperationalMetrics.QueueKind;
import com.ticketflow.usecase.BusinessMetrics.ConflictType;
import com.ticketflow.usecase.BusinessMetrics.Operation;
import com.ticketflow.usecase.BusinessMetrics.ProcessOutcome;
import com.ticketflow.usecase.BusinessMetrics.PurchaseRejection;
import com.ticketflow.usecase.BusinessMetrics.ReleaseReason;
import com.ticketflow.usecase.ReleaseExpiredReservationsUseCase.Summary;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MicrometerMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerMetrics metrics = new MicrometerMetrics(registry);

    private double count(String name, String... tags) {
        return registry.get("ticketflow." + name).tags(tags).counter().count();
    }

    @Test
    void orderPlaced_increments() {
        metrics.orderPlaced();
        metrics.orderPlaced();
        assertThat(count("orders.placed")).isEqualTo(2);
        assertThat(count("orders.sold")).isZero();
    }

    @Test
    void simpleCounters_incrementOnlyTheirOwn() {
        metrics.purchaseReplayed();
        metrics.orderSold();
        metrics.complimentaryIssued();
        metrics.publishRetried();
        metrics.dependencyUnavailable();

        assertThat(count("purchases.replayed")).isEqualTo(1);
        assertThat(count("orders.sold")).isEqualTo(1);
        assertThat(count("complimentary.issued")).isEqualTo(1);
        assertThat(count("queue.publish.retries")).isEqualTo(1);
        assertThat(count("dependency.unavailable")).isEqualTo(1);
        assertThat(count("orders.placed")).isZero();
    }

    @Test
    void taggedCounters_incrementTheMatchingSeriesOnly() {
        metrics.purchaseRejected(PurchaseRejection.INSUFFICIENT_INVENTORY);
        metrics.orderReleased(ReleaseReason.PUBLISH_FAILED);
        metrics.orderProcessed(ProcessOutcome.RELEASED_AS_EXPIRED);
        metrics.conflict(ConflictType.ORDER_STATUS, Operation.EXPIRATION_SWEEP);
        metrics.publishCompleted(PublishOutcome.FAILED);
        metrics.rateLimitRejected(Limiter.ADMIN_FAILURE);

        assertThat(count("purchases.rejected", "reason", "insufficient_inventory")).isEqualTo(1);
        assertThat(count("purchases.rejected", "reason", "enqueue_failed")).isZero();
        assertThat(count("orders.released", "reason", "publish_failed")).isEqualTo(1);
        assertThat(count("orders.released", "reason", "expired")).isZero();
        assertThat(count("orders.processed", "outcome", "released_as_expired")).isEqualTo(1);
        assertThat(count("orders.processed", "outcome", "sold")).isZero();
        assertThat(count("conflicts", "type", "order_status", "operation", "expiration_sweep")).isEqualTo(1);
        assertThat(count("conflicts", "type", "inventory_insufficient", "operation", "purchase")).isZero();
        assertThat(count("queue.publish", "outcome", "failed")).isEqualTo(1);
        assertThat(count("queue.publish", "outcome", "ok")).isZero();
        assertThat(count("ratelimit.rejections", "limiter", "admin_failure")).isEqualTo(1);
        assertThat(count("ratelimit.rejections", "limiter", "write")).isZero();
    }

    @Test
    void consumerMessage_countsOutcomeAndTimesProcessedAndFailedButNotPoison() {
        metrics.consumerMessage(ConsumerOutcome.PROCESSED, Duration.ofMillis(20));
        metrics.consumerMessage(ConsumerOutcome.FAILED, Duration.ofMillis(40));
        metrics.consumerMessage(ConsumerOutcome.POISON, Duration.ofMillis(1));

        assertThat(count("consumer.messages", "outcome", "processed")).isEqualTo(1);
        assertThat(count("consumer.messages", "outcome", "failed")).isEqualTo(1);
        assertThat(count("consumer.messages", "outcome", "poison")).isEqualTo(1);
        var timer = registry.get("ticketflow.consumer.processing.duration").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(60);
    }

    @Test
    void expirationSweep_recordsPerOrderResultsDurationAndSweepOutcome() {
        metrics.expirationSweepCompleted(new Summary(10, 6, 3, 1), Duration.ofMillis(250));
        metrics.expirationSweepFailed(Duration.ofMillis(10));

        assertThat(count("expiration.sweep.orders", "result", "released")).isEqualTo(6);
        assertThat(count("expiration.sweep.orders", "result", "skipped_conflict")).isEqualTo(3);
        assertThat(count("expiration.sweep.orders", "result", "failed")).isEqualTo(1);
        assertThat(count("expiration.sweeps", "result", "ok")).isEqualTo(1);
        assertThat(count("expiration.sweeps", "result", "error")).isEqualTo(1);
        assertThat(registry.get("ticketflow.expiration.sweep.duration").timer().count()).isEqualTo(2);
    }

    @Test
    void queueStatsRefreshed_countsOkAndError() {
        metrics.queueStatsRefreshed(true);
        metrics.queueStatsRefreshed(true);
        metrics.queueStatsRefreshed(false);

        assertThat(count("queue.stats.refreshes", "result", "ok")).isEqualTo(2);
        assertThat(count("queue.stats.refreshes", "result", "error")).isEqualTo(1);
    }

    @Test
    void seriesAreRegisteredUpFrontAtZero() {
        assertThat(registry.getMeters()).isNotEmpty().allSatisfy(meter -> {
            assertThat(meter.getId().getName()).startsWith("ticketflow.");
            if (meter instanceof io.micrometer.core.instrument.Counter counter) {
                assertThat(counter.count()).isZero();
            }
        });
    }

    // ---- cardinality rule ----

    private static final Set<String> ALLOWED_TAG_KEYS =
            Set.of("reason", "outcome", "type", "operation", "limiter", "result", "queue", "state");

    private static Set<String> tagValues(Stream<String> tags) {
        return tags.collect(Collectors.toSet());
    }

    private static Set<String> allowedTagValues() {
        return tagValues(Stream.of(
                Stream.of(PurchaseRejection.values()).map(PurchaseRejection::tag),
                Stream.of(ReleaseReason.values()).map(ReleaseReason::tag),
                Stream.of(ProcessOutcome.values()).map(ProcessOutcome::tag),
                Stream.of(ConflictType.values()).map(ConflictType::tag),
                Stream.of(Operation.values()).map(Operation::tag),
                Stream.of(ConsumerOutcome.values()).map(ConsumerOutcome::tag),
                Stream.of(PublishOutcome.values()).map(PublishOutcome::tag),
                Stream.of(Limiter.values()).map(Limiter::tag),
                Stream.of(QueueKind.values()).map(QueueKind::tag),
                Stream.of("released", "skipped_conflict", "failed", "ok", "error", "visible", "in_flight")
        ).flatMap(s -> s));
    }

    private void exerciseEverything() {
        metrics.orderPlaced();
        metrics.purchaseReplayed();
        metrics.orderSold();
        metrics.complimentaryIssued();
        for (PurchaseRejection reason : PurchaseRejection.values()) {
            metrics.purchaseRejected(reason);
        }
        for (ReleaseReason reason : ReleaseReason.values()) {
            metrics.orderReleased(reason);
        }
        for (ProcessOutcome outcome : ProcessOutcome.values()) {
            metrics.orderProcessed(outcome);
        }
        for (ConflictType type : ConflictType.values()) {
            for (Operation operation : Operation.values()) {
                metrics.conflict(type, operation);
            }
        }
        for (ConsumerOutcome outcome : ConsumerOutcome.values()) {
            metrics.consumerMessage(outcome, Duration.ofMillis(1));
        }
        for (PublishOutcome outcome : PublishOutcome.values()) {
            metrics.publishCompleted(outcome);
        }
        for (Limiter limiter : Limiter.values()) {
            metrics.rateLimitRejected(limiter);
        }
        metrics.publishRetried();
        metrics.dependencyUnavailable();
        metrics.expirationSweepCompleted(new Summary(3, 1, 1, 1), Duration.ofMillis(5));
        metrics.expirationSweepFailed(Duration.ofMillis(5));
        metrics.queueStatsRefreshed(true);
        metrics.queueStatsRefreshed(false);
    }

    @Test
    void cardinality_everyTagKeyAndValueComesFromAFixedSet() {
        exerciseEverything();

        Set<String> values = allowedTagValues();
        for (Meter meter : registry.getMeters()) {
            for (var tag : meter.getId().getTags()) {
                assertThat(ALLOWED_TAG_KEYS).as(meter.getId().getName()).contains(tag.getKey());
                assertThat(values).as(meter.getId().getName() + "." + tag.getKey()).contains(tag.getValue());
            }
        }
    }

    @Test
    void cardinality_usingTheMetricsNeverCreatesNewSeries() {
        List<Meter.Id> before = registry.getMeters().stream().map(Meter::getId).toList();

        exerciseEverything();
        exerciseEverything();

        assertThat(registry.getMeters().stream().map(Meter::getId).toList()).containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void cardinality_noMeterIsTaggedWithIdentifiersOrUserInput() {
        exerciseEverything();

        Set<String> forbidden = Set.of("orderId", "order_id", "eventId", "event_id", "idempotencyKey", "client",
                "address", "ip", "user", "key", "correlationId", "path", "uri");
        assertThat(registry.getMeters().stream().flatMap(m -> m.getId().getTags().stream())
                .map(io.micrometer.core.instrument.Tag::getKey)).doesNotContainAnyElementsOf(forbidden);
    }

    @Test
    void nameAndSeriesBudget_staysSmall() {
        assertThat(registry.getMeters()).hasSizeLessThan(60);
    }

    @Test
    void noopMetrics_everyMethod_doesNothing() {
        OperationalMetrics noop = OperationalMetrics.NOOP;
        noop.consumerMessage(ConsumerOutcome.PROCESSED, Duration.ZERO);
        noop.publishCompleted(PublishOutcome.OK);
        noop.publishRetried();
        noop.rateLimitRejected(Limiter.WRITE);
        noop.dependencyUnavailable();
        noop.expirationSweepCompleted(new Summary(0, 0, 0, 0), Duration.ZERO);
        noop.expirationSweepFailed(Duration.ZERO);
        noop.queueStatsRefreshed(true);
    }

    @Test
    void enumTags_areLowercaseSnakeCase() {
        List<String> tags = allowedTagValues().stream().toList();
        assertThat(tags).allSatisfy(tag -> assertThat(tag).matches("[a-z_]+"));
    }
}
