package com.ticketflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Ticker;
import com.ticketflow.infrastructure.observability.OperationalMetrics;
import com.ticketflow.infrastructure.web.error.AdminAccessDeniedException;
import com.ticketflow.infrastructure.web.error.RateLimitExceededException;
import com.ticketflow.infrastructure.web.ratelimit.ClientAddressResolver;
import com.ticketflow.infrastructure.web.ratelimit.ClientRateLimiter;
import com.ticketflow.infrastructure.web.ratelimit.RateLimitWebFilter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Rate-limiter rejections and admin-key lockouts are counted by limiter; allowed requests are not. */
class RateLimitMetricsTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final WebFilterChain chain = mock(WebFilterChain.class);
    private final List<String> rejections = new ArrayList<>();
    private final OperationalMetrics metrics = new OperationalMetrics() {
        @Override
        public void rateLimitRejected(Limiter limiter) {
            rejections.add(limiter.tag());
        }
    };

    RateLimitMetricsTest() {
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private static MockServerWebExchange exchange(String path, String adminKey) throws Exception {
        var request = MockServerHttpRequest.method(HttpMethod.POST, path)
                .remoteAddress(new InetSocketAddress(InetAddress.getByName("10.0.0.1"), 5000));
        if (adminKey != null) {
            request.header("X-Admin-Key", adminKey);
        }
        return MockServerWebExchange.from(request);
    }

    @Test
    void writeLimiter_overBudget_countsOneWriteRejectionPerRefusedRequest() throws Exception {
        var filter = new RateLimitWebFilter(
                ClientRateLimiter.create(true, 1, 1, 100, Duration.ofMinutes(5), ticker),
                new ClientAddressResolver(false), metrics);

        StepVerifier.create(filter.filter(exchange("/orders", null), chain)).verifyComplete();
        assertThat(rejections).isEmpty();
        StepVerifier.create(filter.filter(exchange("/orders", null), chain))
                .expectError(RateLimitExceededException.class).verify();
        StepVerifier.create(filter.filter(exchange("/orders", null), chain))
                .expectError(RateLimitExceededException.class).verify();

        assertThat(rejections).containsExactly("write", "write");
    }

    @Test
    void adminLockout_countsLockoutsButNotPlainWrongKeys() throws Exception {
        var failures = ClientRateLimiter.create(true, 2, 0.1, 100, Duration.ofMinutes(5), ticker);
        var filter = new AdminKeyWebFilter("the-real-admin-key", new ClientAddressResolver(false), failures, metrics);
        String route = "/events/evt-1/complimentary";

        for (int i = 0; i < 2; i++) {
            StepVerifier.create(filter.filter(exchange(route, "wrong"), chain))
                    .expectError(AdminAccessDeniedException.class).verify();
        }
        assertThat(rejections).as("wrong keys alone are 401s, not lockouts").isEmpty();

        StepVerifier.create(filter.filter(exchange(route, "the-real-admin-key"), chain))
                .expectError(RateLimitExceededException.class).verify();

        assertThat(rejections).containsExactly("admin_failure");
    }
}
