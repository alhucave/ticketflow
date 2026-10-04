package com.ticketflow.infrastructure.persistence;

import com.ticketflow.testsupport.TestTimeouts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.exception.EventAlreadyExistsException;
import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Inventory;
import com.ticketflow.domain.model.Quantity;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

class DynamoDbInventoryRepositoryTest {

    private static final EventId EID = new EventId("e-1");
    private static final Quantity TWO = new Quantity(2);

    private DynamoDbAsyncClient client;
    private DynamoDbInventoryRepository repository;

    @BeforeEach
    void setUp() {
        client = mock(DynamoDbAsyncClient.class);
        repository = new DynamoDbInventoryRepository(client, 3, Duration.ofMillis(1));
    }

    private static AttributeValue n(long v) {
        return AttributeValue.builder().n(Long.toString(v)).build();
    }

    private static Map<String, AttributeValue> item(Inventory i) {
        Map<String, AttributeValue> m = new HashMap<>();
        m.put("eventId", AttributeValue.builder().s(i.eventId().value()).build());
        m.put("available", n(i.available()));
        m.put("reserved", n(i.reserved()));
        m.put("pendingConfirmation", n(i.pendingConfirmation()));
        m.put("sold", n(i.sold()));
        m.put("complimentary", n(i.complimentary()));
        m.put("capacity", n(i.capacity()));
        m.put("version", n(i.version()));
        return m;
    }

    private static CompletableFuture<UpdateItemResponse> updated(Inventory i) {
        return CompletableFuture.completedFuture(UpdateItemResponse.builder().attributes(item(i)).build());
    }

    private static ConditionalCheckFailedException conditionFailed(Map<String, AttributeValue> oldItem) {
        return ConditionalCheckFailedException.builder().item(oldItem).build();
    }

