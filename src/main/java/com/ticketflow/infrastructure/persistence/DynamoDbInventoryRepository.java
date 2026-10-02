package com.ticketflow.infrastructure.persistence;

import com.ticketflow.domain.exception.EventAlreadyExistsException;
import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Inventory;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.port.InventoryRepository;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;
import software.amazon.awssdk.services.dynamodb.model.LimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * DynamoDB adapter of {@link InventoryRepository} (anti-oversell core).
 *
 * <p>Every mutation is exactly one {@code UpdateItem} that decrements the source counter,
 * increments the target counter and {@code version}, guarded by a {@code ConditionExpression}
 * requiring the item to exist and the source counter to hold at least the requested quantity.
 * DynamoDB evaluates condition and update atomically, so there is no read-modify-write and the
 * counters can never go negative nor oversell, whatever the concurrency.
 *
 * <p>Only transient infrastructure errors (throttling, provisioned throughput, internal server
 * errors) are retried, with exponential backoff. A failed condition is a business outcome
 * ({@link InsufficientInventoryException}) and is never retried.
 */
@Repository
public class DynamoDbInventoryRepository implements InventoryRepository {

    static final int DEFAULT_MAX_RETRIES = 3;
    static final Duration DEFAULT_MIN_BACKOFF = Duration.ofMillis(50);

    private static final String KEY = "eventId";
    private static final String AVAILABLE = "available";
    private static final String RESERVED = "reserved";
    private static final String PENDING_CONFIRMATION = "pendingConfirmation";
    private static final String SOLD = "sold";
    private static final String COMPLIMENTARY = "complimentary";
    private static final String CAPACITY = "capacity";
    private static final String VERSION = "version";

    private static final String UPDATE_EXPRESSION =
            "SET #src = #src - :qty, #dst = #dst + :qty, #version = #version + :one";
    private static final String CONDITION_EXPRESSION = "attribute_exists(#key) AND #src >= :qty";

    private final DynamoDbAsyncClient client;
    private final Retry retry;

    @Autowired
    public DynamoDbInventoryRepository(DynamoDbAsyncClient client) {
        this(client, DEFAULT_MAX_RETRIES, DEFAULT_MIN_BACKOFF);
    }

    DynamoDbInventoryRepository(DynamoDbAsyncClient client, int maxRetries, Duration minBackoff) {
        this.client = client;
        this.retry = Retry.backoff(maxRetries, minBackoff)
                .filter(DynamoDbInventoryRepository::isTransient)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    @Override
    public Mono<Inventory> create(Inventory inventory) {
        PutItemRequest request = PutItemRequest.builder()
                .tableName(DynamoDbTables.INVENTORY)
                .item(toItem(inventory))
                .conditionExpression("attribute_not_exists(" + KEY + ")")
                .build();
        return Mono.fromFuture(() -> client.putItem(request))
                .retryWhen(retry)
                .thenReturn(inventory)
                .onErrorMap(ConditionalCheckFailedException.class,
                        error -> new EventAlreadyExistsException(inventory.eventId()));
    }

    @Override
    public Mono<Inventory> findByEventId(EventId eventId) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(DynamoDbTables.INVENTORY)
                .key(key(eventId))
                .consistentRead(true)
                .build();
        return Mono.fromFuture(() -> client.getItem(request))
                .retryWhen(retry)
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> fromItem(response.item()));
    }

    @Override
    public Mono<Inventory> reserve(EventId eventId, Quantity quantity) {
        return move(eventId, quantity, AVAILABLE, RESERVED);
    }

    @Override
    public Mono<Inventory> confirmSale(EventId eventId, Quantity quantity) {
        return move(eventId, quantity, RESERVED, SOLD);
    }

    @Override
    public Mono<Inventory> release(EventId eventId, Quantity quantity) {
        return move(eventId, quantity, RESERVED, AVAILABLE);
    }

    @Override
    public Mono<Inventory> issueComplimentary(EventId eventId, Quantity quantity) {
        return move(eventId, quantity, AVAILABLE, COMPLIMENTARY);
    }

    /** One atomic conditional UpdateItem moving {@code quantity} from counter {@code source} to {@code target}. */
    private Mono<Inventory> move(EventId eventId, Quantity quantity, String source, String target) {
        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(DynamoDbTables.INVENTORY)
                .key(key(eventId))
                .updateExpression(UPDATE_EXPRESSION)
                .conditionExpression(CONDITION_EXPRESSION)
                .expressionAttributeNames(Map.of(
                        "#key", KEY, "#src", source, "#dst", target, "#version", VERSION))
                .expressionAttributeValues(Map.of(":qty", n(quantity.value()), ":one", n(1)))
                .returnValues(ReturnValue.ALL_NEW)
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                .build();
        return Mono.fromFuture(() -> client.updateItem(request))
                .retryWhen(retry)
                .map(response -> fromItem(response.attributes()))
                .onErrorMap(ConditionalCheckFailedException.class,
                        error -> conditionFailure(eventId, quantity, error));
    }

    private static RuntimeException conditionFailure(
            EventId eventId, Quantity quantity, ConditionalCheckFailedException error) {
        return error.hasItem() && !error.item().isEmpty()
                ? new InsufficientInventoryException(eventId, quantity)
                : new EventNotFoundException(eventId);
    }

    /** Throttling, provisioned throughput and 5xx-style service errors; never business or client errors. */
    static boolean isTransient(Throwable error) {
        return error instanceof ProvisionedThroughputExceededException
                || error instanceof RequestLimitExceededException
                || error instanceof LimitExceededException
                || error instanceof InternalServerErrorException
                || (error instanceof AwsServiceException aws
                        && (aws.isThrottlingException() || aws.statusCode() == 503));
    }

    private static Map<String, AttributeValue> key(EventId eventId) {
        return Map.of(KEY, AttributeValue.builder().s(eventId.value()).build());
    }

    private static Map<String, AttributeValue> toItem(Inventory inventory) {
        return Map.of(
                KEY, AttributeValue.builder().s(inventory.eventId().value()).build(),
                AVAILABLE, n(inventory.available()),
                RESERVED, n(inventory.reserved()),
                PENDING_CONFIRMATION, n(inventory.pendingConfirmation()),
                SOLD, n(inventory.sold()),
                COMPLIMENTARY, n(inventory.complimentary()),
                CAPACITY, n(inventory.capacity()),
                VERSION, n(inventory.version()));
    }

    private static Inventory fromItem(Map<String, AttributeValue> item) {
        return new Inventory(
                new EventId(item.get(KEY).s()),
                intOf(item, AVAILABLE),
                intOf(item, RESERVED),
                intOf(item, PENDING_CONFIRMATION),
                intOf(item, SOLD),
                intOf(item, COMPLIMENTARY),
                intOf(item, CAPACITY),
                Long.parseLong(item.get(VERSION).n()));
    }

    private static int intOf(Map<String, AttributeValue> item, String name) {
        return Integer.parseInt(item.get(name).n());
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
