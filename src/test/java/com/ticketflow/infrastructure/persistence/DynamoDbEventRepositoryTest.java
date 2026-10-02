package com.ticketflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.exception.EventAlreadyExistsException;
import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

class DynamoDbEventRepositoryTest {

    private static final Event EVENT = new Event(new EventId("e-1"), "Concert",
            Instant.parse("2030-01-01T20:00:00Z"), "Arena", 50);

    private DynamoDbAsyncClient client;
    private DynamoDbEventRepository repository;

    @BeforeEach
    void setUp() {
        client = mock(DynamoDbAsyncClient.class);
        repository = new DynamoDbEventRepository(client);
    }

    private static Map<String, AttributeValue> item(String id) {
        return Map.of(
                "eventId", AttributeValue.builder().s(id).build(),
                "name", AttributeValue.builder().s("Concert").build(),
                "startsAt", AttributeValue.builder().s("2030-01-01T20:00:00Z").build(),
                "venue", AttributeValue.builder().s("Arena").build(),
                "capacity", AttributeValue.builder().n("50").build());
    }

    @Test
    void save_newEvent_writesEventAndInitialInventoryInOneConditionalTransaction() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        StepVerifier.create(repository.save(EVENT)).expectNext(EVENT).verifyComplete();

        var captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client, times(1)).transactWriteItems(captor.capture());
        var items = captor.getValue().transactItems();
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(i ->
                assertThat(i.put().conditionExpression()).isEqualTo("attribute_not_exists(eventId)"));
        var events = items.get(0).put();
        assertThat(events.tableName()).isEqualTo(DynamoDbTables.EVENTS);
        assertThat(events.item().get("startsAt").s()).isEqualTo("2030-01-01T20:00:00Z");
        var inventory = items.get(1).put();
        assertThat(inventory.tableName()).isEqualTo(DynamoDbTables.INVENTORY);
        assertThat(inventory.item().get("available").n()).isEqualTo("50");
        assertThat(inventory.item().get("capacity").n()).isEqualTo("50");
        assertThat(inventory.item().get("version").n()).isEqualTo("0");
        assertThat(inventory.item().get("sold").n()).isEqualTo("0");
    }

    @Test
    void save_duplicateId_failsWithEventAlreadyExists() {
        var canceled = TransactionCanceledException.builder()
                .cancellationReasons(CancellationReason.builder().code("ConditionalCheckFailed").build(),
                        CancellationReason.builder().code("None").build())
                .build();
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(canceled));

        StepVerifier.create(repository.save(EVENT)).expectError(EventAlreadyExistsException.class).verify();
    }

    @Test
    void save_canceledForOtherReason_propagatesOriginalError() {
        var canceled = TransactionCanceledException.builder()
                .cancellationReasons(CancellationReason.builder().code("ThrottlingError").build()).build();
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(canceled));

        StepVerifier.create(repository.save(EVENT)).expectError(TransactionCanceledException.class).verify();
    }

    @Test
    void save_otherFailure_propagatesOriginalError() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));

        StepVerifier.create(repository.save(EVENT)).expectError(IllegalStateException.class).verify();
    }

    @Test
    void findById_existingItem_mapsToEvent() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetItemResponse.builder().item(item("e-1")).build()));

        StepVerifier.create(repository.findById(new EventId("e-1"))).expectNext(EVENT).verifyComplete();
    }

    @Test
    void findById_unknownId_completesEmpty() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        StepVerifier.create(repository.findById(new EventId("nope"))).verifyComplete();
    }

    @Test
    void findAll_multiplePages_followsLastEvaluatedKey() {
        var key = Map.of("eventId", AttributeValue.builder().s("e-1").build());
        when(client.scan(any(ScanRequest.class))).thenReturn(
                CompletableFuture.completedFuture(ScanResponse.builder()
                        .items(List.of(item("e-1"))).lastEvaluatedKey(key).build()),
                CompletableFuture.completedFuture(ScanResponse.builder().items(List.of(item("e-2"))).build()));

        StepVerifier.create(repository.findAll())
                .expectNextMatches(e -> e.id().value().equals("e-1"))
                .expectNextMatches(e -> e.id().value().equals("e-2"))
                .verifyComplete();
        var captor = ArgumentCaptor.forClass(ScanRequest.class);
        verify(client, times(2)).scan(captor.capture());
        assertThat(captor.getAllValues().get(1).exclusiveStartKey()).isEqualTo(key);
    }

    @Test
    void findAll_emptyTable_completesEmpty() {
        when(client.scan(any(ScanRequest.class))).thenReturn(
                CompletableFuture.completedFuture(ScanResponse.builder().build()));

        StepVerifier.create(repository.findAll()).verifyComplete();
    }
}
