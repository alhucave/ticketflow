package com.ticketflow.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

class SqsOrderQueuePublisherTest {

    private static final String URL = "http://sqs.test/000000000000/orders";
    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    private final SqsAsyncClient client = mock(SqsAsyncClient.class);
    private final Order order = new Order(new OrderId("order-1"), new EventId("event-1"), new Quantity(2),
            TicketStatus.RESERVED, new IdempotencyKey("key-1"), NOW.plusSeconds(600), NOW);

    private SqsOrderQueuePublisher byName;

    @BeforeEach
    void setUp() {
        byName = SqsOrderQueuePublisher.forQueueName(client, "orders", 2, Duration.ofMillis(1));
    }

    private void queueUrlResolves() {
        when(client.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl(URL).build()));
    }

    private static CompletableFuture<SendMessageResponse> ok() {
        return CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("m-1").build());
    }

    private static CompletableFuture<SendMessageResponse> failed(Throwable error) {
        return CompletableFuture.failedFuture(error);
    }

    private static AwsServiceException aws(int status, String code) {
        return SqsException.builder().statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build()).build();
    }

    @Test
    void publish_success_sendsVersionedBodyAndAttributesAfterAck() {
        queueUrlResolves();
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(ok());

        StepVerifier.create(byName.publish(order)).verifyComplete();

        var captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client).sendMessage(captor.capture());
        var request = captor.getValue();
        assertThat(request.queueUrl()).isEqualTo(URL);
        assertThat(request.messageBody()).isEqualTo("{\"version\":1,\"orderId\":\"order-1\"}");
        assertThat(request.messageAttributes().get("eventId").stringValue()).isEqualTo("event-1");
        assertThat(request.messageAttributes().get("orderId").stringValue()).isEqualTo("order-1");
        assertThat(request.messageAttributes().get("messageVersion").stringValue()).isEqualTo("1");
        assertThat(request.messageAttributes()).doesNotContainKey("correlationId");
    }

    @Test
    void publish_completesOnlyAfterSqsAcknowledges() {
        queueUrlResolves();
        var pending = new CompletableFuture<SendMessageResponse>();
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(pending);

        var completed = new java.util.concurrent.atomic.AtomicBoolean();
        byName.publish(order).subscribe(unused -> { }, error -> { }, () -> completed.set(true));
        assertThat(completed).isFalse();

        pending.complete(SendMessageResponse.builder().messageId("m").build());

        assertThat(completed).isTrue();
    }

    @Test
    void publish_correlationIdInContext_isSentAsAttribute() {
        queueUrlResolves();
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(ok());

        StepVerifier.create(byName.publish(order).contextWrite(
                        ctx -> ctx.put(SqsOrderQueuePublisher.CORRELATION_ID_CONTEXT_KEY, "corr-9")))
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client).sendMessage(captor.capture());
        assertThat(captor.getValue().messageAttributes().get("correlationId").stringValue()).isEqualTo("corr-9");
    }

    @Test
    void publish_queueUrlResolvedOnceAndCachedAcrossPublishes() {
        queueUrlResolves();
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(ok());

        StepVerifier.create(byName.publish(order).then(byName.publish(order))).verifyComplete();

        verify(client, times(1)).getQueueUrl(any(GetQueueUrlRequest.class));
        verify(client, times(2)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void publish_withConfiguredQueueUrl_neverResolvesTheName() {
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(ok());

        StepVerifier.create(SqsOrderQueuePublisher.forQueueUrl(client, URL).publish(order)).verifyComplete();

        verify(client, never()).getQueueUrl(any(GetQueueUrlRequest.class));
        var captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client).sendMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(URL);
    }

    @Test
    void publish_transientErrorThenOk_isRetriedAndCompletes() {
        queueUrlResolves();
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(failed(aws(503, "ServiceUnavailable")))
                .thenReturn(failed(aws(400, "ThrottlingException")))
                .thenReturn(ok());

        StepVerifier.create(byName.publish(order)).verifyComplete();

        verify(client, times(3)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void publish_connectionErrorThenOk_isRetried() {
        queueUrlResolves();
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(failed(SdkClientException.create("io", new IOException("reset"))))
                .thenReturn(ok());

        StepVerifier.create(byName.publish(order)).verifyComplete();

        verify(client, times(2)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void publish_transientErrorPersists_failsAfterBoundedRetriesWithOriginalError() {
        queueUrlResolves();
        var error = aws(500, "InternalError");
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(failed(error));

        StepVerifier.create(byName.publish(order)).expectErrorMatches(e -> e == error).verify();

        verify(client, times(3)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void publish_nonTransientError_isNotRetried() {
        queueUrlResolves();
        var error = aws(403, "AccessDenied");
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(failed(error));

        StepVerifier.create(byName.publish(order)).expectErrorMatches(e -> e == error).verify();

        verify(client, times(1)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void publish_queueDoesNotExist_failsClearlyWithoutRetryOrSend() {
        when(client.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(QueueDoesNotExistException.builder().build()));

        StepVerifier.create(byName.publish(order))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(OrderQueueNotFoundException.class)
                        .hasMessageContaining("'orders'"))
                .verify();

        verify(client, times(1)).getQueueUrl(any(GetQueueUrlRequest.class));
        verify(client, never()).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void publish_resolutionFailureIsNotCached_nextPublishResolvesAgain() {
        when(client.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(QueueDoesNotExistException.builder().build()))
                .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl(URL).build()));
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(ok());

        StepVerifier.create(byName.publish(order)).expectError(OrderQueueNotFoundException.class).verify();
        StepVerifier.create(byName.publish(order)).verifyComplete();

        verify(client, times(2)).getQueueUrl(any(GetQueueUrlRequest.class));
    }

    @Test
    void publish_transientResolutionErrorThenOk_isRetried() {
        when(client.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(aws(503, "ServiceUnavailable")))
                .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl(URL).build()));
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(ok());

        StepVerifier.create(byName.publish(order)).verifyComplete();
    }

    @Test
    void isTransient_classifiesErrors() {
        assertThat(SqsOrderQueuePublisher.isTransient(aws(500, "InternalError"))).isTrue();
        assertThat(SqsOrderQueuePublisher.isTransient(aws(400, "RequestThrottled"))).isTrue();
        assertThat(SqsOrderQueuePublisher.isTransient(aws(400, "InvalidParameterValue"))).isFalse();
        assertThat(SqsOrderQueuePublisher.isTransient(SdkClientException.create("x", new IOException()))).isTrue();
        assertThat(SqsOrderQueuePublisher.isTransient(SdkClientException.create("plain"))).isFalse();
        assertThat(SqsOrderQueuePublisher.isTransient(new IllegalStateException())).isFalse();
    }
}
