package com.ticketflow.testsupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * An injected WebTestClient of a {@code @WebFluxTest} slice takes the shared timeout from the test property
 * (DP-035). It would fail with the 5 s default: the handler answers after 5.5 s.
 */
@WebFluxTest(controllers = SlowProbeController.class)
@ContextConfiguration(classes = SlowProbeController.class)
class InjectedWebTestClientTimeoutTest {

    @Autowired
    private WebTestClient client;

    @Test
    void sliceClient_waitsForAHandlerSlowerThanTheLibraryDefault() {
        client.get().uri("/slow").exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("late");
    }
}
