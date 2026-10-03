package com.ticketflow.infrastructure.web.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;

/**
 * Per-client token-bucket limiter with BOUNDED memory: at most {@code maxClients} buckets are tracked
 * and a bucket idle for {@code idleTtl} is dropped (never shorter than the time the bucket needs to
 * refill completely, so eviction of an idle client loses nothing). When the table is full the least
 * valuable entry is evicted; an attacker rotating through many addresses can therefore reset other
 * clients' budgets, which is why a deployment also needs an edge layer (see the README).
 *
 * <p>State is per instance: with N instances behind a load balancer the effective budget is N times
 * the configured one. A disabled limiter lets everything through and tracks nothing.
 */
public final class ClientRateLimiter {

    private final boolean enabled;
    private final double capacity;
    private final double refillPerSecond;
    private final Ticker ticker;
    private final Cache<String, TokenBucket> buckets;

    private ClientRateLimiter(boolean enabled, double capacity, double refillPerSecond, long maxClients,
                              Duration idleTtl, Ticker ticker) {
        this.enabled = enabled;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.ticker = ticker;
        Duration ttl = idleTtl.compareTo(TokenBucket.timeToFull(capacity, refillPerSecond)) >= 0
                ? idleTtl : TokenBucket.timeToFull(capacity, refillPerSecond);
        this.buckets = Caffeine.newBuilder().maximumSize(maxClients).expireAfterAccess(ttl).ticker(ticker)
                .executor(Runnable::run).build();
    }

    public static ClientRateLimiter create(boolean enabled, int capacity, double refillPerSecond, long maxClients,
                                           Duration idleTtl, Ticker ticker) {
        if (capacity < 1 || refillPerSecond <= 0 || maxClients < 1 || idleTtl.isNegative() || idleTtl.isZero()) {
            throw new IllegalArgumentException("Rate limiter needs capacity >= 1, refill > 0, maxClients >= 1 "
                    + "and a positive idle TTL");
        }
        return new ClientRateLimiter(enabled, capacity, refillPerSecond, maxClients, idleTtl, ticker);
    }

    /** Takes one token of the client's bucket. @return {@link Duration#ZERO} when allowed, otherwise the wait */
    public Duration tryAcquire(String client) {
        if (!enabled) {
            return Duration.ZERO;
        }
        long now = ticker.read();
        return buckets.get(client, key -> new TokenBucket(capacity, refillPerSecond, now)).tryConsume(now);
    }

    /** Whether the client is currently out of tokens, without consuming one or tracking new clients. */
    public Duration blockedFor(String client) {
        if (!enabled) {
            return Duration.ZERO;
        }
        TokenBucket bucket = buckets.getIfPresent(client);
        return bucket == null ? Duration.ZERO : bucket.waitTime(ticker.read());
    }

    /** Charges one token unconditionally (a failed attempt). */
    public void penalize(String client) {
        if (!enabled) {
            return;
        }
        long now = ticker.read();
        buckets.get(client, key -> new TokenBucket(capacity, refillPerSecond, now)).consume(now);
    }

    /** Number of clients currently tracked (after pending evictions are applied). */
    public long trackedClients() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }
}
