package com.ticketflow.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.infrastructure.observability.MicrometerMetrics;
import com.ticketflow.infrastructure.observability.OperationalMetrics;
import com.ticketflow.infrastructure.observability.QueueDepthMonitor;
import com.ticketflow.infrastructure.persistence.DynamoDbTables;
import com.ticketflow.usecase.BusinessMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

class ObservabilityConfigTest {

    private final DynamoDbAsyncClient dynamo = mock(DynamoDbAsyncClient.class);
    private final SqsAsyncClient sqs = mock(SqsAsyncClient.class);

    private ApplicationContextRunner runner() {
        when(sqs.getQueueAttributes(any(GetQueueAttributesRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetQueueAttributesResponse.builder().attributes(java.util.Map.of(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0",
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "0")).build()));
        when(sqs.getQueueUrl(any(GetQueueUrlRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetQueueUrlResponse.builder().queueUrl("http://sqs.test/000000000000/orders").build()));
        return new ApplicationContextRunner()
                .withUserConfiguration(ObservabilityConfig.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(DynamoDbAsyncClient.class, () -> dynamo)
                .withBean(SqsAsyncClient.class, () -> sqs);
    }

    @Test
    void context_defaults_oneMetricsBeanForBothPortsAndNoQueuePoller() {
        runner().run(context -> {
            assertThat(context).hasSingleBean(MicrometerMetrics.class);
            assertThat(context.getBean(BusinessMetrics.class)).isSameAs(context.getBean(OperationalMetrics.class));
            assertThat(context).doesNotHaveBean(QueueDepthMonitor.class);
            var props = context.getBean(ObservabilityProperties.class);
            assertThat(props.queueMetrics().enabled()).isFalse();
            assertThat(props.queueMetrics().interval()).isEqualTo(Duration.ofSeconds(15));
            assertThat(props.queueMetrics().timeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(props.health().timeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(props.health().cacheTtl()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void context_queueMetricsEnabled_createsTheMonitorAndRegistersItsGaugesAtStartup() {
        runner().withPropertyValues("ticketflow.observability.queue-metrics.enabled=true",
                "ticketflow.observability.queue-metrics.interval=30s").run(context -> {
            assertThat(context).hasSingleBean(QueueDepthMonitor.class);
            assertThat(context.getBean(QueueDepthMonitor.class).isRunning()).isTrue();
            assertThat(context.getBean(MeterRegistry.class).find("ticketflow.queue.messages").gauges()).hasSize(4);
        });
    }

    @Test
    void context_queueMetricsEnabledWithQueueUrl_doesNotResolveTheQueueName() {
        runner().withPropertyValues("ticketflow.observability.queue-metrics.enabled=true",
                "ticketflow.sqs.orders-queue-url=http://sqs.test/000000000000/orders").run(context -> {
            assertThat(context).hasSingleBean(QueueDepthMonitor.class);
            org.mockito.Mockito.verify(sqs, org.mockito.Mockito.never()).getQueueUrl(any(GetQueueUrlRequest.class));
        });
    }

    @Test
    void context_registersReadinessIndicatorsNamedDynamodbAndSqs() {
        runner().run(context -> assertThat(context.getBeansOfType(ReactiveHealthIndicator.class).keySet())
                .containsExactlyInAnyOrder("dynamodbHealthIndicator", "sqsHealthIndicator"));
    }

    @Test
    void dynamodbIndicator_describesTheOrdersTable_upWhenItAnswersDownWhenItFails() {
        when(dynamo.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DescribeTableResponse.builder().build()));
        runner().withPropertyValues("ticketflow.observability.health.cache-ttl=0s").run(context -> {
            var indicator = context.getBean("dynamodbHealthIndicator", ReactiveHealthIndicator.class);
            assertThat(indicator.health().block(Duration.ofSeconds(5)).getStatus()).isEqualTo(Status.UP);
            var request = ArgumentCaptor.forClass(DescribeTableRequest.class);
            verify(dynamo).describeTable(request.capture());
            assertThat(request.getValue().tableName()).isEqualTo(DynamoDbTables.ORDERS);

            when(dynamo.describeTable(any(DescribeTableRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("unreachable")));
            assertThat(indicator.health().block(Duration.ofSeconds(5)).getStatus()).isEqualTo(Status.DOWN);
        });
    }

    @Test
    void sqsIndicator_byQueueName_resolvesTheQueue_downWhenMissing() {
        runner().withPropertyValues("ticketflow.observability.health.cache-ttl=0s",
                "ticketflow.sqs.orders-queue-name=orders").run(context -> {
            var indicator = context.getBean("sqsHealthIndicator", ReactiveHealthIndicator.class);
            assertThat(indicator.health().block(Duration.ofSeconds(5)).getStatus()).isEqualTo(Status.UP);

            when(sqs.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("no such queue")));
            assertThat(indicator.health().block(Duration.ofSeconds(5)).getStatus()).isEqualTo(Status.DOWN);
        });
    }

    @Test
    void sqsIndicator_byQueueUrl_readsTheQueueAttributes() {
        runner().withPropertyValues("ticketflow.observability.health.cache-ttl=0s",
                "ticketflow.sqs.orders-queue-url=http://sqs.test/000000000000/orders").run(context -> {
            var indicator = context.getBean("sqsHealthIndicator", ReactiveHealthIndicator.class);
            assertThat(indicator.health().block(Duration.ofSeconds(5)).getStatus()).isEqualTo(Status.UP);
            var request = ArgumentCaptor.forClass(GetQueueAttributesRequest.class);
            verify(sqs, org.mockito.Mockito.atLeastOnce()).getQueueAttributes(request.capture());
            assertThat(request.getValue().queueUrl()).isEqualTo("http://sqs.test/000000000000/orders");
        });
    }

    @Test
    void properties_invalidValues_areRejected() {
        assertThatThrownBy(() -> new ObservabilityProperties.QueueMetrics(true, Duration.ofMillis(500),
                Duration.ofSeconds(5))).hasMessageContaining("queue-metrics.interval");
        assertThatThrownBy(() -> new ObservabilityProperties.QueueMetrics(true, Duration.ofSeconds(15),
                Duration.ofMillis(10))).hasMessageContaining("queue-metrics.timeout");
        assertThatThrownBy(() -> new ObservabilityProperties.Health(Duration.ofMillis(10), Duration.ofSeconds(5)))
                .hasMessageContaining("health.timeout");
        assertThatThrownBy(() -> new ObservabilityProperties.Health(Duration.ofSeconds(2), Duration.ofSeconds(-1)))
                .hasMessageContaining("health.cache-ttl");
    }
}
