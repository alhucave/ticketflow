package com.ticketflow.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ticketflow.domain.exception.OrderStatusConflictException;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.infrastructure.config.SqsConsumerProperties;
import com.ticketflow.usecase.ProcessOrderResult;
import com.ticketflow.usecase.ProcessOrderUseCase;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

/**
 * Unit tests with a mocked SQS client. Futures are already completed, so the loop runs
 * synchronously on the test thread until it parks on a never-completing ReceiveMessage; time-based
 * behaviour (backoff, shutdown timeout) uses a virtual-time scheduler.
 */
class SqsOrderConsumerTest {

    private static final String URL = "http://sqs.test/000000000000/orders";
    private static final String VALID = "{\"version\":1,\"orderId\":\"%s\"}";

    private SqsAsyncClient client;
    private ProcessOrderUseCase useCase;
    private VirtualTimeScheduler timer;
    private Deque<CompletableFuture<ReceiveMessageResponse>> receives;
    private List<CompletableFuture<ReceiveMessageResponse>> parked;
    private SqsOrderConsumer consumer;

    @BeforeEach
    void setUp() {
        client = mock(SqsAsyncClient.class);
        useCase = mock(ProcessOrderUseCase.class);
        timer = VirtualTimeScheduler.create();
        receives = new ArrayDeque<>();
        parked = new ArrayList<>();
        when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(invocation -> {
            var next = receives.poll();
            if (next != null) {
                return next;
            }
            var pending = new CompletableFuture<ReceiveMessageResponse>();
            parked.add(pending);
            return pending;
        });
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.stop();
            timer.advanceTimeBy(Duration.ofMinutes(5));
        }
        timer.dispose();
    }

    private SqsConsumerProperties props(int batch, int concurrency) {
        return new SqsConsumerProperties(true, batch, Duration.ofSeconds(20), Duration.ofSeconds(30), concurrency,
                Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(4));
    }

    private SqsOrderConsumer consumer(SqsConsumerProperties props) {
        consumer = new SqsOrderConsumer(client, Mono.just(URL), useCase, props, timer);
        return consumer;
    }

    private static Message message(String id, String body) {
        return Message.builder().messageId(id).receiptHandle("rh-" + id).body(body)
                .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1")).build();
    }

    private static Message valid(String orderId) {
        return message("m-" + orderId, VALID.formatted(orderId));
    }

    private void queueBatch(Message... messages) {
        receives.add(CompletableFuture.completedFuture(ReceiveMessageResponse.builder().messages(messages).build()));
    }

    private static ProcessOrderResult sold(String orderId) {
        return new ProcessOrderResult.Sold(new OrderId(orderId));
    }

    private List<String> deletedHandles() {
        var captor = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(client, org.mockito.Mockito.atLeast(0)).deleteMessage(captor.capture());
        return captor.getAllValues().stream().map(DeleteMessageRequest::receiptHandle).toList();
    }

    @Test
    void start_validMessage_deletedOnlyAfterUseCaseSucceeds() {
        var processing = Sinks.<ProcessOrderResult>one();
        when(useCase.execute(new OrderId("o1"))).thenReturn(processing.asMono());
        queueBatch(valid("o1"));

        consumer(props(10, 4)).start();

        verify(useCase).execute(new OrderId("o1"));
        verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
        processing.tryEmitValue(sold("o1"));
        var captor = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(client).deleteMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(URL);
        assertThat(captor.getValue().receiptHandle()).isEqualTo("rh-m-o1");
    }

    @Test
    void start_receiveRequest_usesConfiguredBatchWaitAndVisibility() {
        consumer(props(7, 4)).start();

        var captor = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(client).receiveMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(URL);
        assertThat(captor.getValue().maxNumberOfMessages()).isEqualTo(7);
        assertThat(captor.getValue().waitTimeSeconds()).isEqualTo(20);
        assertThat(captor.getValue().visibilityTimeout()).isEqualTo(30);
    }

    @Test
    void start_everyTerminalResult_isAcknowledged() {
        var id = new OrderId("o1");
        when(useCase.execute(new OrderId("sold"))).thenReturn(Mono.just(new ProcessOrderResult.Sold(id)));
        when(useCase.execute(new OrderId("expired")))
                .thenReturn(Mono.just(new ProcessOrderResult.ReleasedAsExpired(id)));
        when(useCase.execute(new OrderId("done")))
                .thenReturn(Mono.just(new ProcessOrderResult.AlreadyProcessed(id, TicketStatus.SOLD)));
        when(useCase.execute(new OrderId("missing"))).thenReturn(Mono.just(new ProcessOrderResult.OrderMissing(id)));
        queueBatch(valid("sold"), valid("expired"), valid("done"), valid("missing"));

        consumer(props(10, 4)).start();

        assertThat(deletedHandles()).containsExactlyInAnyOrder("rh-m-sold", "rh-m-expired", "rh-m-done",
                "rh-m-missing");
    }

    @Test
    void start_useCaseFails_messageNotDeletedAndLoopKeepsPolling() {
        when(useCase.execute(new OrderId("bad"))).thenReturn(Mono.error(new IllegalStateException("db down")));
        when(useCase.execute(new OrderId("conflict")))
                .thenReturn(Mono.error(new OrderStatusConflictException(new OrderId("conflict"),
                        TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION)));
        when(useCase.execute(new OrderId("good"))).thenReturn(Mono.just(sold("good")));
        queueBatch(valid("bad"), valid("conflict"), valid("good"));

        consumer(props(10, 4)).start();

        assertThat(deletedHandles()).containsExactly("rh-m-good");
        verify(client, times(2)).receiveMessage(any(ReceiveMessageRequest.class));
        assertThat(consumer.isRunning()).isTrue();
    }

    @Test
    void start_useCaseThrowsSynchronously_messageNotDeletedAndLoopKeepsPolling() {
        when(useCase.execute(any(OrderId.class))).thenThrow(new IllegalStateException("bug"));
        queueBatch(valid("o1"));

        consumer(props(10, 4)).start();

        verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
        verify(client, times(2)).receiveMessage(any(ReceiveMessageRequest.class));
    }

    @Test
    void start_deleteFails_isContainedAndLoopKeepsPolling() {
        when(useCase.execute(any(OrderId.class))).thenReturn(Mono.just(sold("o1")));
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SqsException.builder().message("denied").build()));
        queueBatch(valid("o1"), valid("o2"));

        consumer(props(10, 4)).start();

        verify(client, times(2)).deleteMessage(any(DeleteMessageRequest.class));
        verify(client, times(2)).receiveMessage(any(ReceiveMessageRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json at all", "", "[1,2]", "\"text\"", "{}", "{\"version\":1}", "{\"orderId\":\"o1\"}",
            "{\"version\":2,\"orderId\":\"o1\"}", "{\"version\":\"1\",\"orderId\":\"o1\"}",
            "{\"version\":1,\"orderId\":\"  \"}", "{\"version\":1,\"orderId\":42}", "{\"version\":1,\"orderId\":null}"})
    void start_poisonMessage_notDeletedUseCaseNotCalledAndValidNeighbourStillProcessed(String body) {
        when(useCase.execute(new OrderId("good"))).thenReturn(Mono.just(sold("good")));
        queueBatch(message("poison", body), valid("good"));

        consumer(props(10, 4)).start();

        verify(useCase).execute(any(OrderId.class));
        assertThat(deletedHandles()).containsExactly("rh-m-good");
        verify(client, times(2)).receiveMessage(any(ReceiveMessageRequest.class));
    }

    @Test
    void start_nullBody_isPoison() {
        queueBatch(message("nobody", null));

        consumer(props(10, 4)).start();

        verify(useCase, never()).execute(any(OrderId.class));
        verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void start_poisonMessage_warnsWithoutLeakingPayload() {
        var logger = (Logger) LoggerFactory.getLogger(SqsOrderConsumer.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            queueBatch(message("poison", "{\"token\": SECRET-VALUE-123 oops"));
            queueBatch(message("poison2", "{\"version\":9,\"orderId\":\"o1\",\"card\":\"SECRET-VALUE-123\"}"));

            consumer(props(10, 4)).start();

            var warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
            assertThat(warnings).hasSize(2);
            assertThat(warnings).allSatisfy(e -> {
                assertThat(e.getFormattedMessage()).contains("poison").doesNotContain("SECRET-VALUE-123");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void start_receiveFails_retriesWithCappedBackoffAndRecovers() {
        var failure = SqsException.builder().message("throttled").build();
        for (int i = 0; i < 6; i++) {
            receives.add(CompletableFuture.failedFuture(failure));
        }
        when(useCase.execute(any(OrderId.class))).thenReturn(Mono.just(sold("o1")));
        queueBatch(valid("o1"));

        consumer(props(10, 4)).start();

        verify(client, times(1)).receiveMessage(any(ReceiveMessageRequest.class));
        timer.advanceTimeBy(Duration.ofMillis(900));
        verify(client, times(1)).receiveMessage(any(ReceiveMessageRequest.class));
        // Default jitter can stretch the first delay up to 1.5s.
        timer.advanceTimeBy(Duration.ofMillis(700));
        verify(client, times(2)).receiveMessage(any(ReceiveMessageRequest.class));
        // Remaining failures plus the success: each wait is capped at max-backoff (4s), so 5 x 4s suffices.
        for (int attempt = 0; attempt < 5; attempt++) {
            timer.advanceTimeBy(Duration.ofSeconds(4));
        }
        // 7th call returns the batch, the 8th is the next (parked) poll.
        verify(client, times(8)).receiveMessage(any(ReceiveMessageRequest.class));
        assertThat(deletedHandles()).containsExactly("rh-m-o1");
        assertThat(consumer.isRunning()).isTrue();
    }

    @Test
    void start_queueUrlResolutionFails_retriesInsteadOfDying() {
        var attempts = new AtomicInteger();
        var flaky = Mono.defer(() -> attempts.incrementAndGet() < 3
                ? Mono.<String>error(new OrderQueueNotFoundException("orders", new RuntimeException("no queue")))
                : Mono.just(URL));
        consumer = new SqsOrderConsumer(client, flaky, useCase, props(10, 4), timer);

        consumer.start();
        timer.advanceTimeBy(Duration.ofSeconds(1));
        timer.advanceTimeBy(Duration.ofSeconds(4));

        assertThat(attempts.get()).isEqualTo(3);
        verify(client).receiveMessage(any(ReceiveMessageRequest.class));
    }

    @Test
    void start_concurrencyConfigured_neverMoreInFlightAndNextPollWaitsForBatch() {
        var sinks = new ConcurrentHashMap<String, Sinks.One<ProcessOrderResult>>();
        var inFlight = new AtomicInteger();
        var maxInFlight = new AtomicInteger();
        var started = new ArrayList<String>();
        when(useCase.execute(any(OrderId.class))).thenAnswer(invocation -> {
            OrderId id = invocation.getArgument(0);
            var sink = sinks.computeIfAbsent(id.value(), k -> Sinks.one());
            return sink.asMono()
                    .doOnSubscribe(s -> {
                        started.add(id.value());
                        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    })
                    .doOnTerminate(inFlight::decrementAndGet);
        });
        var batch = new ArrayList<Message>();
        for (int i = 0; i < 10; i++) {
            batch.add(valid("o" + i));
        }
        queueBatch(batch.toArray(Message[]::new));

        consumer(props(10, 3)).start();

        assertThat(started).containsExactly("o0", "o1", "o2");
        for (int i = 0; i < 10; i++) {
            sinks.computeIfAbsent("o" + i, k -> Sinks.one()).tryEmitValue(sold("o" + i));
            if (i < 9) {
                verify(client, times(1)).receiveMessage(any(ReceiveMessageRequest.class));
            }
        }

        assertThat(maxInFlight.get()).isEqualTo(3);
        assertThat(started).hasSize(10);
        assertThat(deletedHandles()).hasSize(10);
        verify(client, times(2)).receiveMessage(any(ReceiveMessageRequest.class));
    }

    @Test
    void stop_inFlightMessage_finishesAndIsDeletedBeforeCallbackThenNoMorePolling() {
        var processing = Sinks.<ProcessOrderResult>one();
        when(useCase.execute(new OrderId("o1"))).thenReturn(processing.asMono());
        queueBatch(valid("o1"));
        consumer(props(10, 4)).start();
        var stopped = new AtomicBoolean();

        consumer.stop(() -> stopped.set(true));

        assertThat(consumer.isRunning()).isFalse();
        assertThat(stopped).isFalse();
        processing.tryEmitValue(sold("o1"));
        assertThat(deletedHandles()).containsExactly("rh-m-o1");
        assertThat(stopped).isTrue();
        verify(client, times(1)).receiveMessage(any(ReceiveMessageRequest.class));
    }

    @Test
    void stop_longPollInFlight_isCancelledAndStopsPromptly() {
        consumer(props(10, 4)).start();
        assertThat(parked).hasSize(1);
        var stopped = new AtomicBoolean();

        consumer.stop(() -> stopped.set(true));

        assertThat(parked.get(0)).isCancelled();
        assertThat(stopped).isTrue();
        verify(useCase, never()).execute(any(OrderId.class));
    }

    @Test
    void stop_inFlightNeverFinishes_disposedAfterShutdownTimeout() {
        var cancelled = new AtomicBoolean();
        when(useCase.execute(any(OrderId.class))).thenReturn(Mono.<ProcessOrderResult>never()
                .doOnCancel(() -> cancelled.set(true)));
        queueBatch(valid("o1"));
        consumer(props(10, 4)).start();
        var stopped = new AtomicBoolean();

        consumer.stop(() -> stopped.set(true));
        timer.advanceTimeBy(Duration.ofSeconds(9));
        assertThat(stopped).isFalse();
        assertThat(cancelled).isFalse();
        timer.advanceTimeBy(Duration.ofSeconds(1));

        assertThat(stopped).isTrue();
        assertThat(cancelled).isTrue();
        verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void lifecycle_stopWhenNotRunningRunsCallbackAndStartIsIdempotent() {
        consumer(props(10, 4));
        var stopped = new AtomicBoolean();
        consumer.stop(() -> stopped.set(true));
        assertThat(stopped).isTrue();
        assertThat(consumer.isRunning()).isFalse();

        consumer.start();
        consumer.start();

        assertThat(consumer.isRunning()).isTrue();
        verify(client, times(1)).receiveMessage(any(ReceiveMessageRequest.class));
    }

    @Test
    void lifecycle_canBeRestartedAfterStop() {
        consumer(props(10, 4)).start();
        consumer.stop();
        consumer.start();

        assertThat(consumer.isRunning()).isTrue();
        verify(client, times(2)).receiveMessage(any(ReceiveMessageRequest.class));
    }

    @Test
    void restart_duringDrain_oldLoopEndsAfterItsBatchAndOnlyTheNewLoopPolls() {
        var processing = Sinks.<ProcessOrderResult>one();
        when(useCase.execute(new OrderId("o1"))).thenReturn(processing.asMono());
        queueBatch(valid("o1"));
        consumer(props(10, 4)).start(); // loop A: o1 in flight

        var stopped = new AtomicBoolean();
        consumer.stop(() -> stopped.set(true)); // A is draining
        consumer.start();                        // loop B starts while A still drains
        assertThat(consumer.isRunning()).isTrue();
        assertThat(parked).hasSize(1);           // B is long polling

        processing.tryEmitValue(sold("o1"));     // A's batch finishes

        assertThat(deletedHandles()).containsExactly("rh-m-o1");
        assertThat(stopped).isTrue();            // the drain completed
        // A did not poll again even though `running` is true again: one poll by A, one (parked) by B.
        verify(client, times(2)).receiveMessage(any(ReceiveMessageRequest.class));
        assertThat(parked).hasSize(1);

        // B is fully functional and alone.
        when(useCase.execute(new OrderId("o2"))).thenReturn(Mono.just(sold("o2")));
        parked.get(0).complete(ReceiveMessageResponse.builder().messages(valid("o2")).build());
        assertThat(deletedHandles()).containsExactly("rh-m-o1", "rh-m-o2");
        verify(useCase, times(1)).execute(new OrderId("o2"));
        verify(client, times(3)).receiveMessage(any(ReceiveMessageRequest.class)); // only B continues
    }

    @Test
    void restart_manyTimesDuringOneDrain_stillLeavesExactlyOneLoop() {
        var processing = Sinks.<ProcessOrderResult>one();
        when(useCase.execute(new OrderId("o1"))).thenReturn(processing.asMono());
        queueBatch(valid("o1"));
        consumer(props(10, 4)).start();

        for (int i = 0; i < 3; i++) {
            consumer.stop();
            consumer.start();
        }
        processing.tryEmitValue(sold("o1"));

        // 1 (first loop) + 3 (one per restart); the drained loops never polled again, and the long polls of
        // the stopped generations were cancelled, so exactly one stays parked.
        verify(client, times(4)).receiveMessage(any(ReceiveMessageRequest.class));
        assertThat(parked).hasSize(3);
        assertThat(parked.stream().filter(future -> !future.isCancelled())).hasSize(1);
    }
}
