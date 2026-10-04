package com.ticketflow.infrastructure.persistence;

import com.ticketflow.testsupport.TestTimeouts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.exception.InvalidStateTransitionException;
import com.ticketflow.domain.exception.OrderAlreadyExistsException;
import com.ticketflow.domain.exception.OrderNotFoundException;
import com.ticketflow.domain.exception.OrderStatusConflictException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderAuditEntry;
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
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactionInProgressException;

class DynamoDbOrderRepositoryTest {

    private static final OrderId OID = new OrderId("o-1");
    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final Order ORDER = new Order(OID, new EventId("e-1"), new Quantity(3),
            TicketStatus.RESERVED, new IdempotencyKey("k-1"), NOW.plusSeconds(600), NOW);

    private DynamoDbAsyncClient client;
    private DynamoDbOrderRepository repository;

    @BeforeEach
    void setUp() {
        client = mock(DynamoDbAsyncClient.class);
        repository = new DynamoDbOrderRepository(client, 3, Duration.ofMillis(1));
    }

    private static AttributeValue s(String v) {
        return AttributeValue.builder().s(v).build();
    }

    private static Map<String, AttributeValue> item(Order o) {
        return Map.of(
                "orderId", s(o.id().value()),
                "eventId", s(o.eventId().value()),
                "quantity", AttributeValue.builder().n(Integer.toString(o.quantity().value())).build(),
                "status", s(o.status().name()),
                "idempotencyKey", s(o.idempotencyKey().value()),
                "reservationExpiresAt", s(DynamoDbOrderRepository.format(o.reservationExpiresAt())),
                "createdAt", s(DynamoDbOrderRepository.format(o.createdAt())));
    }

    private static CompletableFuture<QueryResponse> page(Map<String, AttributeValue> lastKey,
                                                         Map<String, AttributeValue>... items) {
        var builder = QueryResponse.builder().items(items);
        if (lastKey != null) {
            builder.lastEvaluatedKey(lastKey);
        }
        return CompletableFuture.completedFuture(builder.build());
    }

    private static TransactionCanceledException canceled(String firstCode, Map<String, AttributeValue> oldItem) {
        var first = CancellationReason.builder().code(firstCode);
        if (oldItem != null) {
            first.item(oldItem);
        }
        return (TransactionCanceledException) TransactionCanceledException.builder()
                .cancellationReasons(first.build(), CancellationReason.builder().code("None").build()).build();
    }

