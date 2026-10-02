package com.ticketflow.infrastructure.persistence;

import java.util.List;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Schema of the DynamoDB tables (see docs/architecture.md, "Modelo de datos").
 * Timestamps are ISO-8601 strings, which sort chronologically as range keys.
 */
public final class DynamoDbTables {

    public static final String EVENTS = "events";
    public static final String INVENTORY = "inventory";
    public static final String ORDERS = "orders";
    public static final String ORDER_AUDIT = "order_audit";

    public static final String ORDERS_BY_IDEMPOTENCY_KEY_INDEX = "idempotencyKey-index";
    public static final String ORDERS_BY_STATUS_EXPIRY_INDEX = "status-reservationExpiresAt-index";

    private DynamoDbTables() {
    }

    public static List<CreateTableRequest> definitions() {
        return List.of(events(), inventory(), orders(), orderAudit());
    }

    private static CreateTableRequest events() {
        return table(EVENTS, List.of(string("eventId")), List.of(hash("eventId")), List.of());
    }

    private static CreateTableRequest inventory() {
        return table(INVENTORY, List.of(string("eventId")), List.of(hash("eventId")), List.of());
    }

    private static CreateTableRequest orders() {
        return table(ORDERS,
                List.of(string("orderId"), string("idempotencyKey"), string("status"),
                        string("reservationExpiresAt")),
                List.of(hash("orderId")),
                List.of(index(ORDERS_BY_IDEMPOTENCY_KEY_INDEX, hash("idempotencyKey")),
                        index(ORDERS_BY_STATUS_EXPIRY_INDEX, hash("status"), range("reservationExpiresAt"))));
    }

    private static CreateTableRequest orderAudit() {
        return table(ORDER_AUDIT, List.of(string("orderId"), string("timestamp")),
                List.of(hash("orderId"), range("timestamp")), List.of());
    }

    private static CreateTableRequest table(String name, List<AttributeDefinition> attributes,
                                            List<KeySchemaElement> keys, List<GlobalSecondaryIndex> indexes) {
        CreateTableRequest.Builder builder = CreateTableRequest.builder()
                .tableName(name)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(attributes)
                .keySchema(keys);
        if (!indexes.isEmpty()) {
            builder.globalSecondaryIndexes(indexes);
        }
        return builder.build();
    }

    private static GlobalSecondaryIndex index(String name, KeySchemaElement... keys) {
        return GlobalSecondaryIndex.builder()
                .indexName(name)
                .keySchema(keys)
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build();
    }

    private static AttributeDefinition string(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement hash(String name) {
        return KeySchemaElement.builder().attributeName(name).keyType(KeyType.HASH).build();
    }

    private static KeySchemaElement range(String name) {
        return KeySchemaElement.builder().attributeName(name).keyType(KeyType.RANGE).build();
    }
}
