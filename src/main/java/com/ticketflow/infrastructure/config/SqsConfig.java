package com.ticketflow.infrastructure.config;

import com.ticketflow.domain.port.OrderQueuePublisher;
import com.ticketflow.infrastructure.messaging.SqsOrderQueuePublisher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder;

/** Wires the SQS async client and the {@link OrderQueuePublisher} adapter. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SqsProperties.class)
public class SqsConfig {

    @Bean(destroyMethod = "close")
    SqsAsyncClient sqsAsyncClient(SqsProperties properties) {
        return buildClient(properties, SqsAsyncClient.builder());
    }

    @Bean
    OrderQueuePublisher orderQueuePublisher(SqsAsyncClient client, SqsProperties properties) {
        return properties.hasQueueUrl()
                ? SqsOrderQueuePublisher.forQueueUrl(client, properties.ordersQueueUrl())
                : SqsOrderQueuePublisher.forQueueName(client, properties.ordersQueueName());
    }

    static SqsAsyncClient buildClient(SqsProperties properties, SqsAsyncClientBuilder builder) {
        builder.region(Region.of(properties.region())).credentialsProvider(credentialsProvider(properties));
        if (properties.endpoint() != null) {
            builder.endpointOverride(properties.endpoint());
        }
        return builder.build();
    }

    static AwsCredentialsProvider credentialsProvider(SqsProperties properties) {
        if (properties.hasStaticCredentials()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
