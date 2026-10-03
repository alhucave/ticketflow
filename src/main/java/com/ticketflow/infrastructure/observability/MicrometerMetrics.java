package com.ticketflow.infrastructure.observability;

import com.ticketflow.usecase.BusinessMetrics;
import com.ticketflow.usecase.ReleaseExpiredReservationsUseCase.Summary;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Micrometer implementation of both metric ports. Every meter is registered up front from the fixed enums
 * (so each series exists at 0 from startup, which alert rules need) and no meter is ever created from
 * runtime data: the set of series is bounded by construction. Names use the {@code ticketflow.} prefix; see
 * docs/observability.md for the catalogue.
 *
 * <p>Naming note: orders reserved are counted as {@code ticketflow.orders.placed}, not {@code ...created}: the
 * Prometheus client reserves the {@code _created} suffix and would expose {@code ticketflow_orders_created} as
 * {@code ticketflow_orders_total}, a misleading name.
 */
public final class MicrometerMetrics implements BusinessMetrics, OperationalMetrics {

    public static final String PREFIX = "ticketflow.";

    private final Counter ordersPlaced;
    private final Counter purchasesReplayed;
    private final Counter ordersSold;
    private final Counter complimentaryIssued;
    private final Counter publishRetries;
    private final Counter dependencyUnavailable;
    private final Counter sweepsFailed;
    private final Counter sweepsOk;
    private final Map<PurchaseRejection, Counter> rejections = new EnumMap<>(PurchaseRejection.class);
    private final Map<ReleaseReason, Counter> releases = new EnumMap<>(ReleaseReason.class);
    private final Map<ProcessOutcome, Counter> processed = new EnumMap<>(ProcessOutcome.class);
    private final Map<ConsumerOutcome, Counter> consumerMessages = new EnumMap<>(ConsumerOutcome.class);
    private final Map<PublishOutcome, Counter> publishes = new EnumMap<>(PublishOutcome.class);
    private final Map<Limiter, Counter> rateLimits = new EnumMap<>(Limiter.class);
    private final Map<ConflictType, Map<Operation, Counter>> conflicts = new EnumMap<>(ConflictType.class);
    private final Counter sweepReleased;
    private final Counter sweepSkipped;
    private final Counter sweepFailedOrders;
    private final Counter refreshOk;
    private final Counter refreshError;
    private final Timer consumerDuration;
    private final Timer sweepDuration;

