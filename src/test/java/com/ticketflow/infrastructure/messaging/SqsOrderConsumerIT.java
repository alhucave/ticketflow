package com.ticketflow.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.model.OrderId;
import com.ticketflow.infrastructure.config.SqsConsumerProperties;
import com.ticketflow.usecase.ProcessOrderResult;
import com.ticketflow.usecase.ProcessOrderUseCase;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * The consumer against a real SQS (LocalStack) with a mocked use case: ack, redelivery, redrive to
 * the DLQ and poison handling. Queues are created like the compose init script does (redrive to
 * a DLQ with maxReceiveCount=3).
 */
@Tag("integration")
class SqsOrderConsumerIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    // 2026.x images require LOCALSTACK_AUTH_TOKEN; 4.14.0 is the last that runs without one.
    private static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.14.0")).withServices("sqs");

    private static SqsAsyncClient sqs;

    private SqsOrderConsumer consumer;
    private ProcessOrderUseCase useCase;

    @BeforeAll
    static void start() {
        LOCALSTACK.start();
        sqs = SqsAsyncClient.builder().endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    @AfterAll
    static void stop() {
        sqs.close();
        LOCALSTACK.stop();
    }

    @AfterEach
    void stopConsumer() {
        if (consumer != null) {
            consumer.stop();
        }
    }

    private void startConsumer(SqsTestQueues.Queues queues, ProcessOrderUseCase useCase) {
        // Short waits/visibility keep redelivery fast; the queue's own visibility is overridden per receive.
        var props = new SqsConsumerProperties(true, 10, Duration.ofSeconds(1), Duration.ofSeconds(1), 4,
                Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(5));
        consumer = new SqsOrderConsumer(sqs, SqsQueueUrlResolver.byName(sqs, queues.name()), useCase, props);
        consumer.start();
    }

    private static void send(String queueUrl, String body) {
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(body).build()).join();
    }

    private static String valid(String orderId) {
        return "{\"version\":1,\"orderId\":\"" + orderId + "\"}";
    }

    private static ProcessOrderResult sold(OrderId id) {
        return new ProcessOrderResult.Sold(id);
    }

    private static String newName() {
        return "orders-" + UUID.randomUUID();
    }

    private static String receiveFromDlq(String dlqUrl) {
        var messages = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(dlqUrl).waitTimeSeconds(5)
                .maxNumberOfMessages(10).build()).join().messages();
        assertThat(messages).hasSize(1);
        return messages.get(0).body();
    }

    @Test
    void consumer_successfulProcessing_deletesMessageSoItIsNeverRedelivered() {
        var queues = SqsTestQueues.create(sqs, newName());
        useCase = mock(ProcessOrderUseCase.class);
        when(useCase.execute(any(OrderId.class)))
                .thenAnswer(invocation -> Mono.just(sold(invocation.getArgument(0))));
        send(queues.url(), valid("order-ok"));

        startConsumer(queues, useCase);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(SqsTestQueues.total(sqs, queues.url())).isZero());
        // Wait longer than the visibility timeout: a deleted message must not come back.
        await().during(Duration.ofSeconds(3)).atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(SqsTestQueues.total(sqs, queues.url())).isZero();
            assertThat(SqsTestQueues.total(sqs, queues.dlqUrl())).isZero();
        });
        verify(useCase, times(1)).execute(new OrderId("order-ok"));
    }

    @Test
    void consumer_alwaysFailingMessage_isRedeliveredThenLandsInDlqAndConsumerKeepsRunning() {
        var queues = SqsTestQueues.create(sqs, newName());
        useCase = mock(ProcessOrderUseCase.class);
        when(useCase.execute(new OrderId("order-bad"))).thenReturn(Mono.error(new IllegalStateException("boom")));
        when(useCase.execute(new OrderId("order-good"))).thenReturn(Mono.just(sold(new OrderId("order-good"))));
        send(queues.url(), valid("order-bad"));

        startConsumer(queues, useCase);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(SqsTestQueues.total(sqs, queues.dlqUrl())).isEqualTo(1));
        assertThat(receiveFromDlq(queues.dlqUrl())).isEqualTo(valid("order-bad"));
        // One processing attempt per delivery until maxReceiveCount was reached.
        verify(useCase, times(SqsTestQueues.MAX_RECEIVE_COUNT)).execute(new OrderId("order-bad"));
        assertThat(consumer.isRunning()).isTrue();

        send(queues.url(), valid("order-good"));
        await().atMost(TIMEOUT).untilAsserted(() -> {
            verify(useCase, atLeastOnce()).execute(new OrderId("order-good"));
            assertThat(SqsTestQueues.total(sqs, queues.url())).isZero();
        });
    }

    @Test
    void consumer_poisonMessages_goToDlqWithoutStoppingOrBlockingValidMessages() {
        var queues = SqsTestQueues.create(sqs, newName());
        useCase = mock(ProcessOrderUseCase.class);
        when(useCase.execute(any(OrderId.class)))
                .thenAnswer(invocation -> Mono.just(sold(invocation.getArgument(0))));
        send(queues.url(), "this is {not json");
        send(queues.url(), valid("order-after-poison"));
        send(queues.url(), "{\"version\":99,\"orderId\":\"future\"}");

        startConsumer(queues, useCase);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(SqsTestQueues.total(sqs, queues.dlqUrl())).isEqualTo(2);
            assertThat(SqsTestQueues.total(sqs, queues.url())).isZero();
        });
        verify(useCase, times(1)).execute(new OrderId("order-after-poison"));
        verify(useCase, never()).execute(new OrderId("future"));
        // The consumer is still alive and processes a message sent after the poison ones.
        send(queues.url(), valid("order-later"));
        await().atMost(TIMEOUT).untilAsserted(() ->
                verify(useCase, times(1)).execute(new OrderId("order-later")));
        assertThat(consumer.isRunning()).isTrue();
    }
}
