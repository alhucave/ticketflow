package com.ticketflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeyType;

class DynamoDbTablesTest {

    private static Map<String, CreateTableRequest> byName() {
        return DynamoDbTables.definitions().stream()
                .collect(Collectors.toMap(CreateTableRequest::tableName, r -> r));
    }

    @Test
    void definitions_always_containsTheFourTables() {
        assertThat(byName()).containsOnlyKeys("events", "inventory", "orders", "order_audit");
    }

    @Test
    void orders_definition_hasBothGsis() {
        var orders = byName().get("orders");
        assertThat(orders.globalSecondaryIndexes()).hasSize(2);
        var byIndex = orders.globalSecondaryIndexes().stream()
                .collect(Collectors.toMap(g -> g.indexName(), g -> g));
        assertThat(byIndex.get(DynamoDbTables.ORDERS_BY_IDEMPOTENCY_KEY_INDEX).keySchema())
                .extracting(k -> k.attributeName()).containsExactly("idempotencyKey");
        var statusIndex = byIndex.get(DynamoDbTables.ORDERS_BY_STATUS_EXPIRY_INDEX).keySchema();
        assertThat(statusIndex).extracting(k -> k.attributeName() + ":" + k.keyType())
                .containsExactly("status:" + KeyType.HASH, "reservationExpiresAt:" + KeyType.RANGE);
    }

    @Test
    void orderAudit_definition_hasOrderIdHashAndTimestampRange() {
        assertThat(byName().get("order_audit").keySchema())
                .extracting(k -> k.attributeName() + ":" + k.keyType())
                .containsExactly("orderId:" + KeyType.HASH, "timestamp:" + KeyType.RANGE);
    }

    @Test
    void eventsAndInventory_definition_haveEventIdHashAndNoGsi() {
        for (String name : new String[] {"events", "inventory"}) {
            var table = byName().get(name);
            assertThat(table.keySchema()).extracting(k -> k.attributeName()).containsExactly("eventId");
            assertThat(table.hasGlobalSecondaryIndexes()).isFalse();
        }
    }
}