    public MicrometerMetrics(MeterRegistry registry) {
        ordersPlaced = counter(registry, "orders.placed", "Orders placed: reservation made (new orders, not replays)");
        purchasesReplayed = counter(registry, "purchases.replayed",
                "Purchase requests answered from an existing order (idempotent replays)");
        ordersSold = counter(registry, "orders.sold", "Orders whose sale was completed");
        complimentaryIssued = counter(registry, "complimentary.issued", "Complimentary issuances");
        publishRetries = counter(registry, "queue.publish.retries", "Retries of transient SQS publish errors");
        dependencyUnavailable = counter(registry, "dependency.unavailable",
                "Requests answered 503 because DynamoDB or SQS was unavailable");
        for (PurchaseRejection reason : PurchaseRejection.values()) {
            rejections.put(reason, Counter.builder(PREFIX + "purchases.rejected")
                    .description("Purchase requests refused, by reason").tag("reason", reason.tag())
                    .register(registry));
        }
        for (ReleaseReason reason : ReleaseReason.values()) {
            releases.put(reason, Counter.builder(PREFIX + "orders.released")
                    .description("Reservations returned to AVAILABLE, by reason").tag("reason", reason.tag())
                    .register(registry));
        }
        for (ProcessOutcome outcome : ProcessOutcome.values()) {
            processed.put(outcome, Counter.builder(PREFIX + "orders.processed")
                    .description("Order messages processed, by outcome").tag("outcome", outcome.tag())
                    .register(registry));
        }
        for (ConsumerOutcome outcome : ConsumerOutcome.values()) {
            consumerMessages.put(outcome, Counter.builder(PREFIX + "consumer.messages")
                    .description("Messages received by the SQS consumer, by outcome").tag("outcome", outcome.tag())
                    .register(registry));
        }
        for (PublishOutcome outcome : PublishOutcome.values()) {
            publishes.put(outcome, Counter.builder(PREFIX + "queue.publish")
                    .description("Order messages published to SQS, by outcome").tag("outcome", outcome.tag())
                    .register(registry));
        }
        for (Limiter limiter : Limiter.values()) {
            rateLimits.put(limiter, Counter.builder(PREFIX + "ratelimit.rejections")
                    .description("Requests refused with 429, by limiter").tag("limiter", limiter.tag())
                    .register(registry));
        }
        for (ConflictType type : ConflictType.values()) {
            Map<Operation, Counter> byOperation = new EnumMap<>(Operation.class);
            for (Operation operation : Operation.values()) {
                byOperation.put(operation, Counter.builder(PREFIX + "conflicts")
                        .description("Conditional writes refused, by guard and operation")
                        .tag("type", type.tag()).tag("operation", operation.tag()).register(registry));
            }
            conflicts.put(type, byOperation);
        }
        sweepReleased = sweepOrders(registry, "released");
        sweepSkipped = sweepOrders(registry, "skipped_conflict");
        sweepFailedOrders = sweepOrders(registry, "failed");
        sweepsOk = Counter.builder(PREFIX + "expiration.sweeps").description("Expiration sweeps, by result")
                .tag("result", "ok").register(registry);
        sweepsFailed = Counter.builder(PREFIX + "expiration.sweeps").description("Expiration sweeps, by result")
                .tag("result", "error").register(registry);
        refreshOk = Counter.builder(PREFIX + "queue.stats.refreshes")
                .description("Queue depth refreshes (GetQueueAttributes), by result").tag("result", "ok")
                .register(registry);
        refreshError = Counter.builder(PREFIX + "queue.stats.refreshes")
                .description("Queue depth refreshes (GetQueueAttributes), by result").tag("result", "error")
                .register(registry);
        consumerDuration = Timer.builder(PREFIX + "consumer.processing.duration")
                .description("Time the SQS consumer spent on one message").publishPercentileHistogram()
                .register(registry);
        sweepDuration = Timer.builder(PREFIX + "expiration.sweep.duration")
                .description("Duration of one expiration sweep").register(registry);
    }

    private static Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(PREFIX + name).description(description).register(registry);
    }

    private static Counter sweepOrders(MeterRegistry registry, String result) {
        return Counter.builder(PREFIX + "expiration.sweep.orders")
                .description("Orders examined by the expiration sweep, by result").tag("result", result)
                .register(registry);
    }

    @Override
    public void orderPlaced() {
        ordersPlaced.increment();
    }

    @Override
    public void purchaseReplayed() {
        purchasesReplayed.increment();
    }

    @Override
    public void purchaseRejected(PurchaseRejection reason) {
        rejections.get(reason).increment();
    }

    @Override
    public void orderSold() {
        ordersSold.increment();
    }

    @Override
    public void orderReleased(ReleaseReason reason) {
        releases.get(reason).increment();
    }

    @Override
    public void complimentaryIssued() {
        complimentaryIssued.increment();
    }

    @Override
    public void orderProcessed(ProcessOutcome outcome) {
        processed.get(outcome).increment();
    }

    @Override
    public void conflict(ConflictType type, Operation operation) {
        conflicts.get(type).get(operation).increment();
    }

    @Override
    public void consumerMessage(ConsumerOutcome outcome, Duration elapsed) {
        consumerMessages.get(outcome).increment();
        if (outcome != ConsumerOutcome.POISON) {
            consumerDuration.record(elapsed);
        }
    }

    @Override
    public void publishCompleted(PublishOutcome outcome) {
        publishes.get(outcome).increment();
    }

    @Override
    public void publishRetried() {
        publishRetries.increment();
    }

    @Override
    public void rateLimitRejected(Limiter limiter) {
        rateLimits.get(limiter).increment();
    }

    @Override
    public void dependencyUnavailable() {
        dependencyUnavailable.increment();
    }

    @Override
    public void expirationSweepCompleted(Summary summary, Duration elapsed) {
        sweepsOk.increment();
        sweepReleased.increment(summary.released());
        sweepSkipped.increment(summary.skippedConflicts());
        sweepFailedOrders.increment(summary.failed());
        sweepDuration.record(elapsed);
    }

    @Override
    public void expirationSweepFailed(Duration elapsed) {
        sweepsFailed.increment();
        sweepDuration.record(elapsed);
    }

    @Override
    public void queueStatsRefreshed(boolean success) {
        (success ? refreshOk : refreshError).increment();
    }
}
