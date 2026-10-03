package com.ticketflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.exception.OrderEnqueueFailedException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderAuditEntry;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderPlacementRepository;
import com.ticketflow.domain.port.OrderQueuePublisher;
import com.ticketflow.domain.port.OrderRepository;
import com.ticketflow.usecase.GetOrderStatusUseCase;
import com.ticketflow.usecase.IssueComplimentaryResult;
import com.ticketflow.usecase.IssueComplimentaryUseCase;
import com.ticketflow.usecase.RequestPurchaseUseCase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;

/**
 * A client that disconnects mid-request must not interrupt "reserve, publish, compensate". Everything runs
 * synchronously on the test thread (a gated publisher parks the chain; emitting its gate resumes it), so the
 * outcomes are deterministic without sleeps.
 */
class DetachedTest {

    private static final String KEY = "key-0123456789abcdef";
    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    /** Publisher that parks until its gate opens, then succeeds or fails as configured. */
    private static final class GatedPublisher implements OrderQueuePublisher {
        final Sinks.Empty<Void> gate = Sinks.empty();
        final AtomicInteger entered = new AtomicInteger();
        final AtomicInteger published = new AtomicInteger();
        final AtomicReference<String> correlationId = new AtomicReference<>();
        volatile RuntimeException failure;

        @Override
        public Mono<Void> publish(Order order) {
            return Mono.deferContextual(context -> {
                entered.incrementAndGet();
                correlationId.set(context.getOrDefault("correlationId", "<none>"));
                return gate.asMono().then(Mono.defer(() -> failure != null
                        ? Mono.<Void>error(failure) : Mono.fromRunnable(published::incrementAndGet)));
            });
        }
    }

