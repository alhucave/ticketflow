package com.ticketflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.exception.InvalidStateTransitionException;
import com.ticketflow.domain.exception.OrderAlreadyExistsException;
import com.ticketflow.domain.exception.OrderNotFoundException;
import com.ticketflow.domain.exception.OrderStatusConflictException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

class DynamoDbOrderPlacementRepositoryTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final Order ORDER = new Order(new OrderId("o-1"), new EventId("e-1"), new Quantity(3),
            TicketStatus.RESERVED, new IdempotencyKey("k-1"), NOW.plusSeconds(600), NOW);
    private static final Map<String, AttributeValue> SOME_ITEM =
            Map.of("status", AttributeValue.builder().s("PENDING_CONFIRMATION").build());

    private DynamoDbAsyncClient client;
    private DynamoDbOrderPlacementRepository repository;

    @BeforeEach
    void setUp() {
        client = mock(DynamoDbAsyncClient.class);
        repository = new DynamoDbOrderPlacementRepository(client, 3, Duration.ofMillis(1));
    }

    private static CompletableFuture<TransactWriteItemsResponse> ok() {
        return CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build());
    }

    private static CancellationReason reason(String code, Map<String, AttributeValue> item) {
        var builder = CancellationReason.builder().code(code);
        if (item != null) {
            builder.item(item);
        }
        return builder.build();
    }

    private static CompletableFuture<TransactWriteItemsResponse> canceled(CancellationReason... reasons) {
        return CompletableFuture.failedFuture(
                TransactionCanceledException.builder().cancellationReasons(reasons).build());
    }

    @Test
    void placeReservation_success_sendsInventoryOrderAndAuditInOneTransaction() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(ok());

        StepVerifier.create(repository.placeReservation(ORDER, "actor")).expectNext(ORDER).verifyComplete();

        var captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());
        var items = captor.getValue().transactItems();
        assertThat(items).hasSize(3);
        var inventory = items.get(0).update();
        assertThat(inventory.tableName()).isEqualTo("inventory");
        assertThat(inventory.conditionExpression()).contains("#src >= :qty");
        assertThat(inventory.updateExpression()).contains("#version = #version + :one");
        assertThat(inventory.expressionAttributeNames()).containsEntry("#src", "available")
                .containsEntry("#dst", "reserved");
        assertThat(items.get(1).put().tableName()).isEqualTo("orders");
        assertThat(items.get(1).put().conditionExpression()).isEqualTo("attribute_not_exists(orderId)");
        assertThat(items.get(1).put().item().get("status").s()).isEqualTo("RESERVED");
        assertThat(items.get(2).put().tableName()).isEqualTo("order_audit");
        assertThat(items.get(2).put().item().get("from").s()).isEqualTo("AVAILABLE");
        assertThat(items.get(2).put().item().get("to").s()).isEqualTo("RESERVED");
    }

    @Test
    void placeReservation_orderNotReserved_failsWithoutCallingDynamo() {
        var pending = ORDER.transitionTo(TicketStatus.PENDING_CONFIRMATION);

        StepVerifier.create(repository.placeReservation(pending, "actor"))
                .expectError(IllegalArgumentException.class).verify();
        verify(client, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void placeReservation_blankActor_failsWithoutCallingDynamo() {
        StepVerifier.create(repository.placeReservation(ORDER, " ")).expectError(IllegalArgumentException.class).verify();
        verify(client, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void placeReservation_orderExists_mapsToAlreadyExistsEvenIfInventoryAlsoFailed() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                canceled(reason("ConditionalCheckFailed", SOME_ITEM), reason("ConditionalCheckFailed", SOME_ITEM),
                        reason("None", null)));

        StepVerifier.create(repository.placeReservation(ORDER, "actor"))
                .expectError(OrderAlreadyExistsException.class).verify();
        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void placeReservation_inventoryConditionFailsWithItem_mapsToInsufficient() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                canceled(reason("ConditionalCheckFailed", SOME_ITEM), reason("None", null), reason("None", null)));

        StepVerifier.create(repository.placeReservation(ORDER, "actor"))
                .expectError(InsufficientInventoryException.class).verify();
    }

    @Test
    void placeReservation_inventoryConditionFailsWithoutItem_mapsToEventNotFound() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                canceled(reason("ConditionalCheckFailed", null), reason("None", null), reason("None", null)));

        StepVerifier.create(repository.placeReservation(ORDER, "actor"))
                .expectError(EventNotFoundException.class).verify();
    }

    @Test
    void placeReservation_transactionConflictThenSuccess_isRetried() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                canceled(reason("TransactionConflict", null), reason("None", null), reason("None", null)), ok());

        StepVerifier.create(repository.placeReservation(ORDER, "actor")).expectNext(ORDER).verifyComplete();
        verify(client, times(2)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void placeReservation_persistentConflict_failsAfterRetryBudget() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                canceled(reason("TransactionConflict", null), reason("None", null), reason("None", null)));

        StepVerifier.create(repository.placeReservation(ORDER, "actor"))
                .expectError(TransactionCanceledException.class).verify();
        verify(client, times(4)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void placeReservation_cancellationWithoutRecognisedReason_propagatesOriginal() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                canceled(reason("ValidationError", null), reason("None", null), reason("None", null)));

        StepVerifier.create(repository.placeReservation(ORDER, "actor"))
                .expectError(TransactionCanceledException.class).verify();
    }

    @Test
    void releaseReservation_success_sendsOrderAuditAndInventoryInOneTransaction() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(ok());

        StepVerifier.create(repository.releaseReservation(
                        ORDER, TicketStatus.RESERVED, "actor", "enqueue failed", NOW))
                .assertNext(entry -> {
                    assertThat(entry.reason()).isEqualTo("enqueue failed");
                    assertThat(entry.to()).isEqualTo(TicketStatus.AVAILABLE);
                })
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());
        var items = captor.getValue().transactItems();
        assertThat(items).hasSize(3);
        assertThat(items.get(0).update().tableName()).isEqualTo("orders");
        assertThat(items.get(0).update().conditionExpression()).contains("#status = :expected");
        assertThat(items.get(1).put().item().get("reason").s()).isEqualTo("enqueue failed");
        assertThat(items.get(2).update().expressionAttributeNames()).containsEntry("#src", "reserved")
                .containsEntry("#dst", "available");
    }

    @Test
    void releaseReservation_fromPendingConfirmation_drainsPendingCounter() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(ok());

        StepVerifier.create(repository.releaseReservation(
                        ORDER.transitionTo(TicketStatus.PENDING_CONFIRMATION),
                        TicketStatus.PENDING_CONFIRMATION, "scheduler", "expired", NOW))
                .expectNextCount(1).verifyComplete();

        var captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());
        assertThat(captor.getValue().transactItems().get(2).update().expressionAttributeNames())
                .containsEntry("#src", "pendingConfirmation");
    }

    @Test
    void releaseReservation_invalidTransition_failsWithoutCallingDynamo() {
        StepVerifier.create(repository.releaseReservation(ORDER, TicketStatus.SOLD, "actor", "x", NOW))
                .expectError(InvalidStateTransitionException.class).verify();
        verify(client, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void releaseReservation_statusChanged_mapsToConflictWithActualStatus() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                canceled(reason("ConditionalCheckFailed", SOME_ITEM), reason("None", null), reason("None", null)));

        StepVerifier.create(repository.releaseReservation(ORDER, TicketStatus.RESERVED, "actor", "r", NOW))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(OrderStatusConflictException.class);
                    assertThat(((OrderStatusConflictException) e).actual())
                            .isEqualTo(TicketStatus.PENDING_CONFIRMATION);
                })
                .verify();
    }

    @Test
    void releaseReservation_conflictItemWithoutStatus_reportsUnknownActual() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                canceled(reason("ConditionalCheckFailed", Map.of("orderId", AttributeValue.builder().s("o-1").build())),
                        reason("None", null), reason("None", null)));

        StepVerifier.create(repository.releaseReservation(ORDER, TicketStatus.RESERVED, "actor", "r", NOW))
                .expectErrorSatisfies(e -> assertThat(((OrderStatusConflictException) e).actual()).isNull())
                .verify();
    }

    @Test
    void releaseReservation_orderMissing_mapsToNotFound() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                canceled(reason("ConditionalCheckFailed", null), reason("None", null), reason("None", null)));

        StepVerifier.create(repository.releaseReservation(ORDER, TicketStatus.RESERVED, "actor", "r", NOW))
                .expectError(OrderNotFoundException.class).verify();
    }

    @Test
    void releaseReservation_inventoryCounterTooLow_failsAsInconsistent() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                canceled(reason("None", null), reason("None", null), reason("ConditionalCheckFailed", SOME_ITEM)));

        StepVerifier.create(repository.releaseReservation(ORDER, TicketStatus.RESERVED, "actor", "r", NOW))
                .expectError(IllegalStateException.class).verify();
    }

    @Test
    void releaseReservation_otherCancellation_propagatesOriginal() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                canceled(reason("ValidationError", null)));

        StepVerifier.create(repository.releaseReservation(ORDER, TicketStatus.RESERVED, "actor", "r", NOW))
                .expectError(TransactionCanceledException.class).verify();
    }
}
