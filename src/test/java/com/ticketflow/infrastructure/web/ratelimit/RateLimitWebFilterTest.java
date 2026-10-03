package com.ticketflow.infrastructure.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Ticker;
import com.ticketflow.infrastructure.web.error.RateLimitExceededException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RateLimitWebFilterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final WebFilterChain chain = mock(WebFilterChain.class);

    RateLimitWebFilterTest() {
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private RateLimitWebFilter filter(int capacity) {
        return new RateLimitWebFilter(
                ClientRateLimiter.create(true, capacity, 1, 100, Duration.ofMinutes(5), ticker),
                new ClientAddressResolver(false));
    }

    private static MockServerWebExchange exchange(String method, String path, String remote) throws Exception {
        var request = MockServerHttpRequest.method(org.springframework.http.HttpMethod.valueOf(method), path)
                .remoteAddress(new InetSocketAddress(InetAddress.getByName(remote), 5000));
        return MockServerWebExchange.from(request);
    }

    static Stream<Arguments> limitedRoutes() {
        return Stream.of(Arguments.of("POST", "/orders"),
                Arguments.of("POST", "/events"), Arguments.of("POST", "/events/evt-1/complimentary"));
    }

    @ParameterizedTest
    @MethodSource("limitedRoutes")
    void filter_writeRoute_isRejectedWithRetryAfterOnceTheBudgetIsSpent(String method, String path) throws Exception {
        var filter = filter(2);
        for (int i = 0; i < 2; i++) {
            StepVerifier.create(filter.filter(exchange(method, path, "10.0.0.1"), chain)).verifyComplete();
        }
        StepVerifier.create(filter.filter(exchange(method, path, "10.0.0.1"), chain))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(
                        RateLimitExceededException.class,
                        limit -> assertThat(limit.retryAfter()).isEqualTo(Duration.ofSeconds(1))))
                .verify();
        verify(chain, times(2)).filter(any());
    }

    @Test
    void filter_otherClient_isUnaffectedByAnExhaustedOne() throws Exception {
        var filter = filter(1);
        StepVerifier.create(filter.filter(exchange("POST", "/orders", "10.0.0.1"), chain)).verifyComplete();
        StepVerifier.create(filter.filter(exchange("POST", "/orders", "10.0.0.1"), chain))
                .expectError(RateLimitExceededException.class).verify();
        StepVerifier.create(filter.filter(exchange("POST", "/orders", "10.0.0.2"), chain)).verifyComplete();
    }

    @Test
    void filter_routesThatAreNotWrites_arePassedThroughAndNeverCounted() throws Exception {
        var filter = filter(1);
        for (int i = 0; i < 20; i++) {
            StepVerifier.create(filter.filter(exchange("GET", "/orders/ord-1", "10.0.0.1"), chain)).verifyComplete();
            StepVerifier.create(filter.filter(exchange("GET", "/events", "10.0.0.1"), chain)).verifyComplete();
            StepVerifier.create(filter.filter(exchange("POST", "/other", "10.0.0.1"), chain)).verifyComplete();
            StepVerifier.create(filter.filter(exchange("GET", "/events/evt-1/availability", "10.0.0.1"), chain))
                    .verifyComplete();
        }
        // The single token is still there.
        StepVerifier.create(filter.filter(exchange("POST", "/orders", "10.0.0.1"), chain)).verifyComplete();
    }

    @Test
    void filter_afterTheRetryAfterElapsed_acceptsAgain() throws Exception {
        var filter = filter(1);
        StepVerifier.create(filter.filter(exchange("POST", "/orders", "10.0.0.1"), chain)).verifyComplete();
        StepVerifier.create(filter.filter(exchange("POST", "/orders", "10.0.0.1"), chain))
                .expectError(RateLimitExceededException.class).verify();
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        StepVerifier.create(filter.filter(exchange("POST", "/orders", "10.0.0.1"), chain)).verifyComplete();
    }
}
