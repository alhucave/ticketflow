package com.ticketflow.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.exception.IdempotencyKeyReusedException;
import com.ticketflow.domain.exception.IdempotentOrderNotActiveException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.exception.OrderAlreadyExistsException;
import com.ticketflow.domain.exception.OrderEnqueueFailedException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderAuditEntry;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderPlacementRepository;
import com.ticketflow.domain.port.OrderQueuePublisher;
import com.ticketflow.domain.port.OrderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RequestPurchaseUseCaseTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final EventId EVENT = new EventId("e-1");
    private static final IdempotencyKey KEY = new IdempotencyKey("key-1");
    private static final OrderId ORDER_ID = OrderId.fromIdempotencyKey(KEY);
    private static final RequestPurchaseCommand COMMAND = new RequestPurchaseCommand(EVENT, new Quantity(3), KEY);

    private OrderPlacementRepository placement;
    private OrderRepository orders;
    private OrderQueuePublisher queue;
    private RequestPurchaseUseCase useCase;

    @BeforeEach
    void setUp() {
        placement = mock(OrderPlacementRepository.class);
        orders = mock(OrderRepository.class);
        queue = mock(OrderQueuePublisher.class);
        useCase = new RequestPurchaseUseCase(placement, orders, queue,
                Clock.fixed(NOW, ZoneOffset.UTC), TTL);
    }

    private static Order existing(EventId event, int quantity, TicketStatus status) {
        return new Order(ORDER_ID, event, new Quantity(quantity), status, KEY, NOW.plus(TTL), NOW.minusSeconds(5));
    }

    private void placementSucceeds() {
        when(placement.placeReservation(any(), any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
    }

    @Test
    void execute_validRequest_reservesEnqueuesAndReturnsOrderId() {
        placementSucceeds();
        when(queue.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(COMMAND))
                .assertNext(result -> {
                    assertThat(result.orderId()).isEqualTo(ORDER_ID);
                    assertThat(result.status()).isEqualTo(TicketStatus.RESERVED);
                    assertThat(result.replayed()).isFalse();
                })
                .verifyComplete();

        var placed = ArgumentCaptor.forClass(Order.class);
        verify(placement).placeReservation(placed.capture(), eq(RequestPurchaseUseCase.ACTOR));
        assertThat(placed.getValue().status()).isEqualTo(TicketStatus.RESERVED);
        verify(queue).publish(placed.getValue());
    }

    @Test
    void execute_validRequest_expiryIsExactlyNowPlusTtl() {
        placementSucceeds();
        when(queue.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(COMMAND))
                .assertNext(result -> assertThat(result.reservationExpiresAt()).isEqualTo(NOW.plus(TTL)))
                .verifyComplete();

        var placed = ArgumentCaptor.forClass(Order.class);
        verify(placement).placeReservation(placed.capture(), any());
        assertThat(placed.getValue().createdAt()).isEqualTo(NOW);
        assertThat(placed.getValue().reservationExpiresAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    void execute_customTtl_isHonoured() {
        useCase = new RequestPurchaseUseCase(placement, orders, queue, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(2));
        placementSucceeds();
        when(queue.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(COMMAND))
                .assertNext(result -> assertThat(result.reservationExpiresAt()).isEqualTo(NOW.plusSeconds(120)))
                .verifyComplete();
    }

    @Test
    void execute_sameKeyDifferentCommandObjects_derivesSameOrderId() {
        assertThat(OrderId.fromIdempotencyKey(new IdempotencyKey("key-1"))).isEqualTo(ORDER_ID);
        assertThat(OrderId.fromIdempotencyKey(new IdempotencyKey("key-2"))).isNotEqualTo(ORDER_ID);
    }

    @Test
    void execute_orderAlreadyExistsWithSamePayload_returnsExistingWithoutPublishing() {
        when(placement.placeReservation(any(), any())).thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(existing(EVENT, 3, TicketStatus.PENDING_CONFIRMATION)));

        StepVerifier.create(useCase.execute(COMMAND))
                .assertNext(result -> {
                    assertThat(result.orderId()).isEqualTo(ORDER_ID);
                    assertThat(result.status()).isEqualTo(TicketStatus.PENDING_CONFIRMATION);
                    assertThat(result.replayed()).isTrue();
                })
                .verifyComplete();
        verifyNoInteractions(queue);
        verify(placement, never()).releaseReservation(any(), any(), any(), any(), any());
    }

    @Test
    void execute_replayOfStillReservedOrder_republishesItsMessageOnce() {
        when(placement.placeReservation(any(), any())).thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
        var stranded = existing(EVENT, 3, TicketStatus.RESERVED);
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(stranded));
        var published = new java.util.concurrent.atomic.AtomicInteger();
        when(queue.publish(any())).thenReturn(Mono.fromRunnable(published::incrementAndGet));

        StepVerifier.create(useCase.execute(COMMAND))
                .assertNext(result -> {
                    assertThat(result.orderId()).isEqualTo(ORDER_ID);
                    assertThat(result.status()).isEqualTo(TicketStatus.RESERVED);
                    assertThat(result.replayed()).isTrue();
                })
                .verifyComplete();

        verify(queue).publish(stranded);
        assertThat(published).hasValue(1);
        verify(placement, never()).releaseReservation(any(), any(), any(), any(), any());
    }

    @Test
    void execute_replayOfReservedOrderWhenRepublishFails_stillAnswersAndReleasesNothingAndLogsNoInternals() {
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RequestPurchaseUseCase.class);
        logs.start();
        logger.addAppender(logs);
        try {
            when(placement.placeReservation(any(), any()))
                    .thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
            when(orders.findById(ORDER_ID)).thenReturn(Mono.just(existing(EVENT, 3, TicketStatus.RESERVED)));
            when(queue.publish(any())).thenReturn(Mono.error(new IllegalStateException("sqs-internal-host:4566")));

            StepVerifier.create(useCase.execute(COMMAND))
                    .assertNext(result -> {
                        assertThat(result.status()).isEqualTo(TicketStatus.RESERVED);
                        assertThat(result.replayed()).isTrue();
                    })
                    .verifyComplete();

            verify(placement, never()).releaseReservation(any(), any(), any(), any(), any());
            assertThat(logs.list).anySatisfy(line -> assertThat(line.getFormattedMessage())
                    .contains("could not republish").contains("IllegalStateException"));
            assertThat(logs.list).allSatisfy(line -> {
                assertThat(line.getFormattedMessage()).doesNotContain("sqs-internal");
                assertThat(line.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void execute_replayOfReservedOrderWhenPublishThrowsSynchronously_isContainedToo() {
        when(placement.placeReservation(any(), any())).thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(existing(EVENT, 3, TicketStatus.RESERVED)));
        when(queue.publish(any())).thenThrow(new IllegalStateException("boom"));

        StepVerifier.create(useCase.execute(COMMAND)).assertNext(r -> assertThat(r.replayed()).isTrue())
                .verifyComplete();
    }

    @Test
    void execute_replayOfOrderPastReserved_neverRepublishes() {
        when(placement.placeReservation(any(), any())).thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
        for (var status : new TicketStatus[] {TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD}) {
            when(orders.findById(ORDER_ID)).thenReturn(Mono.just(existing(EVENT, 3, status)));
            StepVerifier.create(useCase.execute(COMMAND)).assertNext(r -> assertThat(r.replayed()).isTrue())
                    .verifyComplete();
        }
        verifyNoInteractions(queue);
    }

    @Test
    void execute_orderAlreadyExistsButReleased_failsWithNotActiveAndDoesNotPublish() {
        when(placement.placeReservation(any(), any())).thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(existing(EVENT, 3, TicketStatus.AVAILABLE)));

        StepVerifier.create(useCase.execute(COMMAND))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(IdempotentOrderNotActiveException.class);
                    assertThat(((IdempotentOrderNotActiveException) e).key()).isEqualTo(KEY);
                    assertThat(((IdempotentOrderNotActiveException) e).orderId()).isEqualTo(ORDER_ID);
                })
                .verify();
        verifyNoInteractions(queue);
        verify(placement, never()).releaseReservation(any(), any(), any(), any(), any());
    }

    @Test
    void execute_releasedOrderWithDifferentPayload_keyReusedTakesPrecedence() {
        when(placement.placeReservation(any(), any())).thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(existing(EVENT, 7, TicketStatus.AVAILABLE)));

        StepVerifier.create(useCase.execute(COMMAND)).expectError(IdempotencyKeyReusedException.class).verify();
    }

    @Test
    void execute_orderAlreadyExistsWithDifferentQuantity_failsWithKeyReused() {
        when(placement.placeReservation(any(), any())).thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(existing(EVENT, 7, TicketStatus.RESERVED)));

        StepVerifier.create(useCase.execute(COMMAND))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(IdempotencyKeyReusedException.class);
                    assertThat(((IdempotencyKeyReusedException) e).key()).isEqualTo(KEY);
                    assertThat(((IdempotencyKeyReusedException) e).orderId()).isEqualTo(ORDER_ID);
                })
                .verify();
        verifyNoInteractions(queue);
    }

    @Test
    void execute_orderAlreadyExistsWithDifferentEvent_failsWithKeyReused() {
        when(placement.placeReservation(any(), any())).thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(existing(new EventId("other"), 3, TicketStatus.RESERVED)));

        StepVerifier.create(useCase.execute(COMMAND)).expectError(IdempotencyKeyReusedException.class).verify();
    }

    @Test
    void execute_orderReportedExistingButMissing_failsWithIllegalState() {
        when(placement.placeReservation(any(), any())).thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
        when(orders.findById(ORDER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(COMMAND)).expectError(IllegalStateException.class).verify();
    }

    @Test
    void execute_insufficientInventory_propagatesAndDoesNotPublish() {
        when(placement.placeReservation(any(), any()))
                .thenReturn(Mono.error(new InsufficientInventoryException(EVENT, new Quantity(3))));

        StepVerifier.create(useCase.execute(COMMAND)).expectError(InsufficientInventoryException.class).verify();
        verifyNoInteractions(queue, orders);
    }

    @Test
    void execute_unknownEvent_propagatesEventNotFound() {
        when(placement.placeReservation(any(), any())).thenReturn(Mono.error(new EventNotFoundException(EVENT)));

        StepVerifier.create(useCase.execute(COMMAND)).expectError(EventNotFoundException.class).verify();
        verifyNoInteractions(queue);
    }

    @Test
    void execute_publishFails_releasesReservationAndFailsWithEnqueueError() {
        placementSucceeds();
        var boom = new IllegalStateException("queue down");
        when(queue.publish(any())).thenReturn(Mono.error(boom));
        when(placement.releaseReservation(any(), any(), any(), any(), any())).thenReturn(Mono.just(
                new OrderAuditEntry(ORDER_ID, NOW, TicketStatus.RESERVED, TicketStatus.AVAILABLE, "a", "r")));

        StepVerifier.create(useCase.execute(COMMAND))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(OrderEnqueueFailedException.class).hasCause(boom);
                    assertThat(((OrderEnqueueFailedException) e).reservationReleased()).isTrue();
                    assertThat(((OrderEnqueueFailedException) e).orderId()).isEqualTo(ORDER_ID);
                })
                .verify();

        verify(placement).releaseReservation(any(Order.class), eq(TicketStatus.RESERVED),
                eq(RequestPurchaseUseCase.ACTOR), eq(RequestPurchaseUseCase.PUBLISH_FAILED_REASON), eq(NOW));
    }

    @Test
    void execute_publishAndReleaseBothFail_surfacesBothAndFlagsNotReleased() {
        placementSucceeds();
        var publishError = new IllegalStateException("queue down");
        var releaseError = new IllegalStateException("dynamo down");
        when(queue.publish(any())).thenReturn(Mono.error(publishError));
        when(placement.releaseReservation(any(), any(), any(), any(), any())).thenReturn(Mono.error(releaseError));

        StepVerifier.create(useCase.execute(COMMAND))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(OrderEnqueueFailedException.class).hasCause(publishError);
                    assertThat(((OrderEnqueueFailedException) e).reservationReleased()).isFalse();
                    assertThat(e.getSuppressed()).containsExactly(releaseError);
                })
                .verify();
    }

    @Test
    void constructor_nonPositiveTtl_throws() {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThatThrownBy(() -> new RequestPurchaseUseCase(placement, orders, queue, clock, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestPurchaseUseCase(placement, orders, queue, clock, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestPurchaseUseCase(placement, orders, queue, clock, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void command_nullField_throws() {
        assertThatThrownBy(() -> new RequestPurchaseCommand(null, new Quantity(1), KEY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestPurchaseCommand(EVENT, null, KEY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestPurchaseCommand(EVENT, new Quantity(1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valueObjects_invalidInput_rejectedBeforeAnyPort() {
        assertThatThrownBy(() -> new Quantity(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyKey(" ")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(placement, queue, orders);
    }
}
