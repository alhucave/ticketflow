package com.ticketflow.infrastructure.scheduler;

import com.ticketflow.infrastructure.config.ExpirationProperties;
import com.ticketflow.infrastructure.observability.OperationalMetrics;
import com.ticketflow.usecase.ReleaseExpiredReservationsUseCase;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Runs {@link ReleaseExpiredReservationsUseCase} periodically, without blocking any thread.
 *
 * <p><b>No overlap.</b> The loop is "wait {@code interval}, sweep, repeat": the next wait starts only
 * when the previous sweep finished, so two sweeps never run at the same time in one instance (across
 * instances the use case is safe by its conditional writes).
 *
 * <p><b>Resilience.</b> An error of one sweep (including a failure of the expired-order query) is
 * logged and the schedule goes on with the next interval.
 *
 * <p><b>Shutdown.</b> {@link #stop(Runnable)} cancels the wait between sweeps but lets a sweep in
 * progress finish for at most {@code shutdownTimeout}; a release interrupted after that is safe
 * because each one is a single atomic transaction.
 *
 * <p><b>Restart.</b> Each {@code start()} gets a new generation number captured by its loop; a loop
 * keeps scheduling only while it is still the current generation, so a {@code stop()} + {@code start()}
 * while the previous sweep is still draining cannot leave two schedules alive (the old one ends right
 * after its sweep).
 *
 * <p>The timer is injectable so tests use virtual time.
 */
public final class ReservationExpirationScheduler implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationExpirationScheduler.class);

    private final ReleaseExpiredReservationsUseCase useCase;
    private final ExpirationProperties properties;
    private final Scheduler timer;
    private final OperationalMetrics metrics;

    private Disposable subscription;
    private Sinks.Empty<Void> stopSignal;
    private Sinks.Empty<Void> terminated;
    private volatile boolean running;
    /** Incremented by every start(); a loop is alive only while its captured value is the current one. */
    private volatile long generation;

    public ReservationExpirationScheduler(ReleaseExpiredReservationsUseCase useCase,
                                          ExpirationProperties properties) {
        this(useCase, properties, Schedulers.parallel());
    }

    public ReservationExpirationScheduler(ReleaseExpiredReservationsUseCase useCase,
                                          ExpirationProperties properties, Scheduler timer) {
        this(useCase, properties, timer, OperationalMetrics.NOOP);
    }

    public ReservationExpirationScheduler(ReleaseExpiredReservationsUseCase useCase,
                                          ExpirationProperties properties, OperationalMetrics metrics) {
        this(useCase, properties, Schedulers.parallel(), metrics);
    }

    public ReservationExpirationScheduler(ReleaseExpiredReservationsUseCase useCase,
                                          ExpirationProperties properties, Scheduler timer,
                                          OperationalMetrics metrics) {
        this.metrics = metrics;
        this.useCase = useCase;
        this.properties = properties;
        this.timer = timer;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        long myGeneration = ++generation;
        BooleanSupplier alive = () -> running && generation == myGeneration;
        stopSignal = Sinks.empty();
        terminated = Sinks.empty();
        Mono<Void> stop = stopSignal.asMono();
        Sinks.Empty<Void> done = terminated;
        subscription = cycle(properties.initialDelay(), stop, alive)
                .thenMany(cycle(properties.interval(), stop, alive).repeat(alive::getAsBoolean))
                .doFinally(signal -> done.tryEmitEmpty())
                .subscribe(null, error -> LOG.error("Reservation expiration scheduler terminated unexpectedly",
                        error));
        LOG.info("Reservation expiration scheduler started (interval={}, initialDelay={}, concurrency={}, "
                        + "maxPerSweep={})", properties.interval(), properties.initialDelay(),
                properties.concurrency(), properties.maxPerSweep());
    }

    @Override
    public void stop() {
        stop(() -> { });
    }

    @Override
    public synchronized void stop(Runnable callback) {
        if (!running) {
            callback.run();
            return;
        }
        running = false;
        stopSignal.tryEmitEmpty();
        Disposable loop = subscription;
        LOG.info("Reservation expiration scheduler stopping (waiting up to {} for a sweep in progress)",
                properties.shutdownTimeout());
        terminated.asMono()
                .timeout(properties.shutdownTimeout(), timer)
                .onErrorResume(TimeoutException.class, e -> {
                    LOG.warn("Reservation expiration sweep did not finish in time; disposing it "
                            + "(each release is atomic, the rest is picked up by another sweep)");
                    return Mono.empty();
                })
                .doFinally(signal -> {
                    loop.dispose();
                    LOG.info("Reservation expiration scheduler stopped");
                    callback.run();
                })
                .subscribe();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Waits {@code delay} (cancelled by stop) and, if still running, performs one sweep. */
    private Mono<Void> cycle(Duration delay, Mono<Void> stop, BooleanSupplier alive) {
        return Mono.delay(delay, timer)
                .takeUntilOther(stop)
                .filter(tick -> alive.getAsBoolean())
                .flatMap(tick -> sweep());
    }

    /** One sweep; never fails, so the schedule survives any error. Records its result and duration. */
    private Mono<Void> sweep() {
        return Mono.defer(() -> {
            long startedAt = timer.now(TimeUnit.MILLISECONDS);
            return useCase.execute()
                    .doOnNext(summary -> metrics.expirationSweepCompleted(summary, elapsedSince(startedAt)))
                    .doOnError(error -> metrics.expirationSweepFailed(elapsedSince(startedAt)));
        })
                .onErrorResume(error -> {
                    LOG.error("Reservation expiration sweep failed; will retry at the next interval", error);
                    return Mono.empty();
                })
                .then();
    }

    private Duration elapsedSince(long startedAtMillis) {
        return Duration.ofMillis(Math.max(0, timer.now(TimeUnit.MILLISECONDS) - startedAtMillis));
    }
}
