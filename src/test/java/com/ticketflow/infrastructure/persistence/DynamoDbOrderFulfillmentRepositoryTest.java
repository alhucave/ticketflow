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

class DynamoDbOrderFulfillmentRepositoryTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final Order ORDER = new Order(new OrderId("o-1"), new EventId("e-1"), new Quantity(3),
            TicketStatus.RESERVED, new IdempotencyKey("k-1"), NOW.plusSeconds(600), NOW);
    private static final Map<String, AttributeValue> SOME_ITEM =
            Map.of("status", AttributeValue.builder().s("SOLD").build());

    private DynamoDbAsyncClient client;
    private DynamoDbOrderFulfillmentRepository repository;

    @BeforeEach
    void setUp() {
        client = mock(DynamoDbAsyncClient.class);
        repository = new DynamoDbOrderFulfillmentRepository(client, 3, Duration.ofMillis(1));
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

    private static CompletableFuture<TransactWriteItemsResponse> canceled(
            String first, Map<String, AttributeValue> firstItem, String third, Map<String, AttributeValue> thirdItem) {
        return canceled(reason(first, firstItem), reason("None", null), reason(third, thirdItem));
    }

    @Test
    void markPendingConfirmation_success_sendsOrderAuditAndInventoryInOneTransaction() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(ok());

        StepVerifier.create(repository.markPendingConfirmation(ORDER, "worker", NOW))
                .assertNext(entry -> {
                    assertThat(entry.from()).isEqualTo(TicketStatus.RESERVED);
                    assertThat(entry.to()).isEqualTo(TicketStatus.PENDING_CONFIRMATION);
                    assertThat(entry.actor()).isEqualTo("worker");
                })
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());
        var items = captor.getValue().transactItems();
        assertThat(items).hasSize(3);
        var order = items.get(0).update();
        assertThat(order.tableName()).isEqualTo("orders");
        assertThat(order.conditionExpression()).contains("#status = :expected");
        assertThat(order.expressionAttributeValues().get(":expected").s()).isEqualTo("RESERVED");
        assertThat(order.expressionAttributeValues().get(":target").s()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(items.get(1).put().tableName()).isEqualTo("order_audit");
        assertThat(items.get(1).put().item().get("to").s()).isEqualTo("PENDING_CONFIRMATION");
        var inventory = items.get(2).update();
        assertThat(inventory.tableName()).isEqualTo("inventory");
        assertThat(inventory.conditionExpression()).contains("#src >= :qty");
        assertThat(inventory.updateExpression()).contains("#version = #version + :one");
        assertThat(inventory.expressionAttributeNames()).containsEntry("#src", "reserved")
                .containsEntry("#dst", "pendingConfirmation");
        assertThat(inventory.expressionAttributeValues().get(":qty").n()).isEqualTo("3");
    }

    @Test
    void confirmSale_success_movesPendingToSoldInOneTransaction() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(ok());

        StepVerifier.create(repository.confirmSale(ORDER, "worker", NOW))
                .assertNext(entry -> {
                    assertThat(entry.from()).isEqualTo(TicketStatus.PENDING_CONFIRMATION);
                    assertThat(entry.to()).isEqualTo(TicketStatus.SOLD);
                })
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());
        var items = captor.getValue().transactItems();
        assertThat(items.get(0).update().expressionAttributeValues().get(":expected").s())
                .isEqualTo("PENDING_CONFIRMATION");
        assertThat(items.get(0).update().expressionAttributeValues().get(":target").s()).isEqualTo("SOLD");
        assertThat(items.get(2).update().expressionAttributeNames()).containsEntry("#src", "pendingConfirmation")
                .containsEntry("#dst", "sold");
    }

    @Test
    void confirmSale_blankActor_failsWithoutCallingDynamo() {
        StepVerifier.create(repository.confirmSale(ORDER, " ", NOW)).expectError(IllegalArgumentException.class).verify();
        verify(client, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void confirmSale_statusChanged_mapsToConflictWithActualStatus() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(canceled("ConditionalCheckFailed", SOME_ITEM, "None", null));

        StepVerifier.create(repository.confirmSale(ORDER, "worker", NOW))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(OrderStatusConflictException.class);
                    var conflict = (OrderStatusConflictException) e;
                    assertThat(conflict.expected()).isEqualTo(TicketStatus.PENDING_CONFIRMATION);
                    assertThat(conflict.actual()).isEqualTo(TicketStatus.SOLD);
                })
                .verify();
    }

    @Test
    void markPendingConfirmation_conflictItemWithoutStatus_reportsUnknownActual() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(canceled(
                "ConditionalCheckFailed", Map.of("orderId", AttributeValue.builder().s("o-1").build()), "None", null));

        StepVerifier.create(repository.markPendingConfirmation(ORDER, "worker", NOW))
                .expectErrorSatisfies(e -> assertThat(((OrderStatusConflictException) e).actual()).isNull())
                .verify();
    }

    @Test
    void confirmSale_orderMissing_mapsToNotFound() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(canceled("ConditionalCheckFailed", null, "None", null));

        StepVerifier.create(repository.confirmSale(ORDER, "worker", NOW)).expectError(OrderNotFoundException.class).verify();
    }

    @Test
    void confirmSale_orderConditionWinsOverInventoryCondition() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                canceled("ConditionalCheckFailed", SOME_ITEM, "ConditionalCheckFailed", SOME_ITEM));

        StepVerifier.create(repository.confirmSale(ORDER, "worker", NOW))
                .expectError(OrderStatusConflictException.class).verify();
    }

    @Test
    void markPendingConfirmation_counterTooLow_mapsToInsufficientInventory() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(canceled("None", null, "ConditionalCheckFailed", SOME_ITEM));

        StepVerifier.create(repository.markPendingConfirmation(ORDER, "worker", NOW))
                .expectError(InsufficientInventoryException.class).verify();
    }

    @Test
    void confirmSale_inventoryMissing_mapsToEventNotFound() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(canceled("None", null, "ConditionalCheckFailed", null));

        StepVerifier.create(repository.confirmSale(ORDER, "worker", NOW)).expectError(EventNotFoundException.class).verify();
    }

    @Test
    void confirmSale_otherCancellation_propagatesOriginal() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(canceled(reason("ValidationError", null)));

        StepVerifier.create(repository.confirmSale(ORDER, "worker", NOW))
                .expectError(TransactionCanceledException.class).verify();
    }

    @Test
    void confirmSale_transactionConflictThenSuccess_isRetried() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(canceled("TransactionConflict", null, "None", null), ok());

        StepVerifier.create(repository.confirmSale(ORDER, "worker", NOW)).expectNextCount(1).verifyComplete();
        verify(client, times(2)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void confirmSale_persistentConflict_failsAfterRetryBudget() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(canceled("TransactionConflict", null, "None", null));

        StepVerifier.create(repository.confirmSale(ORDER, "worker", NOW))
                .expectError(TransactionCanceledException.class).verify();
        verify(client, times(4)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void publicConstructor_usesDefaults() {
        assertThat(new DynamoDbOrderFulfillmentRepository(client)).isNotNull();
    }
}
