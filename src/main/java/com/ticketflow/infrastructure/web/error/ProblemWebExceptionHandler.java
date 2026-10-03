package com.ticketflow.infrastructure.web.error;

import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Renders errors that never reach a controller advice (unknown route, wrong method, content negotiation
 * at routing level, exceptions thrown by web filters such as a rate limiter) with the same problem+json
 * shape. Ordered before Spring Boot's {@code DefaultErrorWebExceptionHandler} (order -1), so its
 * {@code /error} attributes (path, trace, requestId) are never produced.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ProblemWebExceptionHandler implements WebExceptionHandler {

    private final ServerResponse.Context context;

    public ProblemWebExceptionHandler(ServerCodecConfigurer codecs) {
        List<HttpMessageWriter<?>> writers = codecs.getWriters();
        this.context = new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return writers;
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return List.of();
            }
        };
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            // Too late to change the status (for example an SSE stream already started): let the server abort.
            return Mono.error(ex);
        }
        ResponseEntity<?> entity = ApiExceptionHandler.translate(ex, CorrelationId.of(exchange));
        return ServerResponse.status(entity.getStatusCode())
                .headers(headers -> headers.addAll(entity.getHeaders()))
                .bodyValue(entity.getBody())
                .flatMap(response -> response.writeTo(exchange, context));
    }
}