    private static DynamoDbException awsError(int status, String code) {
        return (DynamoDbException) DynamoDbException.builder().statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build()).build();
    }

    private static CompletableFuture<TransactWriteItemsResponse> txOk() {
        return CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build());
    }

    @Test
    void save_newOrder_putsItemWithAttributeNotExistsCondition() {
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        StepVerifier.create(repository.save(ORDER)).expectNext(ORDER).verifyComplete();

        var captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(captor.capture());
        assertThat(captor.getValue().tableName()).isEqualTo(DynamoDbTables.ORDERS);
        assertThat(captor.getValue().conditionExpression()).isEqualTo("attribute_not_exists(orderId)");
        assertThat(captor.getValue().item().get("reservationExpiresAt").s())
                .isEqualTo("2030-01-01T10:10:00.000000000Z");
        assertThat(captor.getValue().item().get("status").s()).isEqualTo("RESERVED");
    }

    @Test
    void save_existingId_failsWithOrderAlreadyExists() {
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(ConditionalCheckFailedException.builder().build()));

        StepVerifier.create(repository.save(ORDER)).expectError(OrderAlreadyExistsException.class).verify();
    }

    @Test
    void findById_existing_mapsAllFields() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetItemResponse.builder().item(item(ORDER)).build()));

        StepVerifier.create(repository.findById(OID)).expectNext(ORDER).verifyComplete();
    }

    @Test
    void findById_usesConsistentRead() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetItemResponse.builder().item(item(ORDER)).build()));

        StepVerifier.create(repository.findById(OID)).expectNext(ORDER).verifyComplete();

        var captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(captor.capture());
        assertThat(captor.getValue().consistentRead()).isTrue();
    }

    @Test
    void findById_missing_completesEmpty() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        StepVerifier.create(repository.findById(OID)).verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void findByIdempotencyKey_existing_queriesGsiAndReturnsOrder() {
        when(client.query(any(QueryRequest.class))).thenReturn(page(null, item(ORDER)));

        StepVerifier.create(repository.findByIdempotencyKey(new IdempotencyKey("k-1")))
                .expectNext(ORDER).verifyComplete();

        var captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client).query(captor.capture());
        assertThat(captor.getValue().indexName()).isEqualTo(DynamoDbTables.ORDERS_BY_IDEMPOTENCY_KEY_INDEX);
        assertThat(captor.getValue().expressionAttributeValues().get(":key").s()).isEqualTo("k-1");
    }

    @Test
    void findByIdempotencyKey_none_completesEmpty() {
        when(client.query(any(QueryRequest.class))).thenReturn(page(null));

        StepVerifier.create(repository.findByIdempotencyKey(new IdempotencyKey("nope"))).verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void findExpiredReservations_queriesOnlyExpirableStatusesBeforeNowAndPaginates() {
        var reserved = ORDER;
        var pending = new Order(new OrderId("o-2"), ORDER.eventId(), ORDER.quantity(),
                TicketStatus.PENDING_CONFIRMATION, new IdempotencyKey("k-2"), ORDER.reservationExpiresAt(), NOW);
        var lastKey = Map.of("orderId", s("o-1"));
        when(client.query(any(QueryRequest.class))).thenReturn(
                page(lastKey, item(reserved)), page(null), page(null, item(pending)));

        StepVerifier.create(repository.findExpiredReservations(NOW.plusSeconds(3600)))
                .expectNext(reserved, pending).verifyComplete();

        var captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, times(3)).query(captor.capture());
        var requests = captor.getAllValues();
        assertThat(requests).allSatisfy(r -> {
            assertThat(r.indexName()).isEqualTo(DynamoDbTables.ORDERS_BY_STATUS_EXPIRY_INDEX);
            assertThat(r.keyConditionExpression()).isEqualTo("#status = :status AND #expires <= :now");
            assertThat(r.expressionAttributeValues().get(":now").s()).isEqualTo("2030-01-01T11:00:00.000000000Z");
        });
        assertThat(requests.get(0).expressionAttributeValues().get(":status").s()).isEqualTo("RESERVED");
        assertThat(requests.get(1).exclusiveStartKey()).isEqualTo(lastKey);
        assertThat(requests.get(2).expressionAttributeValues().get(":status").s())
                .isEqualTo("PENDING_CONFIRMATION");
    }

    @Test
    void transition_valid_writesConditionalUpdateAndAuditInOneTransaction() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(txOk());

        StepVerifier.create(repository.transition(
                        OID, TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION, "consumer", NOW))
                .expectNext(new OrderAuditEntry(OID, NOW, TicketStatus.RESERVED,
                        TicketStatus.PENDING_CONFIRMATION, "consumer"))
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());
        var items = captor.getValue().transactItems();
        assertThat(items).hasSize(2);
        var update = items.get(0).update();
        assertThat(update.tableName()).isEqualTo(DynamoDbTables.ORDERS);
        assertThat(update.conditionExpression()).isEqualTo("attribute_exists(#id) AND #status = :expected");
        assertThat(update.expressionAttributeValues().get(":expected").s()).isEqualTo("RESERVED");
        assertThat(update.expressionAttributeValues().get(":target").s()).isEqualTo("PENDING_CONFIRMATION");
        var audit = items.get(1).put();
        assertThat(audit.tableName()).isEqualTo(DynamoDbTables.ORDER_AUDIT);
        assertThat(audit.item().get("timestamp").s())
                .startsWith("2030-01-01T10:00:00.000000000Z#").hasSizeGreaterThan(30);
        assertThat(audit.item().get("from").s()).isEqualTo("RESERVED");
        assertThat(audit.item().get("to").s()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(audit.item().get("actor").s()).isEqualTo("consumer");
    }

    @Test
    void transition_invalidTransition_failsWithoutCallingDynamo() {
        StepVerifier.create(repository.transition(OID, TicketStatus.SOLD, TicketStatus.AVAILABLE, "x", NOW))
                .expectError(InvalidStateTransitionException.class).verify();

        verify(client, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void transition_staleStatus_failsWithConflictCarryingActualStatus() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                CompletableFuture.failedFuture(canceled("ConditionalCheckFailed",
                        Map.of("status", s("PENDING_CONFIRMATION")))));

        StepVerifier.create(repository.transition(
                        OID, TicketStatus.RESERVED, TicketStatus.AVAILABLE, "scheduler", NOW))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(OrderStatusConflictException.class);
                    var conflict = (OrderStatusConflictException) e;
                    assertThat(conflict.expected()).isEqualTo(TicketStatus.RESERVED);
                    assertThat(conflict.actual()).isEqualTo(TicketStatus.PENDING_CONFIRMATION);
                    assertThat(conflict.orderId()).isEqualTo(OID);
                }).verify();
        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void transition_conflictWithoutStatusInReason_hasUnknownActual() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                CompletableFuture.failedFuture(canceled("ConditionalCheckFailed", Map.of("other", s("x")))));

        StepVerifier.create(repository.transition(
                        OID, TicketStatus.RESERVED, TicketStatus.AVAILABLE, "scheduler", NOW))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(OrderStatusConflictException.class);
                    assertThat(((OrderStatusConflictException) e).actual()).isNull();
                    assertThat(e).hasMessageContaining("unknown");
                }).verify();
    }

    @Test
    void transition_missingOrder_failsWithOrderNotFound() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                CompletableFuture.failedFuture(canceled("ConditionalCheckFailed", null)));

        StepVerifier.create(repository.transition(
                        OID, TicketStatus.RESERVED, TicketStatus.AVAILABLE, "scheduler", NOW))
                .expectError(OrderNotFoundException.class).verify();
    }

    @Test
    void transition_cancellationWithoutOrderConditionFailure_propagatesOriginalError() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                CompletableFuture.failedFuture(canceled("ValidationError", null)));

        StepVerifier.create(repository.transition(
                        OID, TicketStatus.RESERVED, TicketStatus.AVAILABLE, "scheduler", NOW))
                .expectError(TransactionCanceledException.class).verify();
        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void transition_cancellationWithoutReasons_propagatesOriginalError() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                CompletableFuture.failedFuture(TransactionCanceledException.builder().build()));

        StepVerifier.create(repository.transition(
                        OID, TicketStatus.RESERVED, TicketStatus.AVAILABLE, "scheduler", NOW))
                .expectError(TransactionCanceledException.class).verify();
    }

    @Test
    void transition_transactionConflictThenSuccess_isRetried() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                CompletableFuture.failedFuture(canceled("TransactionConflict", null)), txOk());

        StepVerifier.create(repository.transition(
                        OID, TicketStatus.RESERVED, TicketStatus.AVAILABLE, "scheduler", NOW))
                .expectNextCount(1).verifyComplete();
        verify(client, times(2)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void transition_persistentThrottling_failsWithOriginalErrorAfterMaxRetries() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                CompletableFuture.failedFuture(ProvisionedThroughputExceededException.builder().build()));

        StepVerifier.create(repository.transition(
                        OID, TicketStatus.RESERVED, TicketStatus.AVAILABLE, "scheduler", NOW))
                .expectError(ProvisionedThroughputExceededException.class).verify(TestTimeouts.WAIT);
        verify(client, times(4)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void transition_nonTransientError_isNotRetried() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                CompletableFuture.failedFuture(ResourceNotFoundException.builder().build()));

        StepVerifier.create(repository.transition(
                        OID, TicketStatus.RESERVED, TicketStatus.AVAILABLE, "scheduler", NOW))
                .expectError(ResourceNotFoundException.class).verify();
        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void saveAuditEntry_entry_putsItemWithUniqueSortKey() {
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));
        var entry = new OrderAuditEntry(OID, NOW, TicketStatus.AVAILABLE, TicketStatus.RESERVED, "api");

        StepVerifier.create(repository.saveAuditEntry(entry)).expectNext(entry).verifyComplete();
        StepVerifier.create(repository.saveAuditEntry(entry)).expectNext(entry).verifyComplete();

        var captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client, times(2)).putItem(captor.capture());
        var first = captor.getAllValues().get(0).item().get("timestamp").s();
        var second = captor.getAllValues().get(1).item().get("timestamp").s();
        assertThat(first).isNotEqualTo(second);
        assertThat(captor.getAllValues().get(0).tableName()).isEqualTo(DynamoDbTables.ORDER_AUDIT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findAuditTrail_entries_parsesTimestampFromSortKeyInOrder() {
        Map<String, AttributeValue> row = Map.of(
                "orderId", s("o-1"),
                "timestamp", s("2030-01-01T10:00:00.000000000Z#abc"),
                "from", s("AVAILABLE"), "to", s("RESERVED"), "actor", s("api"));
        when(client.query(any(QueryRequest.class))).thenReturn(page(null, row));

        StepVerifier.create(repository.findAuditTrail(OID))
                .expectNext(new OrderAuditEntry(OID, NOW, TicketStatus.AVAILABLE, TicketStatus.RESERVED, "api"))
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client).query(captor.capture());
        assertThat(captor.getValue().scanIndexForward()).isTrue();
        assertThat(captor.getValue().consistentRead()).isTrue();
    }

    @Test
    void format_instants_haveFixedWidthSoLexicographicOrderIsChronological() {
        var whole = DynamoDbOrderRepository.format(Instant.parse("2030-01-01T10:00:00Z"));
        var fraction = DynamoDbOrderRepository.format(Instant.parse("2030-01-01T10:00:00.5Z"));
        assertThat(whole).hasSameSizeAs(fraction);
        assertThat(whole).isLessThan(fraction);
    }

    @Test
    void isTransient_variousErrors_onlyInfrastructureErrorsAndTransactionConflicts() {
        assertThat(DynamoDbOrderRepository.isTransient(InternalServerErrorException.builder().build())).isTrue();
        assertThat(DynamoDbOrderRepository.isTransient(TransactionInProgressException.builder().build())).isTrue();
        assertThat(DynamoDbOrderRepository.isTransient(
                software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException.builder().build()))
                .isTrue();
        assertThat(DynamoDbOrderRepository.isTransient(
                software.amazon.awssdk.services.dynamodb.model.LimitExceededException.builder().build())).isTrue();
        assertThat(DynamoDbOrderRepository.isTransient(awsError(400, "ThrottlingException"))).isTrue();
        assertThat(DynamoDbOrderRepository.isTransient(awsError(503, "ServiceUnavailable"))).isTrue();
        assertThat(DynamoDbOrderRepository.isTransient(canceled("ThrottlingError", null))).isTrue();
        assertThat(DynamoDbOrderRepository.isTransient(canceled("ConditionalCheckFailed", null))).isFalse();
        assertThat(DynamoDbOrderRepository.isTransient(awsError(400, "ValidationException"))).isFalse();
        assertThat(DynamoDbOrderRepository.isTransient(new IllegalStateException())).isFalse();
    }

    @Test
    void defaultConstructor_usesDefaultRetryPolicy() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetItemResponse.builder().item(item(ORDER)).build()));

        StepVerifier.create(new DynamoDbOrderRepository(client).findById(OID)).expectNext(ORDER).verifyComplete();
    }
}
