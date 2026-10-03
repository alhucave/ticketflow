package com.ticketflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.infrastructure.config.CorrelationConfig;
import com.ticketflow.infrastructure.config.RateLimitConfig;
import com.ticketflow.infrastructure.web.error.ApiExceptionHandler;
import com.ticketflow.infrastructure.web.error.CorrelationIdWebFilter;
import com.ticketflow.infrastructure.web.error.ProblemWebExceptionHandler;
import com.ticketflow.usecase.GetOrderStatusUseCase;
import com.ticketflow.usecase.RequestPurchaseResult;
import com.ticketflow.usecase.RequestPurchaseUseCase;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * The real filters (security headers, rate limit, correlation id) and problem handlers around the real
 * order controller. The slow refill makes the budget deterministic: it never refills during the test.
 */
@WebFluxTest(controllers = OrderController.class)
@Import({ApiExceptionHandler.class, ProblemWebExceptionHandler.class, CorrelationIdWebFilter.class,
        CorrelationConfig.class, RateLimitConfig.class})
@TestPropertySource(properties = {"ticketflow.rate-limit.capacity=3", "ticketflow.rate-limit.refill-per-second=0.01"})
class RateLimitWebTest {

    private static final String BODY = "{\"eventId\":\"evt-1\",\"quantity\":1}";

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private RequestPurchaseUseCase purchase;

    @MockitoBean
    private GetOrderStatusUseCase status;

    private WebTestClient.ResponseSpec post(String key) {
        return client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).bodyValue(BODY).exchange();
    }

    @Test
    void post_overTheBudget_is429ProblemWithRetryAfterAndStillCarriesCorrelationIdAndSecurityHeaders() {
        when(purchase.execute(any())).thenReturn(Mono.just(new RequestPurchaseResult(
                new OrderId("ord-1"), TicketStatus.RESERVED, Instant.parse("2030-01-01T10:10:00Z"), false)));

        for (int i = 0; i < 3; i++) {
            post("key-0123456789abcdef").expectStatus().isAccepted();
        }
        post("key-0123456789abcdef").expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().value("Retry-After", value -> assertThat(Long.parseLong(value)).isBetween(90L, 100L))
                .expectHeader().exists("X-Correlation-Id")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:ticketflow:problem:rate-limit-exceeded")
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.correlationId").isNotEmpty();
    }

    @Test
    void get_isNeverLimitedWhateverTheBudget() {
        when(status.execute(any())).thenReturn(Mono.empty());
        for (int i = 0; i < 30; i++) {
            client.get().uri("/orders/ord-1").exchange().expectStatus().isOk();
        }
    }
}
