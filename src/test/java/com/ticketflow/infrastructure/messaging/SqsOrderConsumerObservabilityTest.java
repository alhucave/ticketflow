package com.ticketflow.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.infrastructure.config.SqsConsumerProperties;
import com.ticketflow.infrastructure.observability.OperationalMetrics;
import com.ticketflow.infrastructure.web.error.CorrelationContextPropagation;
import com.ticketflow.infrastructure.web.error.CorrelationId;
import com.ticketflow.usecase.ProcessOrderResult;
import com.ticketflow.usecase.ProcessOrderUseCase;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * Observability of the consumer: the correlation id of the publisher travels in a message attribute and is
 * restored (validated) into the Reactor context and MDC of that message's processing, and every message ends
 * as exactly one metric outcome.
 */
class SqsOrderConsumerObservabilityTest {

    private static final String URL = "http://sqs.test/000000000000/orders";

    private SqsAsyncClient client;
    private ProcessOrderUseCase useCase;
    private VirtualTimeScheduler timer;
    private Deque<CompletableFuture<ReceiveMessageResponse>> receives;
    private SqsOrderConsumer consumer;
    private final List<String> contextIds = new ArrayList<>();
    private final List<String> mdcIds = new ArrayList<>();
    private final List<String> outcomes = new ArrayList<>();
    private final List<Duration> durations = new ArrayList<>();
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    private final OperationalMetrics metrics = new OperationalMetrics() {
        @Override
        public void consumerMessage(ConsumerOutcome outcome, Duration elapsed) {
            outcomes.add(outcome.tag());
            durations.add(elapsed);
        }
    };

    @BeforeAll
    static void installPropagation() {
        CorrelationContextPropagation.install();
    }

