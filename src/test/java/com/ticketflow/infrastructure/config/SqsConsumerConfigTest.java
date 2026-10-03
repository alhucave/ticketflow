package com.ticketflow.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.infrastructure.messaging.SqsOrderConsumer;
import com.ticketflow.usecase.ProcessOrderUseCase;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

class SqsConsumerConfigTest {

    private final SqsAsyncClient client = mock(SqsAsyncClient.class);

    private ApplicationContextRunner runner() {
        when(client.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(new CompletableFuture<ReceiveMessageResponse>());
        when(client.getQueueUrl(any(GetQueueUrlRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetQueueUrlResponse.builder().queueUrl("http://sqs.test/0/orders").build()));
        return new ApplicationContextRunner()
                .withUserConfiguration(SqsConsumerConfig.class)
                .withBean(SqsAsyncClient.class, () -> client)
                .withBean(ProcessOrderUseCase.class, () -> mock(ProcessOrderUseCase.class));
    }

    @Test
    void context_consumerNotEnabled_noConsumerBeanAndNoPolling() {
        runner().run(context -> {
            assertThat(context).doesNotHaveBean(SqsOrderConsumer.class);
            verify(client, never()).receiveMessage(any(ReceiveMessageRequest.class));
        });
    }

    @Test
    void context_consumerDisabledExplicitly_noConsumerBean() {
        runner().withPropertyValues("ticketflow.sqs.consumer.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SqsOrderConsumer.class));
    }

    @Test
    void context_consumerEnabled_startsPollingByQueueNameAndStopsOnClose() {
        var holder = new SqsOrderConsumer[1];
        runner().withPropertyValues("ticketflow.sqs.consumer.enabled=true", "ticketflow.sqs.orders-queue-name=orders")
                .run(context -> {
                    holder[0] = context.getBean(SqsOrderConsumer.class);
                    assertThat(holder[0].isRunning()).isTrue();
                    verify(client).getQueueUrl(any(GetQueueUrlRequest.class));
                    verify(client).receiveMessage(any(ReceiveMessageRequest.class));
                });
        assertThat(holder[0].isRunning()).isFalse();
    }

    @Test
    void context_consumerEnabledWithQueueUrl_doesNotResolveName() {
        runner().withPropertyValues("ticketflow.sqs.consumer.enabled=true",
                        "ticketflow.sqs.orders-queue-url=http://sqs.test/0/custom")
                .run(context -> {
                    verify(client, never()).getQueueUrl(any(GetQueueUrlRequest.class));
                    verify(client).receiveMessage(any(ReceiveMessageRequest.class));
                });
    }

    @Test
    void properties_boundFromConfiguration_overrideDefaults() {
        runner().withPropertyValues("ticketflow.sqs.consumer.enabled=true", "ticketflow.sqs.consumer.batch-size=5",
                        "ticketflow.sqs.consumer.wait-time=10s", "ticketflow.sqs.consumer.visibility-timeout=45s",
                        "ticketflow.sqs.consumer.concurrency=2", "ticketflow.sqs.consumer.shutdown-timeout=5s",
                        "ticketflow.sqs.consumer.min-backoff=2s", "ticketflow.sqs.consumer.max-backoff=20s")
                .run(context -> assertThat(context.getBean(SqsConsumerProperties.class))
                        .isEqualTo(new SqsConsumerProperties(true, 5, Duration.ofSeconds(10),
                                Duration.ofSeconds(45), 2, Duration.ofSeconds(5), Duration.ofSeconds(2),
                                Duration.ofSeconds(20))));
    }

    @Test
    void properties_defaults_areSensible() {
        runner().withPropertyValues("ticketflow.sqs.consumer.enabled=true")
                .run(context -> assertThat(context.getBean(SqsConsumerProperties.class))
                        .isEqualTo(new SqsConsumerProperties(true, 10, Duration.ofSeconds(20),
                                Duration.ofSeconds(30), 4, Duration.ofSeconds(25), Duration.ofSeconds(1),
                                Duration.ofSeconds(30))));
    }

    @Test
    void properties_invalidValues_failFast() {
        var s = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new SqsConsumerProperties(true, 0, s, s, 1, s, s, s))
                .hasMessageContaining("batch-size");
        assertThatThrownBy(() -> new SqsConsumerProperties(true, 11, s, s, 1, s, s, s))
                .hasMessageContaining("batch-size");
        assertThatThrownBy(() -> new SqsConsumerProperties(true, 10, Duration.ZERO, s, 1, s, s, s))
                .hasMessageContaining("wait-time");
        assertThatThrownBy(() -> new SqsConsumerProperties(true, 10, Duration.ofSeconds(21), s, 1, s, s, s))
                .hasMessageContaining("wait-time");
        assertThatThrownBy(() -> new SqsConsumerProperties(true, 10, s, Duration.ZERO, 1, s, s, s))
                .hasMessageContaining("visibility-timeout");
        assertThatThrownBy(() -> new SqsConsumerProperties(true, 10, s, s, 0, s, s, s))
                .hasMessageContaining("concurrency");
        assertThatThrownBy(() -> new SqsConsumerProperties(true, 10, s, s, 1, Duration.ofSeconds(-1), s, s))
                .hasMessageContaining("shutdown-timeout");
        assertThatThrownBy(() -> new SqsConsumerProperties(true, 10, s, s, 1, s, Duration.ZERO, s))
                .hasMessageContaining("min-backoff");
        assertThatThrownBy(() -> new SqsConsumerProperties(true, 10, s, s, 1, s, Duration.ofSeconds(2), s))
                .hasMessageContaining("max-backoff");
    }

    @Test
    void queueUrl_byNameIsResolvedLazilyAndCached() {
        when(client.getQueueUrl(any(GetQueueUrlRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetQueueUrlResponse.builder().queueUrl("http://sqs.test/0/orders").build()));
        var url = SqsConsumerConfig.queueUrl(client,
                new SqsProperties(null, "us-east-1", null, null, "orders", null));
        verify(client, never()).getQueueUrl(any(GetQueueUrlRequest.class));

        StepVerifier.create(url).expectNext("http://sqs.test/0/orders").verifyComplete();
        StepVerifier.create(url).expectNext("http://sqs.test/0/orders").verifyComplete();

        verify(client).getQueueUrl(any(GetQueueUrlRequest.class));
    }
}
