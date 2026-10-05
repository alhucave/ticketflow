package com.ticketflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * F-033 / DP-039, the 429 halves: with a client's write budget spent (it never refills during the test) or the
 * client locked out after wrong admin keys, every request is rejected by the rate limiter before its body is read,
 * and the next request on the same connection must still be answered. Clients are told apart by X-Forwarded-For
 * (trusted in this context only). The write budget is 1 token, the admin-failure budget 1 token, and the write
 * budget is large enough elsewhere that the lockout, not the write limiter, is what answers the admin scenario.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "ticketflow.rate-limit.capacity=1",
                "ticketflow.rate-limit.refill-per-second=0.0001", "ticketflow.rate-limit.admin-failure-capacity=1",
                "ticketflow.rate-limit.admin-failure-refill-per-second=0.0001",
                "ticketflow.rate-limit.trust-forwarded-for=true"})
class EarlyRejectionRateLimitKeepAliveTest {

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        EarlyRejectionTestSupport.unreachableDependencies(registry);
    }

    @LocalServerPort
    private int port;

    @Test
    void writeBudgetSpent_429IsAnsweredAndTheConnectionKeepsAnsweringTheNextRequest() throws IOException {
        var probe = new EarlyRejectionProbe("127.0.0.1", port, EarlyRejectionTestSupport.deadline(), "10.1.0.1");
        var anomalies = new ArrayList<EarlyRejectionProbe.Anomaly>();
        // The first write of this client passes the limiter (and then fails validation): the budget is spent.
        var spent = probe.once(probe.writeBudgetScenarios().get(0), "spend");
        assertThat(spent.status()).isNotEqualTo(429);

        anomalies.addAll(EarlyRejectionTestSupport.runAll(probe, probe.writeBudgetScenarios()));

        assertThat(anomalies).as("anomalies after write-budget 429s on a reused connection").isEmpty();
    }
}
