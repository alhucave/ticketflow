package com.ticketflow.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ticketflow.infrastructure.config.ExpirationProperties;
import com.ticketflow.usecase.ReleaseExpiredReservationsUseCase;
import com.ticketflow.usecase.ReleaseExpiredReservationsUseCase.Summary;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

class ReservationExpirationSchedulerTest {

    private static final Duration INTERVAL = Duration.ofMinutes(1);
    private static final Duration INITIAL = Duration.ofSeconds(10);
    private static final Duration SHUTDOWN = Duration.ofSeconds(5);

    private final VirtualTimeScheduler timer = VirtualTimeScheduler.create();
    private final ReleaseExpiredReservationsUseCase useCase = mock(ReleaseExpiredReservationsUseCase.class);
    private final ExpirationProperties properties =
            new ExpirationProperties(true, INTERVAL, INITIAL, 4, 500, SHUTDOWN);
    private final ReservationExpirationScheduler scheduler =
            new ReservationExpirationScheduler(useCase, properties, timer);
    private final AtomicInteger runs = new AtomicInteger();

    @AfterEach
    void tearDown() {
        scheduler.stop();
    }

    private void sweepsImmediately() {
        when(useCase.execute()).thenAnswer(i -> Mono.fromSupplier(() -> {
            runs.incrementAndGet();
            return new Summary(0, 0, 0, 0);
        }));
    }

    @Test
    void start_runsFirstSweepAfterInitialDelayThenEveryInterval() {
        sweepsImmediately();

        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();
        timer.advanceTimeBy(INITIAL.minusMillis(1));
        assertThat(runs).hasValue(0);
        timer.advanceTimeBy(Duration.ofMillis(1));
        assertThat(runs).hasValue(1);
        timer.advanceTimeBy(INTERVAL.minusMillis(1));
        assertThat(runs).hasValue(1);
        timer.advanceTimeBy(Duration.ofMillis(1));
        assertThat(runs).hasValue(2);
        timer.advanceTimeBy(INTERVAL.multipliedBy(3));
        assertThat(runs).hasValue(5);
    }

    @Test
    void start_calledTwice_doesNotDuplicateTheSchedule() {
        sweepsImmediately();

        scheduler.start();
        scheduler.start();
        timer.advanceTimeBy(INITIAL.plus(INTERVAL));

        assertThat(runs).hasValue(2);
    }

    @Test
    void schedule_slowSweepLongerThanInterval_neverOverlaps() {
        var inFlight = new AtomicInteger();
        var peak = new AtomicInteger();
        when(useCase.execute()).thenAnswer(i -> Mono.defer(() -> {
            runs.incrementAndGet();
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            return Mono.delay(Duration.ofMinutes(3), timer).thenReturn(new Summary(0, 0, 0, 0))
                    .doFinally(s -> inFlight.decrementAndGet());
        }));

        scheduler.start();
        timer.advanceTimeBy(INITIAL);
        assertThat(runs).hasValue(1);
        timer.advanceTimeBy(Duration.ofMinutes(3)); // sweep 1 ends exactly now; ticks during it were not queued
        assertThat(runs).hasValue(1);
        timer.advanceTimeBy(INTERVAL); // next sweep starts one interval AFTER the previous finished
        assertThat(runs).hasValue(2);
        timer.advanceTimeBy(Duration.ofMinutes(10));
        assertThat(peak).hasValue(1);
        assertThat(runs.get()).isLessThanOrEqualTo(4);
    }

    @Test
    void schedule_sweepErrorsAndSynchronousThrows_neverKillTheSchedule() {
        var calls = new AtomicInteger();
        when(useCase.execute()).thenAnswer(i -> {
            return switch (calls.incrementAndGet()) {
                case 1 -> Mono.error(new IllegalStateException("gsi down"));
                case 2 -> throw new IllegalStateException("thrown synchronously");
                default -> Mono.fromSupplier(() -> {
                    runs.incrementAndGet();
                    return new Summary(1, 1, 0, 0);
                });
            };
        });

        scheduler.start();
        timer.advanceTimeBy(INITIAL.plus(INTERVAL.multipliedBy(3)));

        assertThat(calls).hasValue(4);
        assertThat(runs).hasValue(2);
        assertThat(scheduler.isRunning()).isTrue();
    }

    @Test
    void stop_whileWaiting_stopsImmediatelyAndNoFurtherSweeps() {
        sweepsImmediately();
        var stopped = new AtomicBoolean();

        scheduler.start();
        timer.advanceTimeBy(INITIAL);
        scheduler.stop(() -> stopped.set(true));
        timer.advanceTimeBy(INTERVAL.multipliedBy(5));

        assertThat(stopped).isTrue();
        assertThat(scheduler.isRunning()).isFalse();
        assertThat(runs).hasValue(1);
    }

    @Test
    void stop_beforeFirstSweep_neverSweeps() {
        sweepsImmediately();

        scheduler.start();
        scheduler.stop();
        timer.advanceTimeBy(INTERVAL.multipliedBy(3));

        assertThat(runs).hasValue(0);
    }

    @Test
    void stop_sweepInProgress_waitsForItToFinish() {
        var finished = new AtomicBoolean();
        var stopped = new AtomicBoolean();
        when(useCase.execute()).thenAnswer(i -> Mono.delay(Duration.ofSeconds(3), timer)
                .doOnNext(t -> finished.set(true)).thenReturn(new Summary(0, 0, 0, 0)));

        scheduler.start();
        timer.advanceTimeBy(INITIAL);
        scheduler.stop(() -> stopped.set(true));
        assertThat(stopped).isFalse();
        timer.advanceTimeBy(Duration.ofSeconds(3));

        assertThat(finished).isTrue();
        assertThat(stopped).isTrue();
    }

    @Test
    void stop_sweepNeverFinishes_disposedAfterShutdownTimeout() {
        var cancelled = new AtomicBoolean();
        var stopped = new AtomicBoolean();
        when(useCase.execute()).thenAnswer(i -> Mono.<Summary>never().doOnCancel(() -> cancelled.set(true)));

        scheduler.start();
        timer.advanceTimeBy(INITIAL);
        scheduler.stop(() -> stopped.set(true));
        timer.advanceTimeBy(SHUTDOWN.minusMillis(1));
        assertThat(stopped).isFalse();
        timer.advanceTimeBy(Duration.ofMillis(1));

        assertThat(stopped).isTrue();
        assertThat(cancelled).isTrue();
    }

    @Test
    void stop_whenNotRunning_invokesCallbackAndIsIdempotent() {
        var stopped = new AtomicBoolean();

        scheduler.stop(() -> stopped.set(true));
        scheduler.stop();

        assertThat(stopped).isTrue();
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void restart_afterStop_schedulesAgain() {
        sweepsImmediately();

        scheduler.start();
        timer.advanceTimeBy(INITIAL);
        scheduler.stop();
        scheduler.start();
        timer.advanceTimeBy(INITIAL);

        assertThat(runs).hasValue(2);
    }

    @Test
    void publicConstructor_usesRealTimerAndStartsStopsCleanly() {
        var real = new ReservationExpirationScheduler(useCase, properties);

        real.start();
        assertThat(real.isRunning()).isTrue();
        real.stop();
        assertThat(real.isRunning()).isFalse();
    }
}
