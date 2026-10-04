package com.ticketflow.testsupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Same proof for the other binding style: a real server on a random port (what every {@code *EndToEndIT} uses).
 * Without the shared timeout the 5.5 s handler would fail the exchange.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // The probe has no DynamoDB/SQS: keep the readiness group of application.yml from naming them.
        properties = "management.endpoint.health.group.readiness.include=readinessState",
        classes = InjectedServerWebTestClientTimeoutTest.Probe.class)
@AutoConfigureWebTestClient
class InjectedServerWebTestClientTimeoutTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(SlowProbeController.class)
    static class Probe {}

    @Autowired
    private WebTestClient client;

    @Test
    void serverBoundClient_waitsForAHandlerSlowerThanTheLibraryDefault() {
        client.get().uri("/slow").exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("late");
    }
}
