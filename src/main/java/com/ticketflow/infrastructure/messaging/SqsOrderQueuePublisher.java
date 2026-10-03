package com.ticketflow.infrastructure.messaging;

import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.port.OrderQueuePublisher;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes orders to SQS with the AWS SDK v2 async client.
 *
 * <p>The returned {@link Mono} completes only after SQS acknowledged the message. {@code SendMessage}
 * is at-least-once: a transient failure retried after the broker already stored the message, or a
 * lost acknowledgement, can produce duplicates. That is acceptable by design: the consumer
 * ({@code ProcessOrderUseCase}) is idempotent per order. Only transient errors (throttling, 5xx and
 * retry-safe connection errors) are retried, with exponential backoff; anything else fails fast so
 * the purchase use case can compensate.
 *
 * <p>The queue URL is resolved from the queue name on first use and cached (failures are not
 * cached, so a queue created later is picked up).
 */
public final class SqsOrderQueuePublisher implements OrderQueuePublisher {

    public static final String ATTR_EVENT_ID = "eventId";
    public static final String ATTR_ORDER_ID = "orderId";
    public static final String ATTR_MESSAGE_VERSION = "messageVersion";
    /** Optional Reactor context key; when present it travels as the {@code correlationId} attribute. */
    public static final String CORRELATION_ID_CONTEXT_KEY = "correlationId";
    public static final String ATTR_CORRELATION_ID = "correlationId";

    static final int DEFAULT_MAX_RETRIES = 3;
    static final Duration DEFAULT_MIN_BACKOFF = Duration.ofMillis(100);

    private static final Logger LOG = LoggerFactory.getLogger(SqsOrderQueuePublisher.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SqsAsyncClient client;
    private final Mono<String> queueUrl;
    private final Retry retry;

    private SqsOrderQueuePublisher(SqsAsyncClient client, Mono<String> queueUrl, int maxRetries,
                                   Duration minBackoff) {
        this.client = client;
        this.queueUrl = queueUrl;
        this.retry = transientRetry(maxRetries, minBackoff);
    }

    static Retry transientRetry(int maxRetries, Duration minBackoff) {
        return Retry.backoff(maxRetries, minBackoff)
                .filter(SqsOrderQueuePublisher::isTransient)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    /** Publisher for a queue identified by name; the URL is resolved lazily and cached. */
    public static SqsOrderQueuePublisher forQueueName(SqsAsyncClient client, String queueName) {
        return forQueueName(client, queueName, DEFAULT_MAX_RETRIES, DEFAULT_MIN_BACKOFF);
    }

    static SqsOrderQueuePublisher forQueueName(SqsAsyncClient client, String queueName, int maxRetries,
                                               Duration minBackoff) {
        Retry retry = transientRetry(maxRetries, minBackoff);
        Mono<String> resolved = SqsQueueUrlResolver.byName(client, queueName, retry);
        return new SqsOrderQueuePublisher(client, resolved, maxRetries, minBackoff);
    }

    /** Publisher for a queue whose URL is already known. */
    public static SqsOrderQueuePublisher forQueueUrl(SqsAsyncClient client, String queueUrl) {
        return new SqsOrderQueuePublisher(client, Mono.just(queueUrl), DEFAULT_MAX_RETRIES, DEFAULT_MIN_BACKOFF);
    }

    @Override
    public Mono<Void> publish(Order order) {
        return Mono.deferContextual(context -> queueUrl.flatMap(url -> {
            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(url)
                    .messageBody(JSON.writeValueAsString(OrderQueueMessage.of(order.id().value())))
                    .messageAttributes(attributes(order, context.getOrDefault(CORRELATION_ID_CONTEXT_KEY, null)))
                    .build();
            return Mono.fromFuture(() -> client.sendMessage(request)).retryWhen(retry);
        })).doOnNext(response -> LOG.debug("Order {} enqueued as SQS message {}", order.id().value(),
                response.messageId())).then();
    }

    private static Map<String, MessageAttributeValue> attributes(Order order, String correlationId) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(ATTR_EVENT_ID, string(order.eventId().value()));
        attributes.put(ATTR_ORDER_ID, string(order.id().value()));
        attributes.put(ATTR_MESSAGE_VERSION, MessageAttributeValue.builder().dataType("Number")
                .stringValue(Integer.toString(OrderQueueMessage.CURRENT_VERSION)).build());
        if (correlationId != null && !correlationId.isBlank()) {
            attributes.put(ATTR_CORRELATION_ID, string(correlationId));
        }
        return attributes;
    }

    private static MessageAttributeValue string(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }

    /** Throttling, 5xx and connection-level failures are worth retrying; everything else is not. */
    static boolean isTransient(Throwable error) {
        return switch (error) {
            case AwsServiceException aws -> aws.isThrottlingException() || aws.statusCode() >= 500;
            case SdkClientException client -> client.retryable() || hasIoCause(client);
            default -> false;
        };
    }

    private static boolean hasIoCause(Throwable error) {
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }
}
