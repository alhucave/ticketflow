package com.ticketflow.infrastructure.config;

import com.ticketflow.infrastructure.messaging.SqsQueueUrlResolver;
import com.ticketflow.infrastructure.observability.DependencyHealthIndicator;
import com.ticketflow.infrastructure.observability.MicrometerMetrics;
import com.ticketflow.infrastructure.observability.OperationalMetrics;
import com.ticketflow.infrastructure.observability.QueueDepthMonitor;
import com.ticketflow.infrastructure.persistence.DynamoDbTables;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Metrics, queue depth gauges and readiness indicators (see docs/observability.md). The two readiness
 * indicators are named {@code dynamodb} and {@code sqs} by Spring Boot (bean name minus the
 * {@code HealthIndicator} suffix) and are included in the {@code readiness} group only (application.yml);
 * the {@code liveness} group never depends on an external system.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ObservabilityProperties.class, SqsProperties.class})
public class ObservabilityConfig {

    /** One bean implementing both metric ports ({@code BusinessMetrics} and {@code OperationalMetrics}). */
    @Bean
    MicrometerMetrics micrometerMetrics(MeterRegistry registry) {
        return new MicrometerMetrics(registry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ticketflow.observability.queue-metrics", name = "enabled",
            havingValue = "true")
    QueueDepthMonitor queueDepthMonitor(SqsAsyncClient client, SqsProperties sqs,
                                        ObservabilityProperties observability, OperationalMetrics metrics,
                                        MeterRegistry registry) {
        return new QueueDepthMonitor(client, SqsConsumerConfig.queueUrl(client, sqs),
                dlqName -> SqsQueueUrlResolver.byName(client, dlqName), observability.queueMetrics(), metrics,
                registry);
    }

    /** DynamoDB reachable and the orders table present (it is created at startup when provisioning is on). */
    @Bean
    ReactiveHealthIndicator dynamodbHealthIndicator(DynamoDbAsyncClient client, ObservabilityProperties properties) {
        return new DependencyHealthIndicator("dynamodb",
                () -> Mono.fromFuture(() -> client.describeTable(
                        DescribeTableRequest.builder().tableName(DynamoDbTables.ORDERS).build())),
                properties.health().timeout(), properties.health().cacheTtl());
    }

    /** The orders queue resolves (exists and SQS answers). */
    @Bean
    ReactiveHealthIndicator sqsHealthIndicator(SqsAsyncClient client, SqsProperties sqs,
                                               ObservabilityProperties properties) {
        return new DependencyHealthIndicator("sqs", () -> sqsProbe(client, sqs),
                properties.health().timeout(), properties.health().cacheTtl());
    }

    static Mono<?> sqsProbe(SqsAsyncClient client, SqsProperties sqs) {
        return sqs.hasQueueUrl()
                ? Mono.fromFuture(() -> client.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(sqs.ordersQueueUrl()).attributeNames(QueueAttributeName.QUEUE_ARN).build()))
                : Mono.fromFuture(() -> client.getQueueUrl(
                        GetQueueUrlRequest.builder().queueName(sqs.ordersQueueName()).build()));
    }
}
