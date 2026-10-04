package com.ticketflow.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Root cause of the F-028 flake family and proof of the shared mechanism (DP-035), without a Spring context: a
 * handler that answers after 5.5 s makes a default WebTestClient fail with the exact exception seen in CI
 * ("Timeout on blocking read"), and the client built through the shared helper does not.
 */
class TestTimeoutsTest {

    @Test
    void defaultWebTestClient_failsWhenTheHandlerAnswersAfterFiveSeconds() {
        WebTestClient defaultClient = WebTestClient.bindToController(new SlowProbeController()).build();

        assertThatThrownBy(() -> defaultClient.get().uri("/slow").exchange())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Timeout on blocking read for 5000000000 NANOSECONDS");
    }

    @Test
    void helperBuiltClient_waitsForTheSameSlowHandler() {
        WebTestClient client = TestWebClients.build(WebTestClient.bindToController(new SlowProbeController()));

        client.get().uri("/slow").exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("late");
    }

    @Test
    void helperBuiltClient_fromABuilder_waitsForTheSameSlowHandler() {
        WebTestClient client = TestWebClients.build(
                WebTestClient.bindToController(new SlowProbeController()).configureClient());

        client.get().uri("/slow").exchange().expectStatus().isOk();
    }

    @Test
    void testProperty_equalsTheSharedConstant_soInjectedClientsAndHandBuiltOnesAgree() throws IOException {
        Properties properties = new Properties();
        try (var in = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(in);
        }

        Duration configured = DurationStyle.detectAndParse(properties.getProperty("spring.test.webtestclient.timeout"));

        assertThat(configured).isEqualTo(TestTimeouts.RESPONSE);
    }

    @Test
    void deadlines_areGenerousEnoughToOutliveASlowRunner() {
        assertThat(TestTimeouts.RESPONSE).isGreaterThanOrEqualTo(Duration.ofSeconds(30));
        assertThat(TestTimeouts.WAIT).isGreaterThanOrEqualTo(TestTimeouts.RESPONSE);
    }
}
