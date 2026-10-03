package com.ticketflow.infrastructure.web;

import com.ticketflow.infrastructure.config.RateLimitConfig;
import com.ticketflow.infrastructure.observability.OperationalMetrics;
import com.ticketflow.infrastructure.web.error.AdminAccessDeniedException;
import com.ticketflow.infrastructure.web.error.RateLimitExceededException;
import com.ticketflow.infrastructure.web.ratelimit.ClientAddressResolver;
import com.ticketflow.infrastructure.web.ratelimit.ClientRateLimiter;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Protects the admin routes with the {@code X-Admin-Key} header (secret from
 * {@code ticketflow.admin.api-key}, env {@code ADMIN_API_KEY}). Runs before routing and body
 * handling, so an unauthorised caller learns nothing about the event or the payload. To protect
 * another admin route, add its pattern to {@link #ADMIN_ROUTES}. Failures are rendered by
 * {@code ProblemWebExceptionHandler} as {@code application/problem+json}.
 *
 * <p>Brute-force protection: every wrong or missing key charges a token of the client's failure
 * budget ({@code ticketflow.rate-limit.admin-failure-*}); once it is spent the client gets {@code 429}
 * with {@code Retry-After} WITHOUT the key being checked, so even the right key is refused until a
 * token refills. The disabled case (403, no key configured) is not a guess and is not charged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AdminKeyWebFilter implements WebFilter {

    public static final String HEADER = "X-Admin-Key";

    /** Every HTTP method on these paths requires the key (a wrong method must not reveal anything either). */
    static final List<PathPattern> ADMIN_ROUTES = List.of(
            PathPatternParser.defaultInstance.parse("/events/{id}/complimentary"));

    private final AdminKeyGuard guard;
    private final ClientAddressResolver clients;
    private final ClientRateLimiter failures;
    private final OperationalMetrics metrics;

    public AdminKeyWebFilter(String apiKey, ClientAddressResolver clients, ClientRateLimiter failures) {
        this(apiKey, clients, failures, OperationalMetrics.NOOP);
    }

    @Autowired
    public AdminKeyWebFilter(@Value("${ticketflow.admin.api-key:}") String apiKey, ClientAddressResolver clients,
                             @Qualifier(RateLimitConfig.ADMIN_FAILURE_LIMITER) ClientRateLimiter failures,
                             ObjectProvider<OperationalMetrics> metrics) {
        this(apiKey, clients, failures, metrics.getIfAvailable(() -> OperationalMetrics.NOOP));
    }

    public AdminKeyWebFilter(String apiKey, ClientAddressResolver clients, ClientRateLimiter failures,
                             OperationalMetrics metrics) {
        this.metrics = metrics;
        this.guard = new AdminKeyGuard(apiKey);
        this.clients = clients;
        this.failures = failures;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var path = exchange.getRequest().getPath().pathWithinApplication();
        if (ADMIN_ROUTES.stream().noneMatch(route -> route.matches(path))) {
            return chain.filter(exchange);
        }
        String client = clients.resolve(exchange);
        Duration lockedOut = failures.blockedFor(client);
        if (!lockedOut.isZero()) {
            metrics.rateLimitRejected(OperationalMetrics.Limiter.ADMIN_FAILURE);
            return Mono.error(new RateLimitExceededException(lockedOut));
        }
        try {
            guard.check(exchange.getRequest().getHeaders().getFirst(HEADER));
        } catch (AdminAccessDeniedException denied) {
            if (!denied.isDisabled()) {
                failures.penalize(client);
            }
            return Mono.error(denied);
        }
        return chain.filter(exchange);
    }

    @Override
    public String toString() {
        return "AdminKeyWebFilter[" + guard + "]";
    }
}
