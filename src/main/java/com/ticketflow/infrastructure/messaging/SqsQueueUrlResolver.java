package com.ticketflow.infrastructure.messaging;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

/** Resolves the URL of an SQS queue; shared by the publisher and the consumer. */
public final class SqsQueueUrlResolver {

    private static final Logger LOG = LoggerFactory.getLogger(SqsQueueUrlResolver.class);

    private SqsQueueUrlResolver() {
    }

    /** Same as {@link #byName(SqsAsyncClient, String, Retry)} with the default transient-error retry. */
    public static Mono<String> byName(SqsAsyncClient client, String queueName) {
        return byName(client, queueName, SqsOrderQueuePublisher.transientRetry(
                SqsOrderQueuePublisher.DEFAULT_MAX_RETRIES, SqsOrderQueuePublisher.DEFAULT_MIN_BACKOFF));
    }

    /**
     * Resolves the URL from the queue name on first subscription and caches it forever; a failure is
     * never cached, so a queue created later is picked up. A missing queue fails with
     * {@link OrderQueueNotFoundException}; {@code retry} applies to the lookup call only.
     */
    static Mono<String> byName(SqsAsyncClient client, String queueName, Retry retry) {
        return Mono.defer(() -> Mono.fromFuture(
                        client.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build())))
                .retryWhen(retry)
                .map(response -> response.queueUrl())
                .onErrorMap(QueueDoesNotExistException.class, e -> new OrderQueueNotFoundException(queueName, e))
                .doOnNext(url -> LOG.info("Resolved SQS queue '{}'", queueName))
                .cache(url -> Duration.ofDays(3650), error -> Duration.ZERO, () -> Duration.ZERO);
    }
}
