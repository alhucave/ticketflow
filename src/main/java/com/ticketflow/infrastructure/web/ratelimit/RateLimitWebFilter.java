package com.ticketflow.infrastructure.web.ratelimit;

import com.ticketflow.infrastructure.observability.OperationalMetrics;
import com.ticketflow.infrastructure.web.error.RateLimitExceededException;
import java.time.Duration;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Applies the per-client budget to the write routes (all share one bucket per client). Over budget it
 * fails with {@link RateLimitExceededException}, which the problem handlers render as {@code 429} with
 * {@code Retry-After} computed from the bucket. Runs after the correlation id and security headers
 * filters (so the rejection carries both) and before the admin-key filter and routing, so a rejected
 * request costs almost nothing: its body is never read. Read routes are not limited.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class RateLimitWebFilter implements WebFilter {

    private record Route(HttpMethod method, PathPattern pattern) {
        boolean matches(ServerWebExchange exchange) {
            return method.equals(exchange.getRequest().getMethod())
                    && pattern.matches(exchange.getRequest().getPath().pathWithinApplication());
        }
    }

    /** Write routes under the budget: add the pattern of another write route here to protect it. */
    private static final List<Route> LIMITED_ROUTES = List.of(
            route(HttpMethod.POST, "/orders"),
            route(HttpMethod.POST, "/events"),
            route(HttpMethod.POST, "/events/{id}/complimentary"));

    private final ClientRateLimiter limiter;
    private final ClientAddressResolver clients;
    private final OperationalMetrics metrics;

    public RateLimitWebFilter(ClientRateLimiter limiter, ClientAddressResolver clients) {
        this(limiter, clients, OperationalMetrics.NOOP);
    }

    public RateLimitWebFilter(ClientRateLimiter limiter, ClientAddressResolver clients, OperationalMetrics metrics) {
        this.limiter = limiter;
        this.clients = clients;
        this.metrics = metrics;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (LIMITED_ROUTES.stream().noneMatch(route -> route.matches(exchange))) {
            return chain.filter(exchange);
        }
        Duration wait = limiter.tryAcquire(clients.resolve(exchange));
        if (wait.isZero()) {
            return chain.filter(exchange);
        }
        metrics.rateLimitRejected(OperationalMetrics.Limiter.WRITE);
        return Mono.error(new RateLimitExceededException(wait));
    }

    private static Route route(HttpMethod method, String pattern) {
        return new Route(method, PathPatternParser.defaultInstance.parse(pattern));
    }
}
