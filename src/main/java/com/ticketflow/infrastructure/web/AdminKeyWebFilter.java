package com.ticketflow.infrastructure.web;

import com.ticketflow.infrastructure.web.error.AdminAccessDeniedException;
import java.util.List;
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
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AdminKeyWebFilter implements WebFilter {

    public static final String HEADER = "X-Admin-Key";

    /** Every HTTP method on these paths requires the key (a wrong method must not reveal anything either). */
    static final List<PathPattern> ADMIN_ROUTES = List.of(
            PathPatternParser.defaultInstance.parse("/events/{id}/complimentary"));

    private final AdminKeyGuard guard;

    public AdminKeyWebFilter(@Value("${ticketflow.admin.api-key:}") String apiKey) {
        this.guard = new AdminKeyGuard(apiKey);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var path = exchange.getRequest().getPath().pathWithinApplication();
        if (ADMIN_ROUTES.stream().noneMatch(route -> route.matches(path))) {
            return chain.filter(exchange);
        }
        try {
            guard.check(exchange.getRequest().getHeaders().getFirst(HEADER));
        } catch (AdminAccessDeniedException denied) {
            return Mono.error(denied);
        }
        return chain.filter(exchange);
    }
}
