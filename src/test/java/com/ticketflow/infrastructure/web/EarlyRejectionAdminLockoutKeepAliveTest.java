package com.ticketflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * F-033 / DP-039: an admin-failure lockout answers 429 WITHOUT checking the key or reading the body; the next request
 * on the same connection must still be answered. The write budget is huge, so only the lockout can answer 429.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "ticketflow.rate-limit.capacity=1000000",
                "ticketflow.rate-limit.refill-per-second=100000", "ticketflow.rate-limit.admin-failure-capacity=1",
                "ticketflow.rate-limit.admin-failure-refill-per-second=0.0001"})
class EarlyRejectionAdminLockoutKeepAliveTest {

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        EarlyRejectionTestSupport.unreachableDependencies(registry);
    }

    @LocalServerPort
    private int port;

    @Test
    void adminLockout_429IsAnsweredAndTheConnectionKeepsAnsweringTheNextRequest() throws IOException {
        var probe = new EarlyRejectionProbe("127.0.0.1", port, EarlyRejectionTestSupport.deadline(), null);
        var lockout = probe.adminLockoutScenarios().get(0);
        // The first wrong key is a 401 and spends the single failure token: from then on, 429.
        assertThat(probe.once(lockout, "spend").status()).isEqualTo(401);

        var anomalies = EarlyRejectionTestSupport.runAll(probe, probe.adminLockoutScenarios());

        assertThat(anomalies).as("anomalies after admin-lockout 429s on a reused connection").isEmpty();
    }
}
