package com.ticketflow.infrastructure.observability;

import com.ticketflow.testsupport.TestTimeouts;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class DependencyHealthIndicatorTest {

    private static final Duration TIMEOUT = TestTimeouts.WAIT;
    private static final Duration TTL = Duration.ofSeconds(5);

    private final AtomicInteger probes = new AtomicInteger();
    private final AtomicLong clockNanos = new AtomicLong();

    private DependencyHealthIndicator indicator(java.util.function.Supplier<Mono<?>> probe) {
        return new DependencyHealthIndicator("test", probe, TIMEOUT, TTL, clockNanos::get);
    }

    @Test
    void health_probeSucceeds_isUpWithoutDetails() {
        var indicator = indicator(() -> Mono.just("ok"));

        StepVerifier.create(indicator.health()).assertNext(health -> {
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).isEmpty();
        }).verifyComplete();
    }

    @Test
    void health_probeSucceedsWithEmpty_isUp() {
        StepVerifier.create(indicator(Mono::empty).health())
                .assertNext(health -> assertThat(health.getStatus()).isEqualTo(Status.UP)).verifyComplete();
    }

    @Test
    void health_probeFails_isDownWithoutLeakingTheCause() {
        var indicator = indicator(() -> Mono.error(new IllegalStateException("secret endpoint http://10.0.0.7:8000")));

        StepVerifier.create(indicator.health()).assertNext(health -> {
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).isEmpty();
            assertThat(health.toString()).doesNotContain("secret", "10.0.0.7");
        }).verifyComplete();
    }

    @Test
    void health_probeNeverAnswers_isDownAfterTheTimeout() {
        StepVerifier.withVirtualTime(() -> indicator(Mono::never).health())
                .expectSubscription()
                .expectNoEvent(TIMEOUT.minusMillis(1))
                .thenAwait(Duration.ofMillis(2))
                .assertNext(health -> assertThat(health.getStatus()).isEqualTo(Status.DOWN))
                .verifyComplete();
    }

    @Test
    void health_withinTheTtl_reusesTheLastResultWithoutCallingTheDependency() {
        var indicator = indicator(() -> Mono.fromSupplier(probes::incrementAndGet));

        indicator.health().block(TestTimeouts.WAIT);
        clockNanos.set(TTL.toNanos() - 1);
        indicator.health().block(TestTimeouts.WAIT);
        indicator.health().block(TestTimeouts.WAIT);

        assertThat(probes).hasValue(1);
    }

    @Test
    void health_afterTheTtl_probesAgain() {
        var indicator = indicator(() -> Mono.fromSupplier(probes::incrementAndGet));

        indicator.health().block(TestTimeouts.WAIT);
        clockNanos.set(TTL.toNanos());
        indicator.health().block(TestTimeouts.WAIT);

        assertThat(probes).hasValue(2);
    }

    @Test
    void health_downIsCachedToo_soAFailingDependencyIsNotHammered() {
        var indicator = indicator(() -> {
            probes.incrementAndGet();
            return Mono.error(new IllegalStateException("down"));
        });

        for (int i = 0; i < 5; i++) {
            assertThat(indicator.health().block(TestTimeouts.WAIT).getStatus()).isEqualTo(Status.DOWN);
        }

        assertThat(probes).hasValue(1);
    }

    @Test
    void health_recoversWhenTheDependencyComesBackAfterTheTtl() {
        var up = new java.util.concurrent.atomic.AtomicBoolean(false);
        var indicator = indicator(() -> up.get() ? Mono.just("ok") : Mono.error(new IllegalStateException("down")));

        assertThat(indicator.health().block(TestTimeouts.WAIT).getStatus()).isEqualTo(Status.DOWN);
        up.set(true);
        clockNanos.set(TTL.toNanos());
        assertThat(indicator.health().block(TestTimeouts.WAIT).getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void health_concurrentProbesShareTheCallInFlight() {
        var gate = Sinks.<String>one();
        var indicator = indicator(() -> {
            probes.incrementAndGet();
            return gate.asMono();
        });

        var first = indicator.health().subscribe();
        var second = indicator.health().subscribe();
        gate.tryEmitValue("ok");

        assertThat(probes).hasValue(1);
        assertThat(first.isDisposed()).isTrue();
        assertThat(second.isDisposed()).isTrue();
    }
}
