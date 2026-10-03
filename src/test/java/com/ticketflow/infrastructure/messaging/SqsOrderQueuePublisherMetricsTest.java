package com.ticketflow.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.infrastructure.observability.OperationalMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

/** Publisher metrics: one outcome per publish and one count per transient retry. */
class SqsOrderQueuePublisherMetricsTest {

    private static final String URL = "http://sqs.test/000000000000/orders";
    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    private final SqsAsyncClient client = mock(SqsAsyncClient.class);
    private final List<String> events = new ArrayList<>();
    private final OperationalMetrics metrics = new OperationalMetrics() {
        @Override
        public void publishCompleted(PublishOutcome outcome) {
            events.add("publish:" + outcome.tag());
        }

        @Override
        public void publishRetried() {
            events.add("retry");
        }
    };
    private final Order order = new Order(new OrderId("order-1"), new EventId("event-1"), new Quantity(2),
            TicketStatus.RESERVED, new IdempotencyKey("key-1"), NOW.plusSeconds(600), NOW);

    private SqsOrderQueuePublisher publisher() {
        when(client.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl(URL).build()));
        return SqsOrderQueuePublisher.forQueueName(client, "orders", 2, Duration.ofMillis(1), metrics);
    }

    private static SqsException status(int code) {
        return (SqsException) SqsException.builder().statusCode(code)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("Err").build()).build();
    }

    @Test
    void publish_success_countsOkOnce() {
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(
                CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("m").build()));

        StepVerifier.create(publisher().publish(order)).verifyComplete();

        assertThat(events).containsExactly("publish:ok");
    }

    @Test
    void publish_transientErrorThenSuccess_countsTheRetryAndOneOk() {
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(
                CompletableFuture.failedFuture(status(503)),
                CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("m").build()));

        StepVerifier.create(publisher().publish(order)).verifyComplete();

        assertThat(events).containsExactly("retry", "publish:ok");
    }

    @Test
    void publish_retriesExhausted_countsEveryRetryAndOneFailure() {
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(CompletableFuture.failedFuture(status(503)));

        StepVerifier.create(publisher().publish(order)).expectError(SqsException.class).verify();

        assertThat(events).containsExactly("retry", "retry", "publish:failed");
    }

    @Test
    void publish_nonTransientError_failsWithoutRetry() {
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(CompletableFuture.failedFuture(status(400)));

        StepVerifier.create(publisher().publish(order)).expectError(SqsException.class).verify();

        assertThat(events).containsExactly("publish:failed");
    }

    @Test
    void publish_forQueueUrlWithMetrics_countsToo() {
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(
                CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("m").build()));

        StepVerifier.create(SqsOrderQueuePublisher.forQueueUrl(client, URL, metrics).publish(order)).verifyComplete();

        assertThat(events).containsExactly("publish:ok");
    }

    @Test
    void publish_forQueueNameWithMetricsOnly_countsToo() {
        when(client.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl(URL).build()));
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(
                CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("m").build()));

        StepVerifier.create(SqsOrderQueuePublisher.forQueueName(client, "orders", metrics).publish(order))
                .verifyComplete();

        assertThat(events).containsExactly("publish:ok");
    }
}
