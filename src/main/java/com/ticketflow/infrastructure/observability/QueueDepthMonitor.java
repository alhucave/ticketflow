package com.ticketflow.infrastructure.observability;

import com.ticketflow.infrastructure.config.ObservabilityProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the queue-depth gauges of the orders queue and its dead-letter queue fresh by polling
 * {@code GetQueueAttributes} on a timer, never on a request thread: scraping {@code /actuator/prometheus} reads
 * plain in-memory values and does no I/O.
 *
 * <ul>
 *   <li>{@code ticketflow.queue.messages{queue=orders|dlq,state=visible|in_flight}}: approximate counts
 *       (SQS reports them eventually consistent). The DLQ is found through the main queue's redrive policy;
 *       without one the DLQ series stay {@code NaN};</li>
 *   <li>{@code ticketflow.queue.stats.age.seconds}: seconds since the last successful refresh, so a stale gauge is
 *       visible (the values are kept on failure, never reset to zero, which would hide an outage).</li>
 * </ul>
 *
 * <p>Refreshes never overlap ({@code concatMap}); a slow or failing one is bounded by {@code timeout}, counted
 * in {@code ticketflow.queue.stats.refreshes{result=error}} and retried at the next interval. The age of the
 * oldest message is not exposed: {@code GetQueueAttributes} does not report it (SQS only publishes
 * {@code ApproximateAgeOfOldestMessage} to CloudWatch), and deriving it would need to receive messages, which
 * is neither cheap nor safe.
 */
public final class QueueDepthMonitor implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(QueueDepthMonitor.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long UNKNOWN = Long.MIN_VALUE;

    private final SqsAsyncClient client;
    private final Mono<String> ordersQueueUrl;
    private final Function<String, Mono<String>> dlqUrlResolver;
    private final ObservabilityProperties.QueueMetrics properties;
    private final OperationalMetrics metrics;
    private final Scheduler timer;
    private final Clock clock;
    private final Map<OperationalMetrics.QueueKind, AtomicLong> visible = new EnumMap<>(OperationalMetrics.QueueKind.class);
    private final Map<OperationalMetrics.QueueKind, AtomicLong> inFlight = new EnumMap<>(OperationalMetrics.QueueKind.class);
    private volatile Instant lastSuccess;
    private volatile Mono<String> dlqUrl;
    private Disposable subscription;
    private volatile boolean running;

    public QueueDepthMonitor(SqsAsyncClient client, Mono<String> ordersQueueUrl,
                             Function<String, Mono<String>> dlqUrlResolver,
                             ObservabilityProperties.QueueMetrics properties, OperationalMetrics metrics,
                             MeterRegistry registry) {
        this(client, ordersQueueUrl, dlqUrlResolver, properties, metrics, registry, Schedulers.parallel(),
                Clock.systemUTC());
    }

    QueueDepthMonitor(SqsAsyncClient client, Mono<String> ordersQueueUrl,
                      Function<String, Mono<String>> dlqUrlResolver,
                      ObservabilityProperties.QueueMetrics properties, OperationalMetrics metrics,
                      MeterRegistry registry, Scheduler timer, Clock clock) {
        this.client = client;
        this.ordersQueueUrl = ordersQueueUrl;
        this.dlqUrlResolver = dlqUrlResolver;
        this.properties = properties;
        this.metrics = metrics;
        this.timer = timer;
        this.clock = clock;
        for (OperationalMetrics.QueueKind kind : OperationalMetrics.QueueKind.values()) {
            AtomicLong v = new AtomicLong(UNKNOWN);
            AtomicLong f = new AtomicLong(UNKNOWN);
            visible.put(kind, v);
            inFlight.put(kind, f);
            gauge(registry, kind, "visible", v);
            gauge(registry, kind, "in_flight", f);
        }
        Gauge.builder(MicrometerMetrics.PREFIX + "queue.stats.age.seconds", this, QueueDepthMonitor::ageSeconds)
                .description("Seconds since the queue depth gauges were last refreshed successfully")
                .baseUnit("seconds").register(registry);
    }

    private static void gauge(MeterRegistry registry, OperationalMetrics.QueueKind kind, String state,
                              AtomicLong value) {
        Gauge.builder(MicrometerMetrics.PREFIX + "queue.messages", value, v -> v.get() == UNKNOWN ? Double.NaN : v.get())
                .description("Approximate number of messages in the queue, by state").tag("queue", kind.tag())
                .tag("state", state).register(registry);
    }

    private double ageSeconds() {
        Instant last = lastSuccess;
        return last == null ? Double.NaN : Duration.between(last, clock.instant()).toMillis() / 1000.0;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        subscription = Flux.interval(Duration.ZERO, properties.interval(), timer)
                .onBackpressureDrop()
                .concatMap(tick -> refresh().onErrorResume(error -> {
                    LOG.warn("Queue depth refresh failed ({}); keeping the last values",
                            error.getClass().getSimpleName());
                    metrics.queueStatsRefreshed(false);
                    return Mono.empty();
                }), 1)
                .subscribe(null, error -> LOG.error("Queue depth monitor terminated unexpectedly", error));
        LOG.info("Queue depth monitor started (interval={})", properties.interval());
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (subscription != null) {
            subscription.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** One refresh of both queues; fails (keeping the previous values) when any lookup fails. */
    Mono<Void> refresh() {
        return Mono.defer(() -> ordersQueueUrl
                        .flatMap(url -> attributes(url).flatMap(attrs -> {
                            store(OperationalMetrics.QueueKind.ORDERS, attrs);
                            return refreshDlq(attrs.get(QueueAttributeName.REDRIVE_POLICY));
                        })))
                .timeout(properties.timeout(), timer)
                .doOnSuccess(done -> {
                    lastSuccess = clock.instant();
                    metrics.queueStatsRefreshed(true);
                });
    }

    private Mono<Void> refreshDlq(String redrivePolicy) {
        String dlqName = dlqName(redrivePolicy);
        if (dlqName == null) {
            return Mono.empty();
        }
        Mono<String> url = dlqUrl;
        if (url == null) {
            url = dlqUrlResolver.apply(dlqName);
            dlqUrl = url;
        }
        return url.flatMap(this::attributes).doOnNext(attrs -> store(OperationalMetrics.QueueKind.DLQ, attrs)).then();
    }

    private Mono<Map<QueueAttributeName, String>> attributes(String url) {
        return Mono.fromFuture(() -> client.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(url)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                                QueueAttributeName.REDRIVE_POLICY).build()))
                .map(response -> response.attributes());
    }

    private void store(OperationalMetrics.QueueKind kind, Map<QueueAttributeName, String> attrs) {
        visible.get(kind).set(parse(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)));
        inFlight.get(kind).set(parse(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)));
    }

    private static long parse(String value) {
        try {
            return value == null ? UNKNOWN : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
    }

    /** Name of the dead-letter queue from the redrive policy ({@code deadLetterTargetArn}), or null. */
    static String dlqName(String redrivePolicy) {
        if (redrivePolicy == null || redrivePolicy.isBlank()) {
            return null;
        }
        try {
            JsonNode arn = JSON.readTree(redrivePolicy).get("deadLetterTargetArn");
            if (arn == null || !arn.isString()) {
                return null;
            }
            String value = arn.stringValue();
            String name = value.substring(value.lastIndexOf(':') + 1);
            return name.isBlank() ? null : name;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
