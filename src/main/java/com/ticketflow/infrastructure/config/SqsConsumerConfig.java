package com.ticketflow.infrastructure.config;

import com.ticketflow.infrastructure.messaging.SqsOrderConsumer;
import com.ticketflow.infrastructure.messaging.SqsQueueUrlResolver;
import com.ticketflow.usecase.ProcessOrderUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/** Wires the order consumer; only active with {@code ticketflow.sqs.consumer.enabled=true}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ticketflow.sqs.consumer", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({SqsProperties.class, SqsConsumerProperties.class})
public class SqsConsumerConfig {

    @Bean
    SqsOrderConsumer sqsOrderConsumer(SqsAsyncClient client, SqsProperties sqs,
                                      SqsConsumerProperties consumer, ProcessOrderUseCase useCase) {
        return new SqsOrderConsumer(client, queueUrl(client, sqs), useCase, consumer);
    }

    static Mono<String> queueUrl(SqsAsyncClient client, SqsProperties sqs) {
        return sqs.hasQueueUrl()
                ? Mono.just(sqs.ordersQueueUrl())
                : SqsQueueUrlResolver.byName(client, sqs.ordersQueueName());
    }
}
