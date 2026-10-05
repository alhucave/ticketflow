package com.ticketflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.testsupport.TestTimeouts;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * F-033 / DP-039: the same failing sequence as {@code HardeningEndToEndIT.securityHeaders_...} (401 on the admin route
 * with a JSON body, 405, 415, then a trivial 404) driven by the client the suite really uses (Reactor Netty), but with
 * a pool of exactly ONE connection, so every request reuses the connection left by the previous rejection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "ticketflow.rate-limit.capacity=1000000",
                "ticketflow.rate-limit.refill-per-second=100000",
                "ticketflow.rate-limit.admin-failure-capacity=1000000"})
class EarlyRejectionPooledClientTest {

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        EarlyRejectionTestSupport.unreachableDependencies(registry);
    }

    @LocalServerPort
    private int port;

    private record Reply(int status, String correlationId) { }

    private static Reply read(WebClient.RequestHeadersSpec<?> request) {
        return request.exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                .map(body -> new Reply(response.statusCode().value(),
                        response.headers().asHttpHeaders().getFirst("X-Correlation-Id")))).block(TestTimeouts.WAIT);
    }

    @Test
    void onePooledConnection_everyRequestAfterAnEarlyRejectionIsAnswered() {
        ConnectionProvider onlyOne = ConnectionProvider.builder("one-connection").maxConnections(1).build();
        WebClient client = WebClient.builder().baseUrl("http://127.0.0.1:" + port)
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create(onlyOne))).build();
        List<String> anomalies = new ArrayList<>();
        try {
            for (int i = 0; i < EarlyRejectionTestSupport.iterations(); i++) {
                String[] expected = {"401", "405", "415", "413", "404"};
                Reply[] replies = {
                    read(client.post().uri("/events/evt-1/complimentary").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Correlation-Id", "c" + i + "-0").bodyValue("{\"quantity\":1}")),
                    read(client.delete().uri("/events").header("X-Correlation-Id", "c" + i + "-1")),
                    read(client.post().uri("/events").contentType(MediaType.TEXT_PLAIN)
                            .header("X-Correlation-Id", "c" + i + "-2").bodyValue("x")),
                    read(client.post().uri("/events").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Correlation-Id", "c" + i + "-3").bodyValue("a".repeat(100_000))),
                    read(client.get().uri("/actuator/health").header("X-Correlation-Id", "c" + i + "-4"))};
                for (int step = 0; step < replies.length; step++) {
                    if (replies[step].status() != Integer.parseInt(expected[step])
                            || !("c" + i + "-" + step).equals(replies[step].correlationId())) {
                        anomalies.add("iteration " + i + " step " + step + ": " + replies[step]);
                    }
                }
            }
        } finally {
            onlyOne.dispose();
        }

        assertThat(anomalies).isEmpty();
    }
}
