package com.ticketflow.infrastructure.persistence;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.CreateGlobalSecondaryIndexAction;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexDescription;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexUpdate;
import software.amazon.awssdk.services.dynamodb.model.IndexStatus;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.dynamodb.model.UpdateTableRequest;

/**
 * Idempotently creates the DynamoDB tables and GSIs of {@link DynamoDbTables}.
 * Existing tables are left untouched; only missing GSIs are added. Safe to run any number of times.
 */
public class DynamoDbTableProvisioner {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbTableProvisioner.class);

    private final DynamoDbAsyncClient client;
    private final int maxAttempts;
    private final Duration pollInterval;

    public DynamoDbTableProvisioner(DynamoDbAsyncClient client, int maxAttempts, Duration pollInterval) {
        this.client = client;
        this.maxAttempts = maxAttempts;
        this.pollInterval = pollInterval;
    }

    /** Provisions all tables sequentially and completes when every table and GSI is ACTIVE. */
    public Mono<Void> provision() {
        return Flux.fromIterable(DynamoDbTables.definitions())
                .concatMap(this::ensureTable)
                .then();
    }

    private Mono<Void> ensureTable(CreateTableRequest definition) {
        String table = definition.tableName();
        return describe(table)
                .flatMap(existing -> existing
                        .map(description -> addMissingIndexes(definition, description))
                        .orElseGet(() -> create(definition)))
                .then(Mono.defer(() -> awaitActive(table)))
                .doOnSuccess(unused -> log.info("DynamoDB table {} is ready", table));
    }

    private Mono<Optional<TableDescription>> describe(String table) {
        return Mono.fromFuture(() -> client.describeTable(b -> b.tableName(table)))
                .map(response -> Optional.of(response.table()))
                .onErrorResume(ResourceNotFoundException.class, e -> Mono.just(Optional.empty()));
    }

    private Mono<Void> create(CreateTableRequest definition) {
        return Mono.fromFuture(() -> client.createTable(definition))
                .doOnNext(unused -> log.info("Created DynamoDB table {}", definition.tableName()))
                .onErrorResume(ResourceInUseException.class, e -> {
                    log.info("DynamoDB table {} already exists", definition.tableName());
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> addMissingIndexes(CreateTableRequest definition, TableDescription existing) {
        Set<String> present = existing.globalSecondaryIndexes().stream()
                .map(GlobalSecondaryIndexDescription::indexName)
                .collect(Collectors.toSet());
        List<GlobalSecondaryIndex> missing = definition.globalSecondaryIndexes().stream()
                .filter(gsi -> !present.contains(gsi.indexName()))
                .toList();
        return Flux.fromIterable(missing)
                .concatMap(gsi -> awaitActive(definition.tableName()).then(addIndex(definition, gsi)))
                .then();
    }

    private Mono<Void> addIndex(CreateTableRequest definition, GlobalSecondaryIndex gsi) {
        UpdateTableRequest request = UpdateTableRequest.builder()
                .tableName(definition.tableName())
                .attributeDefinitions(definition.attributeDefinitions().stream()
                        .filter(attribute -> gsi.keySchema().stream()
                                .anyMatch(key -> key.attributeName().equals(attribute.attributeName())))
                        .toList())
                .globalSecondaryIndexUpdates(GlobalSecondaryIndexUpdate.builder()
                        .create(CreateGlobalSecondaryIndexAction.builder()
                                .indexName(gsi.indexName())
                                .keySchema(gsi.keySchema())
                                .projection(gsi.projection())
                                .build())
                        .build())
                .build();
        return Mono.fromFuture(() -> client.updateTable(request))
                .doOnNext(unused -> log.info("Adding GSI {} to {}", gsi.indexName(), definition.tableName()))
                .onErrorResume(ResourceInUseException.class, e -> Mono.empty())
                .then();
    }

    private Mono<Void> awaitActive(String table) {
        return Mono.fromFuture(() -> client.describeTable(b -> b.tableName(table)))
                .map(response -> response.table())
                .flatMap(description -> isActive(description)
                        ? Mono.<Void>empty()
                        : Mono.error(new TableNotActiveException(table)))
                .retryWhen(Retry.fixedDelay(maxAttempts, pollInterval)
                        .filter(TableNotActiveException.class::isInstance)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    private static boolean isActive(TableDescription description) {
        return description.tableStatus() == TableStatus.ACTIVE
                && description.globalSecondaryIndexes().stream()
                        .allMatch(gsi -> gsi.indexStatus() == IndexStatus.ACTIVE);
    }

    /** Signals that a table or one of its GSIs is not yet ACTIVE (retried while polling). */
    static class TableNotActiveException extends RuntimeException {
        TableNotActiveException(String table) {
            super("DynamoDB table not ACTIVE yet: " + table);
        }
    }
}
