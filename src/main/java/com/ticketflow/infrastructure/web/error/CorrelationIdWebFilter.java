package com.ticketflow.infrastructure.web.error;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Assigns a correlation id to every request: reads {@code X-Correlation-Id} (validated, see
 * {@link CorrelationId}) or generates one, stores it as an exchange attribute, writes it to the Reactor
 * context under {@link CorrelationId#KEY} (which propagates it to the logging MDC and to the SQS
 * publisher) and echoes it in the response header of every response, successful or not.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String id = CorrelationId.resolve(exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));
        exchange.getAttributes().put(CorrelationId.KEY, id);
        var response = exchange.getResponse();
        response.getHeaders().set(CorrelationId.HEADER, id);
        // Error handlers may reset response headers; set it again right before the response is committed.
        response.beforeCommit(() -> {
            response.getHeaders().set(CorrelationId.HEADER, id);
            return Mono.empty();
        });
        return chain.filter(exchange).contextWrite(context -> context.put(CorrelationId.KEY, id));
    }
}
