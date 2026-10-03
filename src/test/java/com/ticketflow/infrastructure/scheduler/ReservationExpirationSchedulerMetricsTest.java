package com.ticketflow.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ticketflow.infrastructure.config.ExpirationProperties;
import com.ticketflow.infrastructure.observability.OperationalMetrics;
import com.ticketflow.usecase.ReleaseExpiredReservationsUseCase;
import com.ticketflow.usecase.ReleaseExpiredReservationsUseCase.Summary;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

/** Sweep metrics: result and duration of every sweep, success or failure; the schedule survives errors. */
class ReservationExpirationSchedulerMetricsTest {

    private static final Duration INTERVAL = Duration.ofMinutes(1);

    private final VirtualTimeScheduler timer = VirtualTimeScheduler.create();
    private final ReleaseExpiredReservationsUseCase useCase = mock(ReleaseExpiredReservationsUseCase.class);
    private final List<String> events = new ArrayList<>();
    private final OperationalMetrics metrics = new OperationalMetrics() {
        @Override
        public void expirationSweepCompleted(Summary summary, Duration elapsed) {
            events.add("ok:" + summary.released() + ":" + elapsed.toMillis());
        }

        @Override
        public void expirationSweepFailed(Duration elapsed) {
            events.add("error:" + elapsed.toMillis());
        }
    };
    private final ReservationExpirationScheduler scheduler = new ReservationExpirationScheduler(useCase,
            new ExpirationProperties(true, INTERVAL, Duration.ofSeconds(10), 4, 500, Duration.ofSeconds(5)), timer,
            metrics);

    @AfterEach
    void tearDown() {
        scheduler.stop();
    }

    @Test
    void sweep_success_recordsSummaryAndVirtualDuration() {
        when(useCase.execute()).thenAnswer(i -> Mono.delay(Duration.ofMillis(250), timer)
                .thenReturn(new Summary(5, 4, 1, 0)));

        scheduler.start();
        timer.advanceTimeBy(Duration.ofSeconds(10).plusMillis(250));

        assertThat(events).containsExactly("ok:4:250");
    }

    @Test
    void sweep_failure_recordsErrorAndKeepsScheduling() {
        when(useCase.execute()).thenAnswer(i -> Mono.delay(Duration.ofMillis(40), timer)
                .then(Mono.<Summary>error(new IllegalStateException("query failed"))))
                .thenAnswer(i -> Mono.just(new Summary(0, 0, 0, 0)));

        scheduler.start();
        timer.advanceTimeBy(Duration.ofSeconds(10).plusMillis(40));
        assertThat(events).containsExactly("error:40");

        timer.advanceTimeBy(INTERVAL);
        assertThat(events).containsExactly("error:40", "ok:0:0");
    }

    @Test
    void constructor_withMetricsButDefaultTimer_isUsable() {
        var withDefaultTimer = new ReservationExpirationScheduler(useCase,
                new ExpirationProperties(true, INTERVAL, Duration.ofSeconds(10), 4, 500, Duration.ofSeconds(5)),
                metrics);
        assertThat(withDefaultTimer.isRunning()).isFalse();
    }
}
