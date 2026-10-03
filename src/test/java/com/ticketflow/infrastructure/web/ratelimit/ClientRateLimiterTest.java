package com.ticketflow.infrastructure.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ClientRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;

    private ClientRateLimiter limiter(int capacity, double refill, long maxClients, Duration idleTtl) {
        return ClientRateLimiter.create(true, capacity, refill, maxClients, idleTtl, ticker);
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    @Test
    void tryAcquire_exhaustedClient_isRejectedWithRetryAfterWhileOthersAreUnaffected() {
        var limiter = limiter(2, 1, 100, Duration.ofMinutes(10));
        assertThat(limiter.tryAcquire("a")).isEqualTo(Duration.ZERO);
        assertThat(limiter.tryAcquire("a")).isEqualTo(Duration.ZERO);
        assertThat(limiter.tryAcquire("a")).isEqualTo(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("b")).isEqualTo(Duration.ZERO);
    }

    @Test
    void tryAcquire_afterTheWait_isAllowedAgain() {
        var limiter = limiter(1, 1, 100, Duration.ofMinutes(10));
        limiter.tryAcquire("a");
        assertThat(limiter.tryAcquire("a")).isPositive();
        advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("a")).isEqualTo(Duration.ZERO);
    }

    @Test
    void disabled_allowsEverythingAndTracksNothing() {
        var limiter = ClientRateLimiter.create(false, 1, 1, 10, Duration.ofMinutes(1), ticker);
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire("a")).isEqualTo(Duration.ZERO);
            limiter.penalize("a");
        }
        assertThat(limiter.blockedFor("a")).isEqualTo(Duration.ZERO);
        assertThat(limiter.trackedClients()).isZero();
    }

    @Test
    void memory_isBoundedByMaxClients() {
        var limiter = limiter(5, 1, 50, Duration.ofMinutes(10));
        for (int i = 0; i < 5_000; i++) {
            limiter.tryAcquire("client-" + i);
        }
        assertThat(limiter.trackedClients()).isLessThanOrEqualTo(50);
    }

    @Test
    void idleClients_areEvictedAfterTheTtl() {
        var limiter = limiter(2, 1, 100, Duration.ofMinutes(10));
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        assertThat(limiter.trackedClients()).isEqualTo(2);
        advance(Duration.ofMinutes(11));
        assertThat(limiter.trackedClients()).isZero();
    }

    @Test
    void idleTtl_neverShorterThanTheTimeTheBucketNeedsToRefill() {
        // 100 tokens at 0.01/s need 10000 s to refill: a 1 s TTL must not hand out a fresh bucket.
        var limiter = limiter(100, 0.01, 100, Duration.ofSeconds(1));
        limiter.penalize("a");
        advance(Duration.ofSeconds(2));
        assertThat(limiter.trackedClients()).isEqualTo(1);
    }

    @Test
    void blockedFor_doesNotConsumeNorTrackUnknownClients() {
        var limiter = limiter(1, 1, 100, Duration.ofMinutes(10));
        assertThat(limiter.blockedFor("ghost")).isEqualTo(Duration.ZERO);
        assertThat(limiter.trackedClients()).isZero();
        limiter.penalize("a");
        assertThat(limiter.blockedFor("a")).isEqualTo(Duration.ofSeconds(1));
        assertThat(limiter.blockedFor("a")).isEqualTo(Duration.ofSeconds(1));
        advance(Duration.ofSeconds(1));
        assertThat(limiter.blockedFor("a")).isEqualTo(Duration.ZERO);
    }

    @Test
    void penalize_untilTheBudgetIsSpent_blocksTheClient() {
        var limiter = limiter(3, 0.1, 100, Duration.ofMinutes(10));
        limiter.penalize("a");
        limiter.penalize("a");
        assertThat(limiter.blockedFor("a")).isEqualTo(Duration.ZERO);
        limiter.penalize("a");
        assertThat(limiter.blockedFor("a")).isEqualTo(Duration.ofSeconds(10));
        assertThat(limiter.blockedFor("other")).isEqualTo(Duration.ZERO);
    }

    @Test
    void create_invalidArguments_areRejected() {
        Duration ttl = Duration.ofMinutes(1);
        assertThatThrownBy(() -> ClientRateLimiter.create(true, 0, 1, 1, ttl, ticker))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientRateLimiter.create(true, 1, 0, 1, ttl, ticker))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientRateLimiter.create(true, 1, 1, 0, ttl, ticker))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientRateLimiter.create(true, 1, 1, 1, Duration.ZERO, ticker))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientRateLimiter.create(true, 1, 1, 1, Duration.ofSeconds(-1), ticker))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
