package com.ticketflow.infrastructure.observability;

import com.ticketflow.testsupport.TestTimeouts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.infrastructure.config.ObservabilityProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

class QueueDepthMonitorTest {

    private static final String ORDERS = "http://sqs.test/000000000000/orders";
    private static final String DLQ = "http://sqs.test/000000000000/orders-dlq";
    private static final String REDRIVE =
            "{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:orders-dlq\",\"maxReceiveCount\":3}";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SqsAsyncClient client = mock(SqsAsyncClient.class);
    private final VirtualTimeScheduler timer = VirtualTimeScheduler.create();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2030-01-01T10:00:00Z"));
    private final List<Boolean> refreshes = new ArrayList<>();
    private final List<String> resolvedDlqNames = new ArrayList<>();
    private QueueDepthMonitor monitor;

    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private final OperationalMetrics metrics = new OperationalMetrics() {
        @Override
        public void queueStatsRefreshed(boolean success) {
            refreshes.add(success);
        }
    };

    @BeforeEach
    void setUp() {
        monitor = new QueueDepthMonitor(client, Mono.just(ORDERS), name -> {
            resolvedDlqNames.add(name);
            return Mono.just(DLQ);
        }, new ObservabilityProperties.QueueMetrics(true, Duration.ofSeconds(15), Duration.ofSeconds(5)), metrics,
                registry, timer, clock);
    }

    @AfterEach
    void tearDown() {
        monitor.stop();
        timer.dispose();
    }

    private static CompletableFuture<GetQueueAttributesResponse> attrs(int visible, int inFlight, String redrive) {
        var map = new java.util.HashMap<QueueAttributeName, String>();
        map.put(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, Integer.toString(visible));
        map.put(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, Integer.toString(inFlight));
        if (redrive != null) {
            map.put(QueueAttributeName.REDRIVE_POLICY, redrive);
        }
        return CompletableFuture.completedFuture(GetQueueAttributesResponse.builder().attributes(map).build());
    }

    private void queueReturns(String url, CompletableFuture<GetQueueAttributesResponse> response) {
        when(client.getQueueAttributes(org.mockito.ArgumentMatchers.<GetQueueAttributesRequest>argThat(
                request -> request != null && url.equals(request.queueUrl())))).thenReturn(response);
    }

    private double gauge(String queue, String state) {
        return registry.get("ticketflow.queue.messages").tag("queue", queue).tag("state", state).gauge().value();
    }

    @Test
    void gauges_beforeTheFirstRefresh_areNaNNotZero() {
        assertThat(gauge("orders", "visible")).isNaN();
        assertThat(gauge("dlq", "visible")).isNaN();
        assertThat(registry.get("ticketflow.queue.stats.age.seconds").gauge().value()).isNaN();
    }

    @Test
    void refresh_readsBothQueuesAndFindsTheDlqThroughTheRedrivePolicy() {
        queueReturns(ORDERS, attrs(7, 2, REDRIVE));
        queueReturns(DLQ, attrs(1, 0, null));

        monitor.refresh().block(TestTimeouts.WAIT);

        assertThat(gauge("orders", "visible")).isEqualTo(7);
        assertThat(gauge("orders", "in_flight")).isEqualTo(2);
        assertThat(gauge("dlq", "visible")).isEqualTo(1);
        assertThat(gauge("dlq", "in_flight")).isZero();
        assertThat(resolvedDlqNames).containsExactly("orders-dlq");
        assertThat(refreshes).containsExactly(true);
    }

    @Test
    void refresh_dlqLookupIsResolvedOnceAndReused() {
        queueReturns(ORDERS, attrs(0, 0, REDRIVE));
        queueReturns(DLQ, attrs(0, 0, null));

        monitor.refresh().block(TestTimeouts.WAIT);
        monitor.refresh().block(TestTimeouts.WAIT);

        assertThat(resolvedDlqNames).hasSize(1);
    }

    @Test
    void refresh_queueWithoutRedrivePolicy_leavesDlqUnknown() {
        queueReturns(ORDERS, attrs(3, 1, null));

        monitor.refresh().block(TestTimeouts.WAIT);

        assertThat(gauge("orders", "visible")).isEqualTo(3);
        assertThat(gauge("dlq", "visible")).isNaN();
        assertThat(resolvedDlqNames).isEmpty();
        assertThat(refreshes).containsExactly(true);
    }

