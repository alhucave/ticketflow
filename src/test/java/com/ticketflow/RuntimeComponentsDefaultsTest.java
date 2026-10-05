package com.ticketflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ticketflow.infrastructure.messaging.SqsOrderConsumer;
import com.ticketflow.infrastructure.scheduler.ReservationExpirationScheduler;
import com.ticketflow.testsupport.TestTimeouts;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * DP-037: the SQS consumer and the expiration job run BY DEFAULT. The shared test configuration
 * ({@code src/test/resources/application.properties}) switches both off for the suite, so these tests start the REAL
 * application with {@code spring.config.location} pointing at the main {@code application.yml} only: the property is
 * truly ABSENT, which is what a plain {@code java -jar} sees. DynamoDB and SQS point at a closed port, which also
 * proves the graceful behaviour with the components enabled but the infrastructure unreachable: the context starts,
 * readiness is DOWN, the consumer retries with backoff and the sweeps are logged and retried, nothing crashes.
 */
class RuntimeComponentsDefaultsTest {

    private static final String[] UNREACHABLE = {
            "--spring.config.location=classpath:/application.yml",
            "--server.port=0",
            "--management.server.port=0",
            "--management.server.address=127.0.0.1",
            "--ticketflow.dynamodb.access-key-id=test",
            "--ticketflow.dynamodb.secret-access-key=test",
            "--ticketflow.sqs.access-key-id=test",
            "--ticketflow.sqs.secret-access-key=test",
            "--ticketflow.observability.health.timeout=500ms",
            "--ticketflow.observability.health.cache-ttl=0s",
            "--ticketflow.sqs.consumer.min-backoff=50ms",
            "--ticketflow.sqs.consumer.max-backoff=200ms",
            "--ticketflow.expiration.initial-delay=PT0S",
            "--ticketflow.expiration.interval=PT0.2S"};

    private static ConfigurableApplicationContext start(int closedPort, String... extra) {
        List<String> args = new ArrayList<>(List.of(UNREACHABLE));
        args.add("--ticketflow.dynamodb.endpoint=http://127.0.0.1:" + closedPort);
        args.add("--ticketflow.sqs.endpoint=http://127.0.0.1:" + closedPort);
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(TicketflowApplication.class)
                .web(WebApplicationType.REACTIVE)
                .run(args.toArray(String[]::new));
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ListAppender<ILoggingEvent> capture(Class<?> type) {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(type)).addAppender(appender);
        return appender;
    }

    private static void detach(Class<?> type, ListAppender<ILoggingEvent> appender) {
        if (appender != null) {
            ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
        }
    }

    private static long count(ListAppender<ILoggingEvent> appender, String fragment) {
        return List.copyOf(appender.list).stream().filter(e -> e.getFormattedMessage().contains(fragment)).count();
    }

    @Test
    void propertiesAbsent_infrastructureUnreachable_componentsRunRetryAndReadinessIsDown() {
        ListAppender<ILoggingEvent> consumerLogs = null;
        ListAppender<ILoggingEvent> schedulerLogs = null;
        try (ConfigurableApplicationContext context = start(closedPort())) {
            // Attached after startup: Spring Boot re-initialises logback while the context starts.
            consumerLogs = capture(SqsOrderConsumer.class);
            schedulerLogs = capture(ReservationExpirationScheduler.class);
            var consumerSeen = consumerLogs;
            var schedulerSeen = schedulerLogs;
            assertThat(context.getEnvironment().containsProperty("ticketflow.sqs.consumer.enabled")).isFalse();
            assertThat(context.getEnvironment().containsProperty("ticketflow.expiration.enabled")).isFalse();
            assertThat(context.getBean(SqsOrderConsumer.class).isRunning()).isTrue();
            assertThat(context.getBean(ReservationExpirationScheduler.class).isRunning()).isTrue();

            // Consumer: ReceiveMessage keeps failing and is retried with backoff (attempt counter grows), never dies.
            await().atMost(TestTimeouts.WAIT).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                    assertThat(count(consumerSeen, "retrying with backoff")).isGreaterThanOrEqualTo(2));
            // Expiry job: each failed sweep is logged and the next interval tries again.
            await().atMost(TestTimeouts.WAIT).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                    assertThat(count(schedulerSeen, "will retry at the next interval")).isGreaterThanOrEqualTo(2));
            assertThat(context.getBean(SqsOrderConsumer.class).isRunning()).isTrue();
            assertThat(context.getBean(ReservationExpirationScheduler.class).isRunning()).isTrue();

            // Readiness DOWN (status only), liveness UP: the app is alive but not ready, it is not restarted.
            int managementPort = Integer.parseInt(context.getEnvironment().getProperty("local.management.port"));
            await().atMost(TestTimeouts.WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    assertThat(get(managementPort, "/actuator/health/readiness"))
                            .isEqualTo("503 {\"status\":\"DOWN\"}"));
            assertThat(get(managementPort, "/actuator/health/liveness")).isEqualTo("200 {\"status\":\"UP\"}");
            assertThat(context.isRunning()).isTrue();
        } finally {
            detach(SqsOrderConsumer.class, consumerLogs);
            detach(ReservationExpirationScheduler.class, schedulerLogs);
        }
    }

    @Test
    void explicitFalse_apiOnlyRole_noConsumerNoScheduler() {
        try (ConfigurableApplicationContext context = start(closedPort(),
                "--ticketflow.sqs.consumer.enabled=false", "--ticketflow.expiration.enabled=false")) {
            assertThat(context.getBeansOfType(SqsOrderConsumer.class)).isEmpty();
            assertThat(context.getBeansOfType(ReservationExpirationScheduler.class)).isEmpty();
        }
    }

    @Test
    void sharedTestConfiguration_switchesBothOffForTheSuite() throws IOException {
        var properties = new java.util.Properties();
        try (var in = getClass().getResourceAsStream("/application.properties")) {
            properties.load(in);
        }
        assertThat(properties).containsEntry("ticketflow.sqs.consumer.enabled", "false")
                .containsEntry("ticketflow.expiration.enabled", "false");
    }

    private static String get(int port, String path) {
        return WebClient.create("http://127.0.0.1:" + port).get().uri(path)
                .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                        .map(body -> response.statusCode().value() + " " + body))
                .block(TestTimeouts.WAIT);
    }
}