    private final OrderPlacementRepository placement = mock(OrderPlacementRepository.class);
    private final GatedPublisher queue = new GatedPublisher();
    private final RequestPurchaseUseCase useCase = new RequestPurchaseUseCase(placement, mock(OrderRepository.class),
            queue, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10));
    private final OrderController controller = new OrderController(useCase, mock(GetOrderStatusUseCase.class), 10);

    DetachedTest() {
        when(placement.placeReservation(any(), any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(placement.releaseReservation(any(), any(), any(), any(), any())).thenAnswer(i -> Mono.just(
                new OrderAuditEntry(((Order) i.getArgument(0)).id(), NOW, TicketStatus.RESERVED,
                        TicketStatus.AVAILABLE, "a", "r")));
    }

    private Disposable purchaseThenCancelWhilePublishing() {
        Disposable request = controller.purchase(KEY, Mono.just(new PurchaseRequest("evt-1", 2)))
                .contextWrite(Context.of("correlationId", "corr-42"))
                .subscribe();
        assertThat(queue.entered).hasValue(1); // reserved, now parked inside the publisher
        request.dispose();                      // the client disconnects
        assertThat(request.isDisposed()).isTrue();
        return request;
    }

    @Test
    void purchase_clientCancelsWhilePublishing_messageIsStillPublishedExactlyOnceAndNothingIsReleased() {
        purchaseThenCancelWhilePublishing();
        assertThat(queue.published).hasValue(0);

        queue.gate.tryEmitEmpty(); // the broker answers after the client is gone

        assertThat(queue.published).hasValue(1);
        assertThat(queue.entered).hasValue(1);
        verify(placement, times(1)).placeReservation(any(), any());
        verify(placement, never()).releaseReservation(any(), any(), any(), any(), any());
        assertThat(queue.correlationId).hasValue("corr-42");
    }

    @Test
    void purchase_clientCancelsAndPublishThenFails_compensationStillRunsSoNoInventoryStaysReserved() {
        purchaseThenCancelWhilePublishing();
        queue.failure = new IllegalStateException("queue down");

        queue.gate.tryEmitEmpty();

        assertThat(queue.published).hasValue(0);
        verify(placement, times(1)).placeReservation(any(), any());
        verify(placement, times(1)).releaseReservation(any(Order.class), org.mockito.ArgumentMatchers.eq(TicketStatus.RESERVED),
                any(), any(), any());
    }

    @Test
    void purchase_clientCancelsAndPublishAndReleaseBothFail_isLeftToTheExpirySweepNotLostSilently() {
        org.mockito.Mockito.doReturn(Mono.error(new IllegalStateException("dynamo down")))
                .when(placement).releaseReservation(any(), any(), any(), any(), any());
        purchaseThenCancelWhilePublishing();
        queue.failure = new IllegalStateException("queue down");

        queue.gate.tryEmitEmpty();

        verify(placement, times(1)).releaseReservation(any(), any(), any(), any(), any());
    }

    @Test
    void useCaseSubscribedDirectly_clientCancel_wouldStrandTheReservation_whichIsWhyTheControllerDetaches() {
        // Negative control: the same chain without Detached is cancelled half way.
        Disposable request = useCase.execute(new com.ticketflow.usecase.RequestPurchaseCommand(
                new EventId("evt-1"), new Quantity(2), new com.ticketflow.domain.model.IdempotencyKey(KEY))).subscribe();
        assertThat(queue.entered).hasValue(1);
        request.dispose();

        queue.gate.tryEmitEmpty();

        assertThat(queue.published).hasValue(0);
        verify(placement, never()).releaseReservation(any(), any(), any(), any(), any());
    }

    @Test
    void purchase_clientStaysConnected_behavesAsBefore() {
        var result = new AtomicReference<Object>();
        controller.purchase(KEY, Mono.just(new PurchaseRequest("evt-1", 2))).subscribe(result::set);
        queue.gate.tryEmitEmpty();

        assertThat(result.get()).isNotNull();
        assertThat(queue.published).hasValue(1);
    }

    @Test
    void purchase_publishFailsWithConnectedClient_errorStillReachesTheClient() {
        queue.failure = new IllegalStateException("queue down");
        var error = new AtomicReference<Throwable>();
        controller.purchase(KEY, Mono.just(new PurchaseRequest("evt-1", 2))).subscribe(null, error::set);
        queue.gate.tryEmitEmpty();

        assertThat(error.get()).isInstanceOf(OrderEnqueueFailedException.class);
    }

    @Test
    void detach_retryAfterTheOnlySubscriberCancelled_stillSeesTheOriginalReactorContext() {
        var gate = Sinks.<Void>empty();
        var seen = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var attempts = new AtomicInteger();
        Mono<String> work = Mono.deferContextual(context -> {
            seen.add(context.getOrDefault("correlationId", "<none>"));
            return gate.asMono().then(Mono.defer(() -> attempts.incrementAndGet() == 1
                    ? Mono.<String>error(new IllegalStateException("transient")) : Mono.just("done")));
        }).retry(1);

        Disposable request = Detached.detach(work).contextWrite(Context.of("correlationId", "corr-7")).subscribe();
        request.dispose();
        gate.tryEmitEmpty(); // first attempt fails after the cancel; the retry resubscribes

        assertThat(seen).containsExactly("corr-7", "corr-7");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void complimentary_clientCancels_issuanceStillCompletes() {
        var useCaseMock = mock(IssueComplimentaryUseCase.class);
        var gate = Sinks.<Void>empty();
        var completed = new AtomicInteger();
        when(useCaseMock.execute(any())).thenReturn(gate.asMono().then(Mono.fromSupplier(() -> {
            completed.incrementAndGet();
            return new IssueComplimentaryResult(new OrderId("ord-c"), new EventId("evt-1"), new Quantity(2),
                    TicketStatus.COMPLIMENTARY, false);
        })));
        var complimentary = new ComplimentaryController(useCaseMock);

        Disposable request = complimentary.issue("evt-1", KEY, Mono.just(new ComplimentaryRequest(2, "VIP")))
                .subscribe();
        verify(useCaseMock).execute(any());
        request.dispose();
        assertThat(completed).hasValue(0);
        gate.tryEmitEmpty();

        assertThat(completed).hasValue(1);
    }
}