    private static DynamoDbException awsError(int status, String code) {
        return (DynamoDbException) DynamoDbException.builder().statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build()).build();
    }

    private UpdateItemRequest singleUpdate() {
        var captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(client, times(1)).updateItem(captor.capture());
        return captor.getValue();
    }

    @Test
    void reserve_enoughAvailable_issuesOneConditionalUpdateIncrementingVersion() {
        var after = new Inventory(EID, 8, 2, 0, 0, 0, 10, 1);
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(updated(after));

        StepVerifier.create(repository.reserve(EID, TWO)).expectNext(after).verifyComplete();

        var request = singleUpdate();
        assertThat(request.tableName()).isEqualTo(DynamoDbTables.INVENTORY);
        assertThat(request.updateExpression()).contains("#version = #version + :one")
                .contains("#src = #src - :qty").contains("#dst = #dst + :qty");
        assertThat(request.conditionExpression()).contains("#src >= :qty");
        assertThat(request.expressionAttributeNames()).containsEntry("#src", "available")
                .containsEntry("#dst", "reserved").containsEntry("#version", "version");
        assertThat(request.expressionAttributeValues().get(":qty").n()).isEqualTo("2");
        assertThat(request.expressionAttributeValues().get(":one").n()).isEqualTo("1");
        verify(client, times(0)).getItem(any(GetItemRequest.class));
    }

    @Test
    void confirmSale_enoughReserved_movesReservedToSold() {
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(updated(new Inventory(EID, 8, 0, 0, 2, 0, 10, 2)));

        StepVerifier.create(repository.confirmSale(EID, TWO)).expectNextCount(1).verifyComplete();

        assertThat(singleUpdate().expressionAttributeNames())
                .containsEntry("#src", "reserved").containsEntry("#dst", "sold");
    }

    @Test
    void release_enoughReserved_movesReservedToAvailable() {
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(updated(new Inventory(EID, 10, 0, 0, 0, 0, 10, 2)));

        StepVerifier.create(repository.release(EID, TWO)).expectNextCount(1).verifyComplete();

        assertThat(singleUpdate().expressionAttributeNames())
                .containsEntry("#src", "reserved").containsEntry("#dst", "available");
    }

    @Test
    void issueComplimentary_enoughAvailable_movesAvailableToComplimentary() {
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(updated(new Inventory(EID, 8, 0, 0, 0, 2, 10, 1)));

        StepVerifier.create(repository.issueComplimentary(EID, TWO)).expectNextCount(1).verifyComplete();

        assertThat(singleUpdate().expressionAttributeNames())
                .containsEntry("#src", "available").containsEntry("#dst", "complimentary");
    }

    @Test
    void reserve_availableBelowQuantity_failsWithInsufficientInventoryAndIsNotRetried() {
        var old = item(new Inventory(EID, 1, 9, 0, 0, 0, 10, 4));
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(conditionFailed(old)));

        StepVerifier.create(repository.reserve(EID, TWO))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(InsufficientInventoryException.class)
                        .hasMessageContaining("e-1"))
                .verify(TestTimeouts.WAIT);

        verify(client, times(1)).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void reserve_missingInventoryItem_failsWithEventNotFound() {
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(conditionFailed(Map.of())));

        StepVerifier.create(repository.reserve(EID, TWO)).expectError(EventNotFoundException.class).verify();

        verify(client, times(1)).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void reserve_conditionFailedWithoutItem_failsWithEventNotFound() {
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(ConditionalCheckFailedException.builder().build()));

        StepVerifier.create(repository.reserve(EID, TWO)).expectError(EventNotFoundException.class).verify();
    }

    @Test
    void reserve_throttledThenSucceeds_isRetried() {
        var after = new Inventory(EID, 8, 2, 0, 0, 0, 10, 1);
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(
                CompletableFuture.failedFuture(ProvisionedThroughputExceededException.builder().build()),
                CompletableFuture.failedFuture(awsError(400, "ThrottlingException")),
                CompletableFuture.failedFuture(InternalServerErrorException.builder().build()),
                updated(after));

        StepVerifier.create(repository.reserve(EID, TWO)).expectNext(after).expectComplete()
                .verify(TestTimeouts.WAIT);

        verify(client, times(4)).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void reserve_serviceUnavailable_isRetried() {
        var after = new Inventory(EID, 8, 2, 0, 0, 0, 10, 1);
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(
                CompletableFuture.failedFuture(awsError(503, "ServiceUnavailable")), updated(after));

        StepVerifier.create(repository.reserve(EID, TWO)).expectNext(after).verifyComplete();

        verify(client, times(2)).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void reserve_persistentThrottling_failsWithOriginalErrorAfterMaxRetries() {
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(
                CompletableFuture.failedFuture(ProvisionedThroughputExceededException.builder().build()));

        StepVerifier.create(repository.reserve(EID, TWO))
                .expectError(ProvisionedThroughputExceededException.class).verify(TestTimeouts.WAIT);

        verify(client, times(4)).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void reserve_nonTransientError_isNotRetried() {
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(
                CompletableFuture.failedFuture(ResourceNotFoundException.builder().build()));

        StepVerifier.create(repository.reserve(EID, TWO)).expectError(ResourceNotFoundException.class).verify();

        verify(client, times(1)).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void isTransient_variousErrors_onlyInfrastructureThrottlingAndServerErrors() {
        assertThat(DynamoDbInventoryRepository.isTransient(
                ProvisionedThroughputExceededException.builder().build())).isTrue();
        assertThat(DynamoDbInventoryRepository.isTransient(
                software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException.builder().build()))
                .isTrue();
        assertThat(DynamoDbInventoryRepository.isTransient(
                software.amazon.awssdk.services.dynamodb.model.LimitExceededException.builder().build())).isTrue();
        assertThat(DynamoDbInventoryRepository.isTransient(awsError(400, "ThrottlingException"))).isTrue();
        assertThat(DynamoDbInventoryRepository.isTransient(conditionFailed(Map.of()))).isFalse();
        assertThat(DynamoDbInventoryRepository.isTransient(awsError(400, "ValidationException"))).isFalse();
        assertThat(DynamoDbInventoryRepository.isTransient(new InsufficientInventoryException(EID, TWO))).isFalse();
        assertThat(DynamoDbInventoryRepository.isTransient(new IllegalStateException())).isFalse();
    }

    @Test
    void defaultConstructor_usesDefaultRetryPolicy() {
        var after = new Inventory(EID, 8, 2, 0, 0, 0, 10, 1);
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(updated(after));

        StepVerifier.create(new DynamoDbInventoryRepository(client).reserve(EID, TWO))
                .expectNext(after).verifyComplete();
    }

    @Test
    void create_newInventory_putsItemConditionallyAndReturnsIt() {
        var inventory = Inventory.initial(EID, 10);
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        StepVerifier.create(repository.create(inventory)).expectNext(inventory).verifyComplete();

        var captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(captor.capture());
        assertThat(captor.getValue().conditionExpression()).isEqualTo("attribute_not_exists(eventId)");
        assertThat(captor.getValue().item().get("available").n()).isEqualTo("10");
        assertThat(captor.getValue().item().get("version").n()).isEqualTo("0");
    }

    @Test
    void create_existingInventory_failsWithEventAlreadyExists() {
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(ConditionalCheckFailedException.builder().build()));

        StepVerifier.create(repository.create(Inventory.initial(EID, 10)))
                .expectError(EventAlreadyExistsException.class).verify();
    }

    @Test
    void findByEventId_existingItem_mapsToInventory() {
        var inventory = new Inventory(EID, 5, 2, 1, 1, 1, 10, 7);
        when(client.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetItemResponse.builder().item(item(inventory)).build()));

        StepVerifier.create(repository.findByEventId(EID)).expectNext(inventory).verifyComplete();

        var captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(captor.capture());
        assertThat(captor.getValue().consistentRead()).isTrue();
    }

    @Test
    void findByEventId_unknownId_completesEmpty() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        StepVerifier.create(repository.findByEventId(EID)).verifyComplete();
    }

    /**
     * Property-style test: an in-memory evaluator honours the conditional update semantics (counter names,
     * amount, condition, version bump) so random operation sequences exercise the real request building.
     */
    @Test
    void anyOperationSequence_keepsCounterInvariantAndNeverOversells() {
        for (long seed = 0; seed < 50; seed++) {
            var random = new Random(seed);
            int capacity = 1 + random.nextInt(30);
            Map<String, AttributeValue> state = new HashMap<>(item(Inventory.initial(EID, capacity)));
            when(client.updateItem(any(UpdateItemRequest.class))).thenAnswer(invocation -> {
                UpdateItemRequest r = invocation.getArgument(0);
                String src = r.expressionAttributeNames().get("#src");
                String dst = r.expressionAttributeNames().get("#dst");
                long qty = Long.parseLong(r.expressionAttributeValues().get(":qty").n());
                long available = Long.parseLong(state.get(src).n());
                if (available < qty) {
                    return CompletableFuture.failedFuture(conditionFailed(Map.copyOf(state)));
                }
                state.put(src, n(available - qty));
                state.put(dst, n(Long.parseLong(state.get(dst).n()) + qty));
                state.put("version", n(Long.parseLong(state.get("version").n()) + 1));
                return CompletableFuture.completedFuture(
                        UpdateItemResponse.builder().attributes(Map.copyOf(state)).build());
            });

            long successes = 0;
            for (int step = 0; step < 100; step++) {
                var qty = new Quantity(1 + random.nextInt(5));
                BiFunction<EventId, Quantity, Mono<Inventory>> op = switch (random.nextInt(4)) {
                    case 0 -> repository::reserve;
                    case 1 -> repository::confirmSale;
                    case 2 -> repository::release;
                    default -> repository::issueComplimentary;
                };
                try {
                    // fromItem builds an Inventory, whose constructor rejects any broken invariant
                    Inventory result = op.apply(EID, qty).block(TestTimeouts.WAIT);
                    successes++;
                    assertThat(result.version()).isEqualTo(successes);
                } catch (InsufficientInventoryException expected) {
                    // business rejection leaves the state untouched
                }
                Inventory current = repositoryView(state);
                assertThat(current.available() + current.reserved() + current.pendingConfirmation()
                        + current.sold() + current.complimentary()).isEqualTo(capacity);
                assertThat(current.available()).isGreaterThanOrEqualTo(0);
                assertThat(current.reserved()).isGreaterThanOrEqualTo(0);
                assertThat(current.version()).isEqualTo(successes);
            }
        }
    }

    private static Inventory repositoryView(Map<String, AttributeValue> state) {
        return new Inventory(EID,
                Integer.parseInt(state.get("available").n()), Integer.parseInt(state.get("reserved").n()),
                Integer.parseInt(state.get("pendingConfirmation").n()), Integer.parseInt(state.get("sold").n()),
                Integer.parseInt(state.get("complimentary").n()), Integer.parseInt(state.get("capacity").n()),
                Long.parseLong(state.get("version").n()));
    }
}
