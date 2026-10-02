package com.ticketflow.infrastructure.persistence;

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
import com.ticketflow.domain.port.OrderRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;
import software.amazon.awssdk.services.dynamodb.model.LimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactionInProgressException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * DynamoDB adapter of {@link OrderRepository}.
 *
 * <p>Status transitions are one {@code TransactWriteItems}: an {@code Update} of the order guarded by
 * {@code #status = :expected} plus a {@code Put} of the audit entry, so the status change and its
 * audit record are atomic and a lost race fails with {@link OrderStatusConflictException} instead of
 * overwriting. The audit sort key is {@code <fixed-width ISO timestamp>#<uuid>}: it sorts
 * chronologically and two entries of the same instant never collide.
 *
 * <p>Timestamps used as keys ({@code reservationExpiresAt} in the expiry GSI, audit sort key) are
 * rendered with a fixed 9-digit fraction so lexicographic order equals chronological order.
 *
 * <p>Only transient infrastructure errors are retried (exponential backoff); business outcomes
 * (failed conditions) never are. GSI reads ({@code findByIdempotencyKey}, {@code findExpiredReservations})
 * are eventually consistent by DynamoDB design.
 */
@Repository
public class DynamoDbOrderRepository implements OrderRepository {

    static final int DEFAULT_MAX_RETRIES = 3;
    static final Duration DEFAULT_MIN_BACKOFF = Duration.ofMillis(50);

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC);
    private static final String SORT_KEY_SEPARATOR = "#";
    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";
    private static final List<String> TRANSIENT_CANCELLATION_CODES =
            List.of("TransactionConflict", "ThrottlingError", "ProvisionedThroughputExceeded");
    private static final List<TicketStatus> EXPIRABLE =
            List.of(TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION);

    private static final String ORDER_ID = "orderId";
    private static final String EVENT_ID = "eventId";
    private static final String QUANTITY = "quantity";
    private static final String STATUS = "status";
    private static final String IDEMPOTENCY_KEY = "idempotencyKey";
    private static final String EXPIRES_AT = "reservationExpiresAt";
    private static final String CREATED_AT = "createdAt";
    private static final String TIMESTAMP_KEY = "timestamp";
    private static final String FROM = "from";
    private static final String TO = "to";
    private static final String ACTOR = "actor";
    private static final String REASON = "reason";

    private final DynamoDbAsyncClient client;
    private final Retry retry;

    @Autowired
    public DynamoDbOrderRepository(DynamoDbAsyncClient client) {
        this(client, DEFAULT_MAX_RETRIES, DEFAULT_MIN_BACKOFF);
    }

    DynamoDbOrderRepository(DynamoDbAsyncClient client, int maxRetries, Duration minBackoff) {
        this.client = client;
        this.retry = Retry.backoff(maxRetries, minBackoff)
                .filter(DynamoDbOrderRepository::isTransient)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    @Override
    public Mono<Order> save(Order order) {
        PutItemRequest request = PutItemRequest.builder()
                .tableName(DynamoDbTables.ORDERS)
                .item(toItem(order))
                .conditionExpression("attribute_not_exists(" + ORDER_ID + ")")
                .build();
        return Mono.fromFuture(() -> client.putItem(request))
                .retryWhen(retry)
                .thenReturn(order)
                .onErrorMap(ConditionalCheckFailedException.class,
                        error -> new OrderAlreadyExistsException(order.id()));
    }

    @Override
    public Mono<Order> findById(OrderId id) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(DynamoDbTables.ORDERS)
                .key(key(id))
                .consistentRead(true)
                .build();
        return Mono.fromFuture(() -> client.getItem(request))
                .retryWhen(retry)
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> fromItem(response.item()));
    }

    @Override
    public Mono<Order> findByIdempotencyKey(IdempotencyKey key) {
        QueryRequest request = QueryRequest.builder()
                .tableName(DynamoDbTables.ORDERS)
                .indexName(DynamoDbTables.ORDERS_BY_IDEMPOTENCY_KEY_INDEX)
                .keyConditionExpression("#key = :key")
                .expressionAttributeNames(Map.of("#key", IDEMPOTENCY_KEY))
                .expressionAttributeValues(Map.of(":key", s(key.value())))
                .build();
        return query(request).next().map(DynamoDbOrderRepository::fromItem);
    }

    @Override
    public Flux<Order> findExpiredReservations(Instant now) {
        return Flux.fromIterable(EXPIRABLE)
                .concatMap(status -> query(QueryRequest.builder()
                        .tableName(DynamoDbTables.ORDERS)
                        .indexName(DynamoDbTables.ORDERS_BY_STATUS_EXPIRY_INDEX)
                        .keyConditionExpression("#status = :status AND #expires < :now")
                        .expressionAttributeNames(Map.of("#status", STATUS, "#expires", EXPIRES_AT))
                        .expressionAttributeValues(Map.of(
                                ":status", s(status.name()), ":now", s(format(now))))
                        .build()))
                .map(DynamoDbOrderRepository::fromItem);
    }

    @Override
    public Mono<OrderAuditEntry> transition(
            OrderId id, TicketStatus expected, TicketStatus target, String actor, Instant at) {
        return Mono.defer(() -> {
            // Validates the state machine, actor and nulls before touching DynamoDB.
            OrderAuditEntry entry = new OrderAuditEntry(id, at, expected, target, actor);
            TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                    .transactItems(updateStatus(id, expected, target), putAudit(entry))
                    .build();
            return Mono.fromFuture(() -> client.transactWriteItems(request))
                    .retryWhen(retry)
                    .thenReturn(entry)
                    .onErrorMap(TransactionCanceledException.class,
                            error -> translate(error, id, expected));
        });
    }

    @Override
    public Mono<OrderAuditEntry> saveAuditEntry(OrderAuditEntry entry) {
        PutItemRequest request = PutItemRequest.builder()
                .tableName(DynamoDbTables.ORDER_AUDIT)
                .item(auditItem(entry))
                .build();
        return Mono.fromFuture(() -> client.putItem(request)).retryWhen(retry).thenReturn(entry);
    }

    @Override
    public Flux<OrderAuditEntry> findAuditTrail(OrderId id) {
        QueryRequest request = QueryRequest.builder()
                .tableName(DynamoDbTables.ORDER_AUDIT)
                .keyConditionExpression("#id = :id")
                .expressionAttributeNames(Map.of("#id", ORDER_ID))
                .expressionAttributeValues(Map.of(":id", s(id.value())))
                .scanIndexForward(true)
                .consistentRead(true)
                .build();
        return query(request).map(DynamoDbOrderRepository::fromAuditItem);
    }

    /** Runs the query following {@code LastEvaluatedKey} pages. */
    private Flux<Map<String, AttributeValue>> query(QueryRequest request) {
        return Mono.fromFuture(() -> client.query(request))
                .retryWhen(retry)
                .expand(page -> page.hasLastEvaluatedKey()
                        ? Mono.fromFuture(() -> client.query(
                                        request.toBuilder().exclusiveStartKey(page.lastEvaluatedKey()).build()))
                                .retryWhen(retry)
                        : Mono.empty())
                .flatMapIterable(QueryResponse::items);
    }

    private static TransactWriteItem updateStatus(OrderId id, TicketStatus expected, TicketStatus target) {
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(DynamoDbTables.ORDERS)
                        .key(key(id))
                        .updateExpression("SET #status = :target")
                        .conditionExpression("attribute_exists(#id) AND #status = :expected")
                        .expressionAttributeNames(Map.of("#id", ORDER_ID, "#status", STATUS))
                        .expressionAttributeValues(Map.of(
                                ":expected", s(expected.name()), ":target", s(target.name())))
                        .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                        .build())
                .build();
    }

    private static TransactWriteItem putAudit(OrderAuditEntry entry) {
        return TransactWriteItem.builder()
                .put(Put.builder().tableName(DynamoDbTables.ORDER_AUDIT).item(auditItem(entry)).build())
                .build();
    }

    /** The order update is the first transaction item; its reason tells missing from stale. */
    private static Throwable translate(TransactionCanceledException error, OrderId id, TicketStatus expected) {
        List<CancellationReason> reasons = error.cancellationReasons();
        if (reasons.isEmpty() || !CONDITIONAL_CHECK_FAILED.equals(reasons.get(0).code())) {
            return error;
        }
        CancellationReason reason = reasons.get(0);
        if (!reason.hasItem() || reason.item().isEmpty()) {
            return new OrderNotFoundException(id);
        }
        AttributeValue status = reason.item().get(STATUS);
        return new OrderStatusConflictException(id, expected,
                status == null ? null : TicketStatus.valueOf(status.s()));
    }

    /** Throttling, transaction conflicts and 5xx-style service errors; never business or client errors. */
    static boolean isTransient(Throwable error) {
        return switch (error) {
            case TransactionCanceledException canceled -> isTransientCancellation(canceled);
            case ProvisionedThroughputExceededException ignored -> true;
            case RequestLimitExceededException ignored -> true;
            case LimitExceededException ignored -> true;
            case InternalServerErrorException ignored -> true;
            case TransactionInProgressException ignored -> true;
            case AwsServiceException aws -> aws.isThrottlingException() || aws.statusCode() == 503;
            default -> false;
        };
    }

    private static boolean isTransientCancellation(TransactionCanceledException error) {
        List<CancellationReason> reasons = error.cancellationReasons();
        return reasons.stream().noneMatch(r -> CONDITIONAL_CHECK_FAILED.equals(r.code()))
                && reasons.stream().anyMatch(r -> TRANSIENT_CANCELLATION_CODES.contains(r.code()));
    }

    static String format(Instant instant) {
        return TIMESTAMP.format(instant);
    }

    static Map<String, AttributeValue> key(OrderId id) {
        return Map.of(ORDER_ID, s(id.value()));
    }

    static Map<String, AttributeValue> toItem(Order order) {
        return Map.of(
                ORDER_ID, s(order.id().value()),
                EVENT_ID, s(order.eventId().value()),
                QUANTITY, AttributeValue.builder().n(Integer.toString(order.quantity().value())).build(),
                STATUS, s(order.status().name()),
                IDEMPOTENCY_KEY, s(order.idempotencyKey().value()),
                EXPIRES_AT, s(format(order.reservationExpiresAt())),
                CREATED_AT, s(format(order.createdAt())));
    }

    private static Order fromItem(Map<String, AttributeValue> item) {
        return new Order(
                new OrderId(item.get(ORDER_ID).s()),
                new EventId(item.get(EVENT_ID).s()),
                new Quantity(Integer.parseInt(item.get(QUANTITY).n())),
                TicketStatus.valueOf(item.get(STATUS).s()),
                new IdempotencyKey(item.get(IDEMPOTENCY_KEY).s()),
                Instant.parse(item.get(EXPIRES_AT).s()),
                Instant.parse(item.get(CREATED_AT).s()));
    }

    static Map<String, AttributeValue> auditItem(OrderAuditEntry entry) {
        Map<String, AttributeValue> item = new HashMap<>(Map.of(
                ORDER_ID, s(entry.orderId().value()),
                TIMESTAMP_KEY, s(format(entry.timestamp()) + SORT_KEY_SEPARATOR + UUID.randomUUID()),
                FROM, s(entry.from().name()),
                TO, s(entry.to().name()),
                ACTOR, s(entry.actor())));
        if (entry.reason() != null) {
            item.put(REASON, s(entry.reason()));
        }
        return item;
    }

    private static OrderAuditEntry fromAuditItem(Map<String, AttributeValue> item) {
        String sortKey = item.get(TIMESTAMP_KEY).s();
        return new OrderAuditEntry(
                new OrderId(item.get(ORDER_ID).s()),
                Instant.parse(sortKey.substring(0, sortKey.indexOf(SORT_KEY_SEPARATOR))),
                TicketStatus.valueOf(item.get(FROM).s()),
                TicketStatus.valueOf(item.get(TO).s()),
                item.get(ACTOR).s(),
                item.containsKey(REASON) ? item.get(REASON).s() : null);
    }

    static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
