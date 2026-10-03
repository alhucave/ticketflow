package com.ticketflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Ticker;
import com.ticketflow.infrastructure.web.error.AdminAccessDeniedException;
import com.ticketflow.infrastructure.web.error.RateLimitExceededException;
import com.ticketflow.infrastructure.web.ratelimit.ClientAddressResolver;
import com.ticketflow.infrastructure.web.ratelimit.ClientRateLimiter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Brute-force protection of the admin key: failed attempts are budgeted per client. */
class AdminKeyWebFilterTest {

    private static final String KEY = "the-real-admin-key-9f3a";
    private static final String ROUTE = "/events/evt-1/complimentary";

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final WebFilterChain chain = mock(WebFilterChain.class);
    private final ClientRateLimiter failures = ClientRateLimiter.create(true, 3, 0.1, 100, Duration.ofMinutes(5), ticker);

    AdminKeyWebFilterTest() {
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private AdminKeyWebFilter filter(String key) {
        return new AdminKeyWebFilter(key, new ClientAddressResolver(false), failures);
    }

    private static MockServerWebExchange exchange(String path, String adminKey, String remote) throws Exception {
        var request = MockServerHttpRequest.method(HttpMethod.POST, path)
                .remoteAddress(new InetSocketAddress(InetAddress.getByName(remote), 5000));
        if (adminKey != null) {
            request.header("X-Admin-Key", adminKey);
        }
        return MockServerWebExchange.from(request);
    }

    private void fail(AdminKeyWebFilter filter, String remote) throws Exception {
        StepVerifier.create(filter.filter(exchange(ROUTE, "wrong", remote), chain))
                .expectError(AdminAccessDeniedException.class).verify();
    }

    @Test
    void filter_failedAttempts_areBudgetedThenEvenTheRightKeyIsRefused() throws Exception {
        var filter = filter(KEY);
        fail(filter, "10.0.0.1");
        fail(filter, "10.0.0.1");
        fail(filter, "10.0.0.1");

        StepVerifier.create(filter.filter(exchange(ROUTE, "wrong", "10.0.0.1"), chain))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(RateLimitExceededException.class,
                        limit -> assertThat(limit.retryAfter()).isEqualTo(Duration.ofSeconds(10))))
                .verify();
        // The brute-forcer cannot confirm a guess during the lockout: the key is not even compared.
        StepVerifier.create(filter.filter(exchange(ROUTE, KEY, "10.0.0.1"), chain))
                .expectError(RateLimitExceededException.class).verify();
        verify(chain, never()).filter(any());
    }

    @Test
    void filter_lockout_endsWhenATokenRefills() throws Exception {
        var filter = filter(KEY);
        for (int i = 0; i < 3; i++) {
            fail(filter, "10.0.0.1");
        }
        nanos.addAndGet(Duration.ofSeconds(10).toNanos());

        StepVerifier.create(filter.filter(exchange(ROUTE, KEY, "10.0.0.1"), chain)).verifyComplete();
        verify(chain).filter(any());
    }

    @Test
    void filter_lockoutOfOneClient_doesNotAffectAnother() throws Exception {
        var filter = filter(KEY);
        for (int i = 0; i < 3; i++) {
            fail(filter, "10.0.0.1");
        }
        StepVerifier.create(filter.filter(exchange(ROUTE, KEY, "10.0.0.2"), chain)).verifyComplete();
    }

    @Test
    void filter_successfulAttempts_areNeverCharged() throws Exception {
        var filter = filter(KEY);
        for (int i = 0; i < 50; i++) {
            StepVerifier.create(filter.filter(exchange(ROUTE, KEY, "10.0.0.1"), chain)).verifyComplete();
        }
        assertThat(failures.blockedFor("10.0.0.1")).isEqualTo(Duration.ZERO);
    }

    @Test
    void filter_missingKey_countsAsAFailedAttempt() throws Exception {
        var filter = filter(KEY);
        for (int i = 0; i < 3; i++) {
            StepVerifier.create(filter.filter(exchange(ROUTE, null, "10.0.0.1"), chain))
                    .expectError(AdminAccessDeniedException.class).verify();
        }
        StepVerifier.create(filter.filter(exchange(ROUTE, KEY, "10.0.0.1"), chain))
                .expectError(RateLimitExceededException.class).verify();
    }

    @Test
    void filter_noKeyConfigured_isNotAGuessAndNeverLocksOut() throws Exception {
        var filter = filter("");
        for (int i = 0; i < 20; i++) {
            StepVerifier.create(filter.filter(exchange(ROUTE, "anything", "10.0.0.1"), chain))
                    .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(
                            AdminAccessDeniedException.class, denied -> assertThat(denied.isDisabled()).isTrue()))
                    .verify();
        }
    }

    @Test
    void filter_nonAdminRoute_isIgnoredEvenFromALockedOutClient() throws Exception {
        var filter = filter(KEY);
        for (int i = 0; i < 3; i++) {
            fail(filter, "10.0.0.1");
        }
        StepVerifier.create(filter.filter(exchange("/orders", null, "10.0.0.1"), chain)).verifyComplete();
    }

    @Test
    void toString_neverExposesTheKey() {
        assertThat(filter(KEY).toString()).doesNotContain(KEY).contains("enabled=true");
        assertThat(filter("").toString()).contains("enabled=false");
        assertThat(new AdminKeyGuard(KEY).toString()).doesNotContain(KEY).isEqualTo("AdminKeyGuard[enabled=true]");
    }
}
