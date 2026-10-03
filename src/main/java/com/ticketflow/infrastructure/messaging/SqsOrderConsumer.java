package com.ticketflow.infrastructure.messaging;

import com.ticketflow.domain.model.OrderId;
import com.ticketflow.infrastructure.config.SqsConsumerProperties;
import com.ticketflow.usecase.ProcessOrderResult;
import com.ticketflow.usecase.ProcessOrderUseCase;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reactive long-polling SQS consumer that feeds {@link ProcessOrderUseCase} with at-least-once
 * semantics.
 *
 * <p><b>Acknowledgement.</b> A message is deleted only after the use case completed successfully
 * (any {@link ProcessOrderResult}: sold, released as expired, already processed or order missing are
 * all terminal). A failed use case (transient infrastructure error, or a final
 * {@code OrderStatusConflictException}), a failed delete, or a poison message (invalid JSON, unknown
 * version, missing {@code orderId}) is never deleted: SQS makes it visible again after the
 * visibility timeout and, once {@code maxReceiveCount} is exceeded, moves it to the dead-letter
 * queue. Redelivery is safe because the use case is idempotent per order.
 *
 * <p><b>Loop.</b> Each iteration long-polls one batch and processes it with
 * {@code flatMap(concurrency)}, so at most {@code min(concurrency, batchSize)} messages are in flight
 * and no received message waits (invisible) behind another batch. Errors of a single message are
 * contained in that message; {@code ReceiveMessage} failures are retried forever with capped
 * exponential backoff, so the loop never terminates silently.
 *
 * <p><b>Shutdown.</b> {@link #stop(Runnable)} stops polling (an in-flight long poll is cancelled; its
 * messages simply reappear), lets messages already received finish for at most
 * {@code shutdownTimeout}, then disposes the loop.
 *
 * <p><b>Restart.</b> Each {@code start()} gets a new generation number captured by its loop: a loop only
 * repeats while it is still the current generation, so a {@code stop()} + {@code start()} during a drain
 * can never leave the old loop polling next to the new one (the old one finishes its in-flight batch and
 * ends).
 *
 * <p>Logs never include message bodies.
 */
