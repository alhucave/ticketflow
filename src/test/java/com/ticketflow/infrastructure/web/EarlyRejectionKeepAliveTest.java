package com.ticketflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * F-033 / DP-039: a request rejected early (401 admin key, 405, 413, 415) whose body the server never consumed must
 * not leave the keep-alive connection in a state where the NEXT request gets no answer. Raw sockets, one connection,
 * real server on a real port; the follow-up is a trivial 404 like the request that hung in CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "ticketflow.rate-limit.capacity=1000000",
                "ticketflow.rate-limit.refill-per-second=100000",
                "ticketflow.rate-limit.admin-failure-capacity=1000000"})
class EarlyRejectionKeepAliveTest {

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        EarlyRejectionTestSupport.unreachableDependencies(registry);
    }

    @LocalServerPort
    private int port;

    @Test
    void everyEarlyRejection_leavesTheConnectionAnsweringTheNextRequest() {
        var probe = new EarlyRejectionProbe("127.0.0.1", port, EarlyRejectionTestSupport.deadline(), null);

        List<EarlyRejectionProbe.Anomaly> anomalies =
                EarlyRejectionTestSupport.runAll(probe, probe.rejectionScenarios());

        assertThat(anomalies).as("anomalies after early rejections on a reused connection").isEmpty();
    }
}
