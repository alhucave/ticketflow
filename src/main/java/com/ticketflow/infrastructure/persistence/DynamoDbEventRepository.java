package com.ticketflow.infrastructure.persistence;

import com.ticketflow.domain.exception.EventAlreadyExistsException;
import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Inventory;
import com.ticketflow.domain.port.EventRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * DynamoDB adapter of {@link EventRepository}. An event and its initial inventory are written in
 * one transaction, each guarded by {@code attribute_not_exists} so duplicates are rejected.
 */
@Repository
public class DynamoDbEventRepository implements EventRepository {

    private static final String NOT_EXISTS = "attribute_not_exists(eventId)";
    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

    private final DynamoDbAsyncClient client;

    public DynamoDbEventRepository(DynamoDbAsyncClient client) {
        this.client = client;
    }

    @Override
    public Mono<Event> save(Event event) {
        Inventory inventory = Inventory.initial(event.id(), event.capacity());
        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(
                        put(DynamoDbTables.EVENTS, eventItem(event)),
                        put(DynamoDbTables.INVENTORY, inventoryItem(inventory)))
                .build();
        return Mono.fromFuture(() -> client.transactWriteItems(request))
                .thenReturn(event)
                .onErrorMap(DynamoDbEventRepository::isDuplicate,
                        error -> new EventAlreadyExistsException(event.id()));
    }

    @Override
    public Mono<Event> findById(EventId id) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(DynamoDbTables.EVENTS)
                .key(Map.of("eventId", s(id.value())))
                .consistentRead(true)
                .build();
        return Mono.fromFuture(() -> client.getItem(request))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> toEvent(response.item()));
    }

    @Override
    public Flux<Event> findAll() {
        return Mono.fromFuture(() -> client.scan(scan(null)))
                .expand(page -> page.hasLastEvaluatedKey()
                        ? Mono.fromFuture(() -> client.scan(scan(page.lastEvaluatedKey())))
                        : Mono.empty())
                .flatMapIterable(ScanResponse::items)
                .map(DynamoDbEventRepository::toEvent);
    }

    private static ScanRequest scan(Map<String, AttributeValue> startKey) {
        ScanRequest.Builder builder = ScanRequest.builder().tableName(DynamoDbTables.EVENTS);
        if (startKey != null) {
            builder.exclusiveStartKey(startKey);
        }
        return builder.build();
    }

    private static boolean isDuplicate(Throwable error) {
        return error instanceof TransactionCanceledException canceled
                && canceled.cancellationReasons().stream()
                        .anyMatch(reason -> CONDITIONAL_CHECK_FAILED.equals(reason.code()));
    }

    private static TransactWriteItem put(String table, Map<String, AttributeValue> item) {
        return TransactWriteItem.builder()
                .put(Put.builder().tableName(table).item(item).conditionExpression(NOT_EXISTS).build())
                .build();
    }

    private static Map<String, AttributeValue> eventItem(Event event) {
        return Map.of(
                "eventId", s(event.id().value()),
                "name", s(event.name()),
                "startsAt", s(event.startsAt().toString()),
                "venue", s(event.venue()),
                "capacity", n(event.capacity()));
    }

    private static Map<String, AttributeValue> inventoryItem(Inventory inventory) {
        return Map.of(
                "eventId", s(inventory.eventId().value()),
                "available", n(inventory.available()),
                "reserved", n(inventory.reserved()),
                "pendingConfirmation", n(inventory.pendingConfirmation()),
                "sold", n(inventory.sold()),
                "complimentary", n(inventory.complimentary()),
                "capacity", n(inventory.capacity()),
                "version", n(inventory.version()));
    }

    private static Event toEvent(Map<String, AttributeValue> item) {
        return new Event(
                new EventId(item.get("eventId").s()),
                item.get("name").s(),
                Instant.parse(item.get("startsAt").s()),
                item.get("venue").s(),
                Integer.parseInt(item.get("capacity").n()));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