public final class SqsOrderConsumer implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(SqsOrderConsumer.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SqsAsyncClient client;
    private final Mono<String> queueUrl;
    private final ProcessOrderUseCase useCase;
    private final SqsConsumerProperties properties;
    private final Scheduler timer;

    private Disposable subscription;
    private Sinks.Empty<Void> stopSignal;
    private Sinks.Empty<Void> terminated;
    private volatile boolean running;
    /** Incremented by every start(); a loop is alive only while its captured value is the current one. */
    private volatile long generation;

    public SqsOrderConsumer(SqsAsyncClient client, Mono<String> queueUrl, ProcessOrderUseCase useCase,
                            SqsConsumerProperties properties) {
        this(client, queueUrl, useCase, properties, Schedulers.parallel());
    }

    SqsOrderConsumer(SqsAsyncClient client, Mono<String> queueUrl, ProcessOrderUseCase useCase,
                     SqsConsumerProperties properties, Scheduler timer) {
        this.client = client;
        this.queueUrl = queueUrl;
        this.useCase = useCase;
        this.properties = properties;
        this.timer = timer;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        long myGeneration = ++generation;
        stopSignal = Sinks.empty();
        terminated = Sinks.empty();
        Mono<Void> stop = stopSignal.asMono();
        Sinks.Empty<Void> done = terminated;
        subscription = pollingLoop(stop, () -> running && generation == myGeneration)
                .doFinally(signal -> done.tryEmitEmpty())
                .subscribe(null, error -> LOG.error("SQS order consumer terminated unexpectedly", error));
        LOG.info("SQS order consumer started (batchSize={}, concurrency={}, waitTime={}, visibilityTimeout={})",
                properties.batchSize(), properties.concurrency(), properties.waitTime(),
                properties.visibilityTimeout());
    }

    @Override
    public void stop() {
        stop(() -> { });
    }

    @Override
    public synchronized void stop(Runnable callback) {
        if (!running) {
            callback.run();
            return;
        }
        running = false;
        stopSignal.tryEmitEmpty();
        Disposable loop = subscription;
        LOG.info("SQS order consumer stopping (waiting up to {} for in-flight messages)",
                properties.shutdownTimeout());
        terminated.asMono()
                .timeout(properties.shutdownTimeout(), timer)
                .onErrorResume(TimeoutException.class, e -> {
                    LOG.warn("SQS order consumer did not drain in time; disposing with messages in flight "
                            + "(they will be redelivered)");
                    return Mono.empty();
                })
                .doFinally(signal -> {
                    loop.dispose();
                    LOG.info("SQS order consumer stopped");
                    callback.run();
                })
                .subscribe();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Endless poll -> process loop; ends only after {@code stop} fires (or on disposal). */
    Flux<Void> pollingLoop(Mono<Void> stop, BooleanSupplier alive) {
        return Flux.defer(() -> pollOnce(stop))
                .repeat(alive::getAsBoolean)
                // Defensive: nothing in the loop is expected to fail, but it must never die silently.
                .retryWhen(backoff("Polling loop failed"));
    }

    private Mono<Void> pollOnce(Mono<Void> stop) {
        return queueUrl
                .flatMap(url -> Mono.fromFuture(() -> client.receiveMessage(receiveRequest(url))))
                .retryWhen(backoff("ReceiveMessage failed"))
                .takeUntilOther(stop)
                .flatMapMany(this::processBatch)
                .then();
    }

    private Flux<Void> processBatch(ReceiveMessageResponse response) {
        return Flux.fromIterable(response.messages())
                .flatMap(message -> handle(message), properties.concurrency());
    }

    private ReceiveMessageRequest receiveRequest(String url) {
        return ReceiveMessageRequest.builder()
                .queueUrl(url)
                .maxNumberOfMessages(properties.batchSize())
                .waitTimeSeconds((int) properties.waitTime().toSeconds())
                .visibilityTimeout((int) properties.visibilityTimeout().toSeconds())
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                .build();
    }

    private Retry backoff(String description) {
        return Retry.backoff(Long.MAX_VALUE, properties.minBackoff())
                .maxBackoff(properties.maxBackoff())
                .scheduler(timer)
                .doBeforeRetry(signal -> LOG.warn("{} (attempt {}): {}: {}; retrying with backoff", description,
                        signal.totalRetries() + 1, signal.failure().getClass().getSimpleName(),
                        signal.failure().getMessage()));
    }

    /** Processes one message; never fails: every error leaves the message undeleted for redelivery. */
    private Mono<Void> handle(Message message) {
        return Mono.fromCallable(() -> parse(message))
                .onErrorResume(InvalidMessageException.class, error -> {
                    LOG.warn("Discarding poison message {} (receiveCount={}): {}; not deleted, SQS will redrive it",
                            message.messageId(), receiveCount(message), error.getMessage());
                    return Mono.empty();
                })
                .flatMap(orderId -> Mono.defer(() -> useCase.execute(orderId))
                        .flatMap(result -> acknowledge(message, result)))
                .onErrorResume(error -> {
                    LOG.warn("Message {} (receiveCount={}) not acknowledged, SQS will redeliver: {}: {}",
                            message.messageId(), receiveCount(message), error.getClass().getSimpleName(),
                            error.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> acknowledge(Message message, ProcessOrderResult result) {
        return queueUrl
                .flatMap(url -> Mono.fromFuture(() -> client.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(url).receiptHandle(message.receiptHandle()).build())))
                .doOnSuccess(response -> LOG.debug("Order {} processed as {}; message {} deleted",
                        result.orderId().value(), result.getClass().getSimpleName(), message.messageId()))
                .then();
    }

    private static String receiveCount(Message message) {
        return message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
    }

    /** Validates the versioned body; the reason never echoes the payload. */
    private static OrderId parse(Message message) {
        JsonNode body;
        try {
            body = JSON.readTree(message.body());
        } catch (RuntimeException e) {
            throw new InvalidMessageException("body is not valid JSON (" + e.getClass().getSimpleName() + ")");
        }
        if (body == null || !body.isObject()) {
            throw new InvalidMessageException("body is not a JSON object");
        }
        JsonNode version = body.get("version");
        if (version == null || !version.isInt()) {
            throw new InvalidMessageException("missing or non-integer version");
        }
        if (version.intValue() != OrderQueueMessage.CURRENT_VERSION) {
            throw new InvalidMessageException("unsupported version " + version.intValue());
        }
        JsonNode orderId = body.get("orderId");
        if (orderId == null || !orderId.isString() || orderId.stringValue().isBlank()) {
            throw new InvalidMessageException("missing orderId");
        }
        return new OrderId(orderId.stringValue());
    }

    private static final class InvalidMessageException extends RuntimeException {
        InvalidMessageException(String reason) {
            super(reason, null, false, false);
        }
    }
}
