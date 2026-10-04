package com.ticketflow.testsupport;

import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Builds the {@link WebTestClient}s that a test creates by hand ({@code bindToController(...)} and the like) with
 * the shared {@link TestTimeouts#RESPONSE} timeout (DP-035). Clients injected by Spring Boot
 * ({@code @Autowired WebTestClient}, with or without a server) get the same timeout from the test property
 * {@code spring.test.webtestclient.timeout}; a hand-built client does not read it, so it goes through here.
 */
public final class TestWebClients {

    private TestWebClients() {}

    /** Same as {@code spec.build()} but with the shared response timeout. */
    public static WebTestClient build(WebTestClient.MockServerSpec<?> spec) {
        return spec.configureClient().responseTimeout(TestTimeouts.RESPONSE).build();
    }

    /** Same as {@code builder.build()} but with the shared response timeout. */
    public static WebTestClient build(WebTestClient.Builder builder) {
        return builder.responseTimeout(TestTimeouts.RESPONSE).build();
    }
}
