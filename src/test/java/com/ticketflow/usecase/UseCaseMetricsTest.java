package com.ticketflow.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.exception.IdempotencyKeyReusedException;
import com.ticketflow.domain.exception.IdempotentOrderNotActiveException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.exception.OrderAlreadyExistsException;
import com.ticketflow.domain.exception.OrderStatusConflictException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderAuditEntry;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderFulfillmentRepository;
import com.ticketflow.domain.port.OrderPlacementRepository;
import com.ticketflow.domain.port.OrderQueuePublisher;
import com.ticketflow.domain.port.OrderRepository;
import com.ticketflow.usecase.BusinessMetrics.ConflictType;
import com.ticketflow.usecase.BusinessMetrics.Operation;
import com.ticketflow.usecase.BusinessMetrics.ProcessOutcome;
import com.ticketflow.usecase.BusinessMetrics.PurchaseRejection;
import com.ticketflow.usecase.BusinessMetrics.ReleaseReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Every call site of {@link BusinessMetrics} in the use cases: right event, right tag, exactly once. */
class UseCaseMetricsTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final EventId EVENT = new EventId("e-1");
    private static final IdempotencyKey KEY = new IdempotencyKey("key-1");
    private static final OrderId ORDER_ID = OrderId.fromIdempotencyKey(KEY);
    private static final OrderId PROCESSED_ID = new OrderId("o-1");

    /** Records each call as {@code name[:tag...]}. */
    private static final class Recorder implements BusinessMetrics {
        final List<String> calls = new ArrayList<>();

        @Override
        public void orderPlaced() {
            calls.add("placed");
        }

        @Override
        public void purchaseReplayed() {
            calls.add("replayed");
        }

        @Override
        public void purchaseRejected(PurchaseRejection reason) {
            calls.add("rejected:" + reason.tag());
        }

        @Override
        public void orderSold() {
            calls.add("sold");
        }

        @Override
        public void orderReleased(ReleaseReason reason) {
            calls.add("released:" + reason.tag());
        }

        @Override
        public void complimentaryIssued() {
            calls.add("complimentary");
        }

        @Override
        public void orderProcessed(ProcessOutcome outcome) {
            calls.add("processed:" + outcome.tag());
        }

        @Override
        public void conflict(ConflictType type, Operation operation) {
            calls.add("conflict:" + type.tag() + ":" + operation.tag());
        }
    }

    private final Recorder metrics = new Recorder();
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderPlacementRepository placement = mock(OrderPlacementRepository.class);
    private final OrderFulfillmentRepository fulfillment = mock(OrderFulfillmentRepository.class);
    private final OrderQueuePublisher queue = mock(OrderQueuePublisher.class);
    private RequestPurchaseUseCase purchase;

    @BeforeEach
    void setUp() {
        purchase = new RequestPurchaseUseCase(placement, orders, queue, CLOCK, Duration.ofMinutes(10), metrics);
    }

    private static RequestPurchaseCommand command(int quantity) {
        return new RequestPurchaseCommand(EVENT, new Quantity(quantity), KEY);
    }

    private static Order stored(TicketStatus status, int quantity) {
        return new Order(ORDER_ID, EVENT, new Quantity(quantity), status, KEY, NOW.plusSeconds(600), NOW);
    }

    private static OrderAuditEntry audit(TicketStatus from, TicketStatus to) {
        return new OrderAuditEntry(ORDER_ID, NOW, from, to, "actor", null);
    }

    private void placementSucceeds() {
        when(placement.placeReservation(any(), any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
    }

    private void placementFindsExisting() {
        when(placement.placeReservation(any(), any()))
                .thenReturn(Mono.error(new OrderAlreadyExistsException(ORDER_ID)));
    }

    @Test
    void purchase_accepted_countsOneOrderPlacedAndNothingElse() {
        placementSucceeds();
        when(queue.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(purchase.execute(command(3))).expectNextCount(1).verifyComplete();

        assertThat(metrics.calls).containsExactly("placed");
    }

    @Test
    void purchase_replayOfExistingOrder_countsReplayNotANewOrder() {
        placementFindsExisting();
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(stored(TicketStatus.SOLD, 3)));

        StepVerifier.create(purchase.execute(command(3))).expectNextCount(1).verifyComplete();

        assertThat(metrics.calls).containsExactly("replayed");
    }

    @Test
    void purchase_replayOfStrandedReservation_countsReplayOnce() {
        placementFindsExisting();
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(stored(TicketStatus.RESERVED, 3)));
        when(queue.publish(any())).thenReturn(Mono.error(new IllegalStateException("down")));

        StepVerifier.create(purchase.execute(command(3))).expectNextCount(1).verifyComplete();

        assertThat(metrics.calls).containsExactly("replayed");
    }

    @Test
    void purchase_notEnoughInventory_countsRejectionAndInventoryConflict() {
        when(placement.placeReservation(any(), any()))
                .thenReturn(Mono.error(new InsufficientInventoryException(EVENT, new Quantity(3))));

        StepVerifier.create(purchase.execute(command(3))).expectError(InsufficientInventoryException.class).verify();

        assertThat(metrics.calls).containsExactly("rejected:insufficient_inventory",
                "conflict:inventory_insufficient:purchase");
    }

    @Test
    void purchase_keyReusedWithDifferentPayload_countsRejection() {
        placementFindsExisting();
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(stored(TicketStatus.RESERVED, 9)));

        StepVerifier.create(purchase.execute(command(3))).expectError(IdempotencyKeyReusedException.class).verify();

        assertThat(metrics.calls).containsExactly("rejected:idempotency_key_reused");
    }

    @Test
    void purchase_keyOfReleasedOrder_countsOrderNotActiveRejection() {
        placementFindsExisting();
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(stored(TicketStatus.AVAILABLE, 3)));

        StepVerifier.create(purchase.execute(command(3))).expectError(IdempotentOrderNotActiveException.class)
                .verify();

        assertThat(metrics.calls).containsExactly("rejected:order_not_active");
    }

    @Test
    void purchase_publishFailsAndCompensates_countsPlacedRejectedAndReleasedByPublishFailure() {
        placementSucceeds();
        when(queue.publish(any())).thenReturn(Mono.error(new IllegalStateException("queue down")));
        when(placement.releaseReservation(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(audit(TicketStatus.RESERVED, TicketStatus.AVAILABLE)));

        StepVerifier.create(purchase.execute(command(3))).expectError().verify();

        assertThat(metrics.calls).containsExactly("placed", "rejected:enqueue_failed", "released:publish_failed");
    }

    @Test
    void purchase_publishFailsAndCompensationFails_doesNotCountARelease() {
        placementSucceeds();
        when(queue.publish(any())).thenReturn(Mono.error(new IllegalStateException("queue down")));
        when(placement.releaseReservation(any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("db down")));

        StepVerifier.create(purchase.execute(command(3))).expectError().verify();

        assertThat(metrics.calls).containsExactly("placed", "rejected:enqueue_failed");
    }

    // ---- ProcessOrderUseCase ----

    private ProcessOrderUseCase processor() {
        return new ProcessOrderUseCase(orders, fulfillment, placement, CLOCK, metrics);
    }

    private static Order processed(TicketStatus status, Instant expiresAt) {
        return new Order(PROCESSED_ID, EVENT, new Quantity(2), status, KEY, expiresAt, NOW.minusSeconds(60));
    }

    private void fulfillmentSucceeds() {
        when(fulfillment.markPendingConfirmation(any(), any(), any()))
                .thenReturn(Mono.just(audit(TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION)));
        when(fulfillment.confirmSale(any(), any(), any()))
                .thenReturn(Mono.just(audit(TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD)));
    }

    @Test
    void process_validReservation_countsSaleAndSoldOutcome() {
        when(orders.findById(PROCESSED_ID)).thenReturn(Mono.just(processed(TicketStatus.RESERVED, NOW.plusSeconds(60))));
        fulfillmentSucceeds();

        StepVerifier.create(processor().execute(PROCESSED_ID)).expectNextCount(1).verifyComplete();

        assertThat(metrics.calls).containsExactly("sold", "processed:sold");
    }

    @Test
    void process_expiredReservation_countsReleaseByExpiryAndReleasedOutcome() {
        when(orders.findById(PROCESSED_ID)).thenReturn(Mono.just(processed(TicketStatus.RESERVED, NOW.minusSeconds(1))));
        when(placement.releaseReservation(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(audit(TicketStatus.RESERVED, TicketStatus.AVAILABLE)));

        StepVerifier.create(processor().execute(PROCESSED_ID)).expectNextCount(1).verifyComplete();

        assertThat(metrics.calls).containsExactly("released:expired", "processed:released_as_expired");
    }

    @Test
    void process_alreadySold_countsAlreadyProcessedOutcome() {
        when(orders.findById(PROCESSED_ID)).thenReturn(Mono.just(processed(TicketStatus.SOLD, NOW.plusSeconds(60))));

        StepVerifier.create(processor().execute(PROCESSED_ID)).expectNextCount(1).verifyComplete();

        assertThat(metrics.calls).containsExactly("processed:already_processed");
    }

    @Test
    void process_missingOrder_countsOrderMissingOutcome() {
        when(orders.findById(PROCESSED_ID)).thenReturn(Mono.empty());

        StepVerifier.create(processor().execute(PROCESSED_ID)).expectNextCount(1).verifyComplete();

        assertThat(metrics.calls).containsExactly("processed:order_missing");
    }

    @Test
    void process_lostRaceThenFound_countsOneStatusConflictAndTheFinalOutcome() {
        when(orders.findById(PROCESSED_ID)).thenReturn(
                Mono.just(processed(TicketStatus.RESERVED, NOW.plusSeconds(60))),
                Mono.just(processed(TicketStatus.SOLD, NOW.plusSeconds(60))));
        when(fulfillment.markPendingConfirmation(any(), any(), any())).thenReturn(Mono.error(
                new OrderStatusConflictException(PROCESSED_ID, TicketStatus.RESERVED, TicketStatus.SOLD)));

        StepVerifier.create(processor().execute(PROCESSED_ID)).expectNextCount(1).verifyComplete();

        assertThat(metrics.calls).containsExactly("conflict:order_status:process_order",
                "processed:already_processed");
    }

    @Test
    void process_inventoryGuardRefuses_countsInventoryConflictAndPropagates() {
        when(orders.findById(PROCESSED_ID)).thenReturn(Mono.just(processed(TicketStatus.RESERVED, NOW.plusSeconds(60))));
        when(fulfillment.markPendingConfirmation(any(), any(), any()))
                .thenReturn(Mono.error(new InsufficientInventoryException(EVENT, new Quantity(2))));

        StepVerifier.create(processor().execute(PROCESSED_ID)).expectError(InsufficientInventoryException.class)
                .verify();

        assertThat(metrics.calls).containsExactly("conflict:inventory_insufficient:process_order");
    }

    // ---- ReleaseExpiredReservationsUseCase ----

    @Test
    void sweep_releasedAndConflictingAndFailing_countsEachOutcomeOnce() {
        var released = processed(TicketStatus.RESERVED, NOW.minusSeconds(1));
        var lost = new Order(new OrderId("o-2"), EVENT, new Quantity(1), TicketStatus.RESERVED, KEY,
                NOW.minusSeconds(1), NOW.minusSeconds(601));
        var broken = new Order(new OrderId("o-3"), EVENT, new Quantity(1), TicketStatus.RESERVED, KEY,
                NOW.minusSeconds(1), NOW.minusSeconds(601));
        when(orders.findExpiredReservations(NOW)).thenReturn(Flux.just(released, lost, broken));
        when(placement.releaseReservation(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            return switch (order.id().value()) {
                case "o-1" -> Mono.just(audit(TicketStatus.RESERVED, TicketStatus.AVAILABLE));
                case "o-2" -> Mono.error(new OrderStatusConflictException(order.id(), TicketStatus.RESERVED,
                        TicketStatus.SOLD));
                default -> Mono.error(new IllegalStateException("db down"));
            };
        });
        var sweep = new ReleaseExpiredReservationsUseCase(orders, placement, CLOCK, 1, 10, metrics);

        StepVerifier.create(sweep.execute()).expectNextCount(1).verifyComplete();

        assertThat(metrics.calls).containsExactly("released:expired", "conflict:order_status:expiration_sweep");
    }

    // ---- IssueComplimentaryUseCase ----

    @Test
    void complimentary_issued_countsOnce() {
        when(placement.issueComplimentary(any(), any(), any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        var useCase = new IssueComplimentaryUseCase(placement, orders, CLOCK, metrics);

        StepVerifier.create(useCase.execute(new IssueComplimentaryCommand(EVENT, new Quantity(2), KEY, "guests")))
                .expectNextCount(1).verifyComplete();

        assertThat(metrics.calls).containsExactly("complimentary");
    }

    @Test
    void complimentary_notEnoughAvailable_countsInventoryConflict() {
        when(placement.issueComplimentary(any(), any(), any()))
                .thenReturn(Mono.error(new InsufficientInventoryException(EVENT, new Quantity(2))));
        var useCase = new IssueComplimentaryUseCase(placement, orders, CLOCK, metrics);

        StepVerifier.create(useCase.execute(new IssueComplimentaryCommand(EVENT, new Quantity(2), KEY, "guests")))
                .expectError(InsufficientInventoryException.class).verify();

        assertThat(metrics.calls).containsExactly("conflict:inventory_insufficient:complimentary");
    }

    // ---- defaults ----

    @Test
    void noopMetrics_everyMethod_doesNothing() {
        BusinessMetrics noop = BusinessMetrics.NOOP;
        noop.orderPlaced();
        noop.purchaseReplayed();
        noop.purchaseRejected(PurchaseRejection.ENQUEUE_FAILED);
        noop.orderSold();
        noop.orderReleased(ReleaseReason.EXPIRED);
        noop.complimentaryIssued();
        noop.orderProcessed(ProcessOutcome.SOLD);
        noop.conflict(ConflictType.ORDER_STATUS, Operation.PURCHASE);
    }

    @Test
    void processOutcome_ofEveryResult_mapsToItsOwnTag() {
        assertThat(ProcessOutcome.of(new ProcessOrderResult.Sold(PROCESSED_ID))).isEqualTo(ProcessOutcome.SOLD);
        assertThat(ProcessOutcome.of(new ProcessOrderResult.ReleasedAsExpired(PROCESSED_ID)))
                .isEqualTo(ProcessOutcome.RELEASED_AS_EXPIRED);
        assertThat(ProcessOutcome.of(new ProcessOrderResult.AlreadyProcessed(PROCESSED_ID, TicketStatus.SOLD)))
                .isEqualTo(ProcessOutcome.ALREADY_PROCESSED);
        assertThat(ProcessOutcome.of(new ProcessOrderResult.OrderMissing(PROCESSED_ID)))
                .isEqualTo(ProcessOutcome.ORDER_MISSING);
    }
}