    @Test
    void refresh_failure_keepsTheLastValuesAndFailsTheRefresh() {
        queueReturns(ORDERS, attrs(5, 1, REDRIVE));
        queueReturns(DLQ, attrs(2, 0, null));
        monitor.refresh().block(TestTimeouts.WAIT);
        queueReturns(ORDERS, CompletableFuture.failedFuture(new IllegalStateException("sqs down")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> monitor.refresh().block(TestTimeouts.WAIT))
                .isInstanceOf(IllegalStateException.class);

        assertThat(gauge("orders", "visible")).isEqualTo(5);
        assertThat(gauge("dlq", "visible")).isEqualTo(2);
    }

    @Test
    void ageGauge_growsUntilTheNextSuccessfulRefresh() {
        queueReturns(ORDERS, attrs(0, 0, null));
        monitor.refresh().block(TestTimeouts.WAIT);
        assertThat(registry.get("ticketflow.queue.stats.age.seconds").gauge().value()).isZero();

        now.set(now.get().plusSeconds(42));

        assertThat(registry.get("ticketflow.queue.stats.age.seconds").gauge().value()).isEqualTo(42.0);
    }

    @Test
    void start_pollsAtTheConfiguredIntervalAndCountsFailuresWithoutStopping() {
        queueReturns(ORDERS, attrs(1, 0, null));

        monitor.start();
        assertThat(monitor.isRunning()).isTrue();
        // first poll is immediate
        assertThat(refreshes).containsExactly(true);

        queueReturns(ORDERS, CompletableFuture.failedFuture(new IllegalStateException("sqs down")));
        timer.advanceTimeBy(Duration.ofSeconds(15));
        assertThat(refreshes).containsExactly(true, false);
        assertThat(gauge("orders", "visible")).isEqualTo(1);

        queueReturns(ORDERS, attrs(9, 0, null));
        timer.advanceTimeBy(Duration.ofSeconds(15));
        assertThat(refreshes).containsExactly(true, false, true);
        assertThat(gauge("orders", "visible")).isEqualTo(9);
        verify(client, times(3)).getQueueAttributes(any(GetQueueAttributesRequest.class));
    }

    @Test
    void start_slowRefresh_isAbandonedAfterTheTimeoutAndCountedAsError() {
        when(client.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(new CompletableFuture<>());

        monitor.start();
        timer.advanceTimeBy(Duration.ofSeconds(5));

        assertThat(refreshes).containsExactly(false);
    }

    @Test
    void stop_stopsPolling() {
        queueReturns(ORDERS, attrs(1, 0, null));
        monitor.start();
        monitor.stop();

        timer.advanceTimeBy(Duration.ofMinutes(5));

        assertThat(monitor.isRunning()).isFalse();
        assertThat(refreshes).hasSize(1);
    }

    @Test
    void start_twice_pollsOnlyOnce() {
        queueReturns(ORDERS, attrs(1, 0, null));
        monitor.start();
        monitor.start();

        assertThat(refreshes).hasSize(1);
    }

    @Test
    void dlqName_parsesTheArnOrReturnsNull() {
        assertThat(QueueDepthMonitor.dlqName(REDRIVE)).isEqualTo("orders-dlq");
        assertThat(QueueDepthMonitor.dlqName(null)).isNull();
        assertThat(QueueDepthMonitor.dlqName(" ")).isNull();
        assertThat(QueueDepthMonitor.dlqName("not json")).isNull();
        assertThat(QueueDepthMonitor.dlqName("{\"maxReceiveCount\":3}")).isNull();
        assertThat(QueueDepthMonitor.dlqName("{\"deadLetterTargetArn\":7}")).isNull();
        assertThat(QueueDepthMonitor.dlqName("{\"deadLetterTargetArn\":\"arn:aws:sqs:r:1:\"}")).isNull();
    }

    @Test
    void gauges_useOnlyFixedTagValues() {
        assertThat(registry.getMeters()).allSatisfy(meter -> {
            assertThat(meter.getId().getName()).startsWith("ticketflow.queue.");
            meter.getId().getTags().forEach(tag -> {
                assertThat(tag.getKey()).isIn("queue", "state");
                assertThat(tag.getValue()).isIn("orders", "dlq", "visible", "in_flight");
            });
        });
        assertThat(registry.getMeters()).hasSize(5);
    }

    @Test
    void unparsableCounts_becomeUnknownInsteadOfFailing() {
        var map = Map.of(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "oops");
        queueReturns(ORDERS, CompletableFuture.completedFuture(GetQueueAttributesResponse.builder()
                .attributes(map).build()));

        monitor.refresh().block(TestTimeouts.WAIT);

        assertThat(gauge("orders", "visible")).isNaN();
        assertThat(gauge("orders", "in_flight")).isNaN();
    }
}
