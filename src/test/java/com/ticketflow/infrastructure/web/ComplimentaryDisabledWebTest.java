package com.ticketflow.infrastructure.web;

import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketflow.infrastructure.config.CorrelationConfig;
import com.ticketflow.infrastructure.web.error.ApiExceptionHandler;
import com.ticketflow.infrastructure.web.error.CorrelationIdWebFilter;
import com.ticketflow.infrastructure.web.error.ProblemWebExceptionHandler;
import com.ticketflow.usecase.IssueComplimentaryUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Secure by default: with no admin key configured the endpoint is closed, whatever the caller sends. */
@WebFluxTest(controllers = ComplimentaryController.class)
@Import({ApiExceptionHandler.class, ProblemWebExceptionHandler.class, CorrelationIdWebFilter.class,
        CorrelationConfig.class, AdminKeyWebFilter.class})
class ComplimentaryDisabledWebTest {

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private IssueComplimentaryUseCase useCase;

    @Test
    void issue_noKeyConfigured_is403WhateverIsSent() {
        for (String supplied : new String[] {null, "", "anything"}) {
            var spec = client.post().uri("/events/evt-1/complimentary").contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "key-1");
            if (supplied != null) {
                spec.header("X-Admin-Key", supplied);
            }
            spec.bodyValue("{\"quantity\":1}").exchange().expectStatus().isForbidden()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().jsonPath("$.type").isEqualTo("urn:ticketflow:problem:admin-disabled");
        }
        verifyNoInteractions(useCase);
    }
}
