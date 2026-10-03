package com.ticketflow.infrastructure.observability;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import reactor.core.publisher.Mono;

/**
 * Reactive readiness check of one external dependency (DynamoDB, SQS): runs a cheap probe call, never blocks a
 * thread, bounds it with a timeout (a dependency slower than {@code timeout} is DOWN) and reuses the result for
 * {@code cacheTtl}, so frequent probes (load balancer, Prometheus, container health check) cost one AWS call per
 * TTL, not one per probe; concurrent probes share the call in flight.
 *
 * <p>The result is only UP or DOWN, without details: the cause is never exposed (the health endpoint shows no
 * details and the exception message may carry endpoints or account data); the failure class is logged.
 * Used only in the readiness group: liveness must never depend on an external system.
 */
public final class DependencyHealthIndicator implements ReactiveHealthIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(DependencyHealthIndicator.class);

    private final String name;
    private final Supplier<Mono<?>> probe;
    private final Duration timeout;
    private final long cacheTtlNanos;
    private final LongSupplier nanoTime;

    private Mono<Health> current;
    private long currentCreatedAt;

    public DependencyHealthIndicator(String name, Supplier<Mono<?>> probe, Duration timeout, Duration cacheTtl) {
        this(name, probe, timeout, cacheTtl, System::nanoTime);
    }

    DependencyHealthIndicator(String name, Supplier<Mono<?>> probe, Duration timeout, Duration cacheTtl,
                              LongSupplier nanoTime) {
        this.name = name;
        this.probe = probe;
        this.timeout = timeout;
        this.cacheTtlNanos = cacheTtl.toNanos();
        this.nanoTime = nanoTime;
    }

    @Override
    public synchronized Mono<Health> health() {
        long now = nanoTime.getAsLong();
        if (current == null || now - currentCreatedAt >= cacheTtlNanos) {
            current = check().cache();
            currentCreatedAt = now;
        }
        return current;
    }

    private Mono<Health> check() {
        return Mono.defer(probe)
                .timeout(timeout)
                .thenReturn(Health.up().build())
                .onErrorResume(error -> {
                    LOG.warn("Readiness probe of {} failed ({}); reporting DOWN", name,
                            error.getClass().getSimpleName());
                    return Mono.just(Health.down().build());
                });
    }
}
