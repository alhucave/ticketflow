package com.ticketflow.infrastructure.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TokenBucketTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void tryConsume_withinCapacity_isAllowedThenDenied() {
        var bucket = new TokenBucket(3, 1, 0);
        assertThat(bucket.tryConsume(0)).isEqualTo(Duration.ZERO);
        assertThat(bucket.tryConsume(0)).isEqualTo(Duration.ZERO);
        assertThat(bucket.tryConsume(0)).isEqualTo(Duration.ZERO);
        assertThat(bucket.tryConsume(0)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void tryConsume_denied_reportsTheWaitUntilTheNextTokenAndDoesNotConsume() {
        var bucket = new TokenBucket(1, 0.5, 0); // one token every 2 s
        bucket.tryConsume(0);
        assertThat(bucket.tryConsume(0)).isEqualTo(Duration.ofSeconds(2));
        assertThat(bucket.tryConsume(SECOND)).isEqualTo(Duration.ofSeconds(1));
        assertThat(bucket.tryConsume(2 * SECOND)).isEqualTo(Duration.ZERO);
    }

    @Test
    void refill_neverExceedsCapacity() {
        var bucket = new TokenBucket(2, 10, 0);
        bucket.tryConsume(0);
        bucket.tryConsume(0);
        assertThat(bucket.tryConsume(1000 * SECOND)).isEqualTo(Duration.ZERO);
        assertThat(bucket.tryConsume(1000 * SECOND)).isEqualTo(Duration.ZERO);
        assertThat(bucket.tryConsume(1000 * SECOND)).isPositive();
    }

    @Test
    void waitTime_looksWithoutConsuming() {
        var bucket = new TokenBucket(1, 1, 0);
        assertThat(bucket.waitTime(0)).isEqualTo(Duration.ZERO);
        assertThat(bucket.waitTime(0)).isEqualTo(Duration.ZERO);
        assertThat(bucket.tryConsume(0)).isEqualTo(Duration.ZERO);
        assertThat(bucket.waitTime(0)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void consume_chargesUnconditionallyButNeverBelowZero() {
        var bucket = new TokenBucket(2, 1, 0);
        for (int i = 0; i < 10; i++) {
            bucket.consume(0);
        }
        // Empty, not "in debt": one second of refill buys exactly one token.
        assertThat(bucket.waitTime(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(bucket.waitTime(SECOND)).isEqualTo(Duration.ZERO);
    }

    @Test
    void time_goingBackwards_isIgnored() {
        var bucket = new TokenBucket(1, 1, 10 * SECOND);
        bucket.tryConsume(10 * SECOND);
        assertThat(bucket.tryConsume(0)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void timeToFull_isCapacityOverRate() {
        assertThat(TokenBucket.timeToFull(20, 1)).isEqualTo(Duration.ofSeconds(20));
        assertThat(TokenBucket.timeToFull(5, 0.05)).isEqualTo(Duration.ofSeconds(100));
    }
}
