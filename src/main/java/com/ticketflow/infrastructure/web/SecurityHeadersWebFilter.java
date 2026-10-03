package com.ticketflow.infrastructure.web;

import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Adds cheap defensive headers to EVERY response, errors and 4xx/5xx written by the problem handlers
 * included. The API only serves JSON and event streams, never documents: responses must not be
 * sniffed, framed, cached by shared caches, or leak the URL through {@code Referer}.
 *
 * <p>They are set when the exchange starts and again right before commit, because error handlers may
 * reset response headers (same approach as the correlation id filter). {@code Strict-Transport-Security}
 * is deliberately absent: it only makes sense over TLS, which is terminated at the edge layer.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class SecurityHeadersWebFilter implements WebFilter {

    static final Map<String, String> HEADERS = Map.of(
            "X-Content-Type-Options", "nosniff",
            HttpHeaders.CACHE_CONTROL, "no-store",
            "Referrer-Policy", "no-referrer",
            "X-Frame-Options", "DENY",
            "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'",
            "Cross-Origin-Resource-Policy", "same-origin");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var response = exchange.getResponse();
        HEADERS.forEach(response.getHeaders()::set);
        response.beforeCommit(() -> {
            HEADERS.forEach(response.getHeaders()::set);
            return Mono.empty();
        });
        return chain.filter(exchange);
    }
}
