package com.ticketflow.infrastructure.web;

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

/** With trust-forwarded-for the proxy-appended (last) X-Forwarded-For entry identifies the client. */
@WebFluxTest(controllers = OrderController.class)
@Import({ApiExceptionHandler.class, ProblemWebExceptionHandler.class, CorrelationIdWebFilter.class,
        CorrelationConfig.class, RateLimitConfig.class})
@TestPropertySource(properties = {"ticketflow.rate-limit.capacity=2", "ticketflow.rate-limit.refill-per-second=0.01",
        "ticketflow.rate-limit.trust-forwarded-for=true"})
class RateLimitForwardedWebTest {

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private RequestPurchaseUseCase purchase;

    @MockitoBean
    private GetOrderStatusUseCase status;

    private WebTestClient.ResponseSpec post(String forwardedFor) {
        var spec = client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-0123456789abcdef");
        if (forwardedFor != null) {
            spec.header("X-Forwarded-For", forwardedFor);
        }
        return spec.bodyValue("{\"eventId\":\"evt-1\",\"quantity\":1}").exchange();
    }

    @Test
    void post_distinctForwardedClients_haveIndependentBudgetsAndSpoofedFirstEntriesDoNotHelp() {
        when(purchase.execute(any())).thenReturn(Mono.just(new RequestPurchaseResult(
                new OrderId("ord-1"), TicketStatus.RESERVED, Instant.parse("2030-01-01T10:10:00Z"), false)));

        post("1.1.1.1, 203.0.113.7").expectStatus().isAccepted();
        post("2.2.2.2, 203.0.113.7").expectStatus().isAccepted();
        // A different spoofable prefix but the same address added by the proxy: same client, over budget.
        post("3.3.3.3, 203.0.113.7").expectStatus().isEqualTo(429);
        // Another real client is unaffected.
        post("1.1.1.1, 203.0.113.8").expectStatus().isAccepted();
        // Garbage is not trusted: falls back to the (shared) socket address, which has its own budget.
        post("garbage").expectStatus().isAccepted();
        post("not-an-ip").expectStatus().isAccepted();
        post(null).expectStatus().isEqualTo(429);
    }
}