    @BeforeEach
    void setUp() {
        client = mock(SqsAsyncClient.class);
        useCase = mock(ProcessOrderUseCase.class);
        timer = VirtualTimeScheduler.create();
        receives = new ArrayDeque<>();
        when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(invocation -> {
            var next = receives.poll();
            return next != null ? next : new CompletableFuture<ReceiveMessageResponse>();
        });
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));
        logs = new ListAppender<>();
        logs.start();
        logger = (Logger) LoggerFactory.getLogger(SqsOrderConsumer.class);
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        if (consumer != null) {
            consumer.stop();
            timer.advanceTimeBy(Duration.ofMinutes(5));
        }
        timer.dispose();
        MDC.clear();
    }

    private void start() {
        var props = new SqsConsumerProperties(true, 10, Duration.ofSeconds(20), Duration.ofSeconds(30), 4,
                Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(4));
        consumer = new SqsOrderConsumer(client, Mono.just(URL), useCase, props, timer, metrics);
        consumer.start();
    }

    private static Message message(String orderId, String correlationId) {
        var builder = Message.builder().messageId("m-" + orderId).receiptHandle("rh-" + orderId)
                .body("{\"version\":1,\"orderId\":\"" + orderId + "\"}")
                .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1"));
        if (correlationId != null) {
            builder.messageAttributes(Map.of("correlationId",
                    MessageAttributeValue.builder().dataType("String").stringValue(correlationId).build()));
        }
        return builder.build();
    }

    private void queue(Message... messages) {
        receives.add(CompletableFuture.completedFuture(ReceiveMessageResponse.builder().messages(messages).build()));
    }

    /** Use case result that records the correlation id seen by its pipeline (context and MDC). */
    private void useCaseSucceeds() {
        when(useCase.execute(any(OrderId.class))).thenAnswer(invocation -> Mono.deferContextual(context -> {
            contextIds.add(context.getOrDefault(CorrelationId.KEY, "none"));
            return Mono.just((ProcessOrderResult) new ProcessOrderResult.Sold(invocation.getArgument(0)))
                    .doOnNext(result -> mdcIds.add(String.valueOf(MDC.get(CorrelationId.KEY))));
        }));
    }

    @Test
    void receiveRequest_asksForTheCorrelationIdAttribute() {
        start();

        var request = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(client).receiveMessage(request.capture());
        assertThat(request.getValue().messageAttributeNames()).containsExactly("correlationId");
    }

    @Test
    void process_messageWithCorrelationId_restoresItInContextMdcAndLogs() {
        useCaseSucceeds();
        queue(message("o1", "corr-from-api-1"));

        start();

        assertThat(contextIds).containsExactly("corr-from-api-1");
        assertThat(mdcIds).containsExactly("corr-from-api-1");
        var processed = logs.list.stream().filter(e -> e.getFormattedMessage().contains("processed as")).toList();
        assertThat(processed).hasSize(1);
        assertThat(processed.get(0).getMDCPropertyMap()).containsEntry("correlationId", "corr-from-api-1");
        assertThat(MDC.get(CorrelationId.KEY)).as("nothing leaks to the polling thread").isNull();
    }

    @Test
    void process_eachMessageKeepsItsOwnCorrelationId() {
        useCaseSucceeds();
        queue(message("o1", "corr-A"), message("o2", "corr-B"));

        start();

        assertThat(contextIds).containsExactlyInAnyOrder("corr-A", "corr-B");
        assertThat(mdcIds).containsExactlyInAnyOrder("corr-A", "corr-B");
    }

    @Test
    void process_messageWithoutCorrelationId_getsAFreshOne() {
        useCaseSucceeds();
        queue(message("o1", null), message("o2", null));

        start();

        assertThat(contextIds).hasSize(2).allSatisfy(id -> assertThat(UUID.fromString(id)).isNotNull());
        assertThat(contextIds.get(0)).isNotEqualTo(contextIds.get(1));
    }

    @Test
    void process_unsafeCorrelationId_isReplacedNeverTrusted() {
        useCaseSucceeds();
        queue(message("o1", "bad id\r\nINJECTED"), message("o2", "x".repeat(65)));

        start();

        assertThat(contextIds).hasSize(2).allSatisfy(id -> {
            assertThat(id).doesNotContain("INJECTED", " ").hasSizeLessThanOrEqualTo(64);
            assertThat(UUID.fromString(id)).isNotNull();
        });
    }

    @Test
    void process_failuresAndPoison_logUnderTheMessageCorrelationId() {
        when(useCase.execute(any(OrderId.class))).thenReturn(Mono.error(new IllegalStateException("db down")));
        queue(message("o1", "corr-fail"));
        queue(Message.builder().messageId("poison").receiptHandle("rh").body("not json")
                .messageAttributes(Map.of("correlationId",
                        MessageAttributeValue.builder().dataType("String").stringValue("corr-poison").build()))
                .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1")).build());

        start();

        var warnings = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0).getMDCPropertyMap()).containsEntry("correlationId", "corr-fail");
        assertThat(warnings.get(1).getMDCPropertyMap()).containsEntry("correlationId", "corr-poison");
    }

    @Test
    void metrics_oneOutcomePerMessage_processedFailedPoison() {
        when(useCase.execute(new OrderId("ok"))).thenReturn(
                Mono.just(new ProcessOrderResult.Sold(new OrderId("ok"))));
        when(useCase.execute(new OrderId("bad"))).thenReturn(Mono.error(new IllegalStateException("db down")));
        queue(message("ok", null), message("bad", null),
                Message.builder().messageId("p").receiptHandle("rh-p").body("{").attributes(
                        Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1")).build());

        start();

        assertThat(outcomes).containsExactlyInAnyOrder("processed", "failed", "poison");
        assertThat(durations).hasSize(3).allSatisfy(d -> assertThat(d.isNegative()).isFalse());
    }

    @Test
    void metrics_deleteFails_countsAsFailedNotProcessed() {
        when(useCase.execute(any(OrderId.class))).thenReturn(Mono.just(new ProcessOrderResult.Sold(new OrderId("o1"))));
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sqs down")));
        queue(message("o1", null));

        start();

        assertThat(outcomes).containsExactly("failed");
    }
}
