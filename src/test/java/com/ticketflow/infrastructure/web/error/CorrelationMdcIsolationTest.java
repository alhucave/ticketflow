package com.ticketflow.infrastructure.web.error;

import com.ticketflow.testsupport.TestTimeouts;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ticketflow.infrastructure.config.CorrelationConfig;
import com.ticketflow.infrastructure.config.RateLimitConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.scheduler.Schedulers;

/** The MDC must follow each request across threads and never leak to another request or thread. */
@WebFluxTest(controllers = ErrorProbeController.class)
@Import({ApiExceptionHandler.class, ProblemWebExceptionHandler.class, CorrelationIdWebFilter.class,
        CorrelationConfig.class, RateLimitConfig.class})
class CorrelationMdcIsolationTest {

    private static final int REQUESTS = 120;

    @Autowired
    private WebTestClient client;

    @Test
    void concurrentRequests_withDifferentIds_neverSeeEachOthersIdInLogs() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(24)) {
                for (int i = 0; i < REQUESTS; i++) {
                    String id = "req-" + i;
                    long delay = ThreadLocalRandom.current().nextLong(1, 60);
                    futures.add(pool.submit(() -> {
                        start.await();
                        client.get().uri("/probe/log/{d}", delay).header(CorrelationId.HEADER, id).exchange()
                                .expectStatus().isOk()
                                .expectHeader().valueEquals(CorrelationId.HEADER, id);
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get();
                }
            }

            List<ILoggingEvent> lines = appender.list.stream()
                    .filter(e -> e.getFormattedMessage().startsWith("probe-log-line")).toList();
            assertThat(lines).hasSize(REQUESTS);
            for (ILoggingEvent line : lines) {
                String claimed = line.getFormattedMessage().substring("probe-log-line claimed=".length());
                assertThat(line.getMDCPropertyMap()).containsEntry(CorrelationId.KEY, claimed);
            }

            // Leak check: unrelated tasks on the scheduler threads that served requests see no id.
            List<String> leaked = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(200);
            for (int i = 0; i < 200; i++) {
                (i % 2 == 0 ? Schedulers.boundedElastic() : Schedulers.parallel()).schedule(() -> {
                    String value = MDC.get(CorrelationId.KEY);
                    if (value != null) {
                        leaked.add(value);
                    }
                    done.countDown();
                });
            }
            assertThat(done.await(TestTimeouts.WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThat(leaked).isEmpty();
            assertThat(MDC.get(CorrelationId.KEY)).isNull();
        } finally {
            root.detachAppender(appender);
        }
    }
}
