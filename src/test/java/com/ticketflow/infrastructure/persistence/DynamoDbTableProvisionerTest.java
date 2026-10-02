package com.ticketflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.CreateTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexDescription;
import software.amazon.awssdk.services.dynamodb.model.IndexStatus;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.dynamodb.model.UpdateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateTableResponse;

class DynamoDbTableProvisionerTest {

    private DynamoDbAsyncClient client;
    private DynamoDbTableProvisioner provisioner;

    @BeforeEach
    void setUp() {
        client = mock(DynamoDbAsyncClient.class);
        provisioner = new DynamoDbTableProvisioner(client, 3, Duration.ofMillis(1));
        when(client.createTable(any(CreateTableRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(CreateTableResponse.builder().build()));
        when(client.updateTable(any(UpdateTableRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(UpdateTableResponse.builder().build()));
    }

    private static CompletableFuture<DescribeTableResponse> failed(Throwable t) {
        return CompletableFuture.failedFuture(t);
    }

    private static DescribeTableResponse table(TableStatus status, String... activeIndexes) {
        List<GlobalSecondaryIndexDescription> gsis = new ArrayList<>();
        for (String name : activeIndexes) {
            gsis.add(GlobalSecondaryIndexDescription.builder().indexName(name)
                    .indexStatus(IndexStatus.ACTIVE).build());
        }
        return DescribeTableResponse.builder()
                .table(TableDescription.builder().tableStatus(status).globalSecondaryIndexes(gsis).build())
                .build();
    }

    @SuppressWarnings("unchecked")
    private void describeWith(java.util.function.Function<String, CompletableFuture<DescribeTableResponse>> fn) {
        when(client.describeTable(any(Consumer.class))).thenAnswer((InvocationOnMock inv) -> {
            DescribeTableRequest.Builder b = DescribeTableRequest.builder();
            ((Consumer<DescribeTableRequest.Builder>) inv.getArgument(0)).accept(b);
            return fn.apply(b.build().tableName());
        });
    }

    private static final String[] ORDERS_INDEXES = {
        DynamoDbTables.ORDERS_BY_IDEMPOTENCY_KEY_INDEX, DynamoDbTables.ORDERS_BY_STATUS_EXPIRY_INDEX};

    @Test
    void provision_tablesMissing_createsAllFourTables() {
        boolean[] created = new boolean[4];
        var names = List.of("events", "inventory", "orders", "order_audit");
        when(client.createTable(any(CreateTableRequest.class))).thenAnswer(inv -> {
            created[names.indexOf(inv.<CreateTableRequest>getArgument(0).tableName())] = true;
            return CompletableFuture.completedFuture(CreateTableResponse.builder().build());
        });
        describeWith(name -> created[names.indexOf(name)]
                ? CompletableFuture.completedFuture(table(TableStatus.ACTIVE, name.equals("orders")
                        ? ORDERS_INDEXES : new String[0]))
                : failed(ResourceNotFoundException.builder().build()));

        StepVerifier.create(provisioner.provision()).verifyComplete();

        var captor = ArgumentCaptor.forClass(CreateTableRequest.class);
        verify(client, times(4)).createTable(captor.capture());
        assertThat(captor.getAllValues()).extracting(CreateTableRequest::tableName).containsExactlyElementsOf(names);
        verify(client, never()).updateTable(any(UpdateTableRequest.class));
    }

    @Test
    void provision_tablesAlreadyExist_createsNothing() {
        describeWith(name -> CompletableFuture.completedFuture(table(TableStatus.ACTIVE,
                name.equals("orders") ? ORDERS_INDEXES : new String[0])));

        StepVerifier.create(provisioner.provision()).verifyComplete();

        verify(client, never()).createTable(any(CreateTableRequest.class));
        verify(client, never()).updateTable(any(UpdateTableRequest.class));
    }

    @Test
    void provision_createRacesWithAnotherInstance_toleratesResourceInUse() {
        when(client.createTable(any(CreateTableRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(ResourceInUseException.builder().build()));
        boolean[] seen = new boolean[1];
        describeWith(name -> {
            // first lookup per table: not found; afterwards: ACTIVE (created by the other instance)
            if (!name.equals("events") || seen[0]) {
                return CompletableFuture.completedFuture(table(TableStatus.ACTIVE,
                        name.equals("orders") ? ORDERS_INDEXES : new String[0]));
            }
            seen[0] = true;
            return failed(ResourceNotFoundException.builder().build());
        });

        StepVerifier.create(provisioner.provision()).verifyComplete();

        verify(client, times(1)).createTable(any(CreateTableRequest.class));
    }

    @Test
    void provision_ordersMissingOneGsi_addsOnlyTheMissingIndex() {
        boolean[] added = new boolean[1];
        when(client.updateTable(any(UpdateTableRequest.class))).thenAnswer(inv -> {
            added[0] = true;
            return CompletableFuture.completedFuture(UpdateTableResponse.builder().build());
        });
        describeWith(name -> CompletableFuture.completedFuture(table(TableStatus.ACTIVE,
                !name.equals("orders") ? new String[0]
                        : added[0] ? ORDERS_INDEXES : new String[] {DynamoDbTables.ORDERS_BY_IDEMPOTENCY_KEY_INDEX})));

        StepVerifier.create(provisioner.provision()).verifyComplete();

        var captor = ArgumentCaptor.forClass(UpdateTableRequest.class);
        verify(client).updateTable(captor.capture());
        var request = captor.getValue();
        assertThat(request.tableName()).isEqualTo("orders");
        var create = request.globalSecondaryIndexUpdates().get(0).create();
        assertThat(create.indexName()).isEqualTo(DynamoDbTables.ORDERS_BY_STATUS_EXPIRY_INDEX);
        assertThat(request.attributeDefinitions()).extracting(a -> a.attributeName())
                .containsExactlyInAnyOrder("status", "reservationExpiresAt");
    }

    @Test
    void provision_gsiUpdateRacesWithAnotherInstance_toleratesResourceInUse() {
        when(client.updateTable(any(UpdateTableRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(ResourceInUseException.builder().build()));
        describeWith(name -> CompletableFuture.completedFuture(table(TableStatus.ACTIVE,
                name.equals("orders") ? new String[0] : new String[0])));
        // ORDERS never gets indexes in this fake, so awaitActive still passes (no GSI descriptions to check).

        StepVerifier.create(provisioner.provision()).verifyComplete();

        verify(client, times(2)).updateTable(any(UpdateTableRequest.class));
    }

    @Test
    void provision_tableCreatingThenActive_pollsUntilActive() {
        int[] calls = new int[1];
        describeWith(name -> {
            if (!name.equals("events")) {
                return CompletableFuture.completedFuture(table(TableStatus.ACTIVE,
                        name.equals("orders") ? ORDERS_INDEXES : new String[0]));
            }
            // initial lookup + two CREATING polls, then ACTIVE
            return CompletableFuture.completedFuture(++calls[0] <= 3
                    ? table(TableStatus.CREATING) : table(TableStatus.ACTIVE));
        });

        StepVerifier.create(provisioner.provision()).verifyComplete();

        assertThat(calls[0]).isGreaterThan(3);
    }

    @Test
    void provision_tableNeverActive_failsAfterMaxAttempts() {
        describeWith(name -> CompletableFuture.completedFuture(table(TableStatus.CREATING)));

        StepVerifier.create(provisioner.provision())
                .expectError(DynamoDbTableProvisioner.TableNotActiveException.class)
                .verify();
    }

    @Test
    void provision_unexpectedDynamoError_propagates() {
        describeWith(name -> failed(DynamoDbException.builder().message("denied").build()));

        StepVerifier.create(provisioner.provision())
                .expectError(DynamoDbException.class)
                .verify();
    }
}
