package com.ticketflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.domain.exception.EventAlreadyExistsException;
import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Inventory;
import com.ticketflow.domain.model.Quantity;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/** Runs against DynamoDB Local. Enabled with INCLUDE_INTEGRATION=true. */
@Tag("integration")
class DynamoDbInventoryRepositoryIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000);

    private static DynamoDbAsyncClient client;
    private static DynamoDbInventoryRepository repository;

    @BeforeAll
    static void start() {
        DYNAMO.start();
        client = DynamoDbAsyncClient.builder()
                .endpointOverride(URI.create("http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        StepVerifier.create(new DynamoDbTableProvisioner(client, 10, Duration.ofMillis(100)).provision())
                .verifyComplete();
        repository = new DynamoDbInventoryRepository(client);
    }

    @AfterAll
    static void stop() {
        client.close();
        DYNAMO.stop();
    }

    private static EventId seed(String id, int capacity) {
        var eventId = new EventId(id);
        repository.create(Inventory.initial(eventId, capacity)).block(TIMEOUT);
        return eventId;
    }

    private static Inventory read(EventId id) {
        return repository.findByEventId(id).block(TIMEOUT);
    }

    private static void assertInvariant(Inventory i) {
        assertThat(i.available() + i.reserved() + i.pendingConfirmation() + i.sold() + i.complimentary())
                .isEqualTo(i.capacity());
        assertThat(List.of(i.available(), i.reserved(), i.sold(), i.complimentary())).allMatch(v -> v >= 0);
    }

    @Test
    void create_thenFind_roundTripsAndDuplicateIsRejected() {
        var id = seed("inv-create", 10);

        assertThat(read(id)).isEqualTo(Inventory.initial(id, 10));
        StepVerifier.create(repository.create(Inventory.initial(id, 99)))
                .expectError(EventAlreadyExistsException.class).verify();
        assertThat(read(id).capacity()).isEqualTo(10);
    }

    @Test
    void findByEventId_unknown_completesEmpty() {
        StepVerifier.create(repository.findByEventId(new EventId("inv-none"))).verifyComplete();
    }

    @Test
    void reserve_unknownEvent_failsWithEventNotFoundAndCreatesNothing() {
        var id = new EventId("inv-ghost");

        StepVerifier.create(repository.reserve(id, new Quantity(1))).expectError(EventNotFoundException.class).verify();

        StepVerifier.create(repository.findByEventId(id)).verifyComplete();
    }

    @Test
    void reserve_availableBelowQuantity_failsWithInsufficientInventoryAndLeavesStateUntouched() {
        var id = seed("inv-short", 3);

        StepVerifier.create(repository.reserve(id, new Quantity(4)))
                .expectError(InsufficientInventoryException.class).verify();

        assertThat(read(id)).isEqualTo(Inventory.initial(id, 3));
    }

    @Test
    void operations_fullLifecycle_moveCountersAndIncrementVersionOncePerWrite() {
        var id = seed("inv-life", 10);

        assertThat(repository.reserve(id, new Quantity(5)).block(TIMEOUT))
                .isEqualTo(new Inventory(id, 5, 5, 0, 0, 0, 10, 1));
        assertThat(repository.confirmSale(id, new Quantity(2)).block(TIMEOUT))
                .isEqualTo(new Inventory(id, 5, 3, 0, 2, 0, 10, 2));
        assertThat(repository.release(id, new Quantity(1)).block(TIMEOUT))
                .isEqualTo(new Inventory(id, 6, 2, 0, 2, 0, 10, 3));
        assertThat(repository.issueComplimentary(id, new Quantity(4)).block(TIMEOUT))
                .isEqualTo(new Inventory(id, 2, 2, 0, 2, 4, 10, 4));
        assertThat(read(id)).isEqualTo(new Inventory(id, 2, 2, 0, 2, 4, 10, 4));
    }

    @Test
    void confirmSaleReleaseAndComplimentary_sourceTooSmall_failWithInsufficientInventory() {
        var id = seed("inv-src", 5);

        StepVerifier.create(repository.confirmSale(id, new Quantity(1)))
                .expectError(InsufficientInventoryException.class).verify();
        StepVerifier.create(repository.release(id, new Quantity(1)))
                .expectError(InsufficientInventoryException.class).verify();
        StepVerifier.create(repository.issueComplimentary(id, new Quantity(6)))
                .expectError(InsufficientInventoryException.class).verify();

        assertThat(read(id)).isEqualTo(Inventory.initial(id, 5));
    }

    @Test
    void reserve_parallelSingleTickets_exactlyCapacitySucceedAndNeverOversells() {
        int capacity = 50;
        var id = seed("inv-race-1", capacity);

        var outcomes = Flux.range(0, 200)
                .flatMap(i -> repository.reserve(id, new Quantity(1))
                        .map(inv -> true)
                        .onErrorResume(InsufficientInventoryException.class, e -> Mono.just(false))
                        .subscribeOn(Schedulers.parallel()), 200)
                .collectList().block(TIMEOUT);

        assertThat(outcomes).hasSize(200);
        assertThat(outcomes.stream().filter(ok -> ok).count()).isEqualTo(capacity);
        assertThat(outcomes.stream().filter(ok -> !ok).count()).isEqualTo(200 - capacity);
        var finalState = read(id);
        assertThat(finalState).isEqualTo(new Inventory(id, 0, capacity, 0, 0, 0, capacity, capacity));
        assertInvariant(finalState);
    }

    @Test
    void reserve_parallelMixedQuantities_neverOversellsAndAccountsEveryTicket() {
        int capacity = 100;
        var id = seed("inv-race-mixed", capacity);
        var random = new Random(42);
        List<Integer> quantities = random.ints(200, 1, 5).boxed().toList();

        var outcomes = Flux.fromIterable(quantities)
                .flatMap(q -> repository.reserve(id, new Quantity(q))
                        .map(inv -> q)
                        .onErrorResume(InsufficientInventoryException.class, e -> Mono.just(-q))
                        .subscribeOn(Schedulers.parallel()), 200)
                .collectList().block(TIMEOUT);

        int reservedTotal = outcomes.stream().filter(v -> v > 0).mapToInt(Integer::intValue).sum();
        long successes = outcomes.stream().filter(v -> v > 0).count();
        var finalState = read(id);
        assertThat(outcomes).hasSize(200);
        assertThat(reservedTotal).isLessThanOrEqualTo(capacity);
        assertThat(finalState.reserved()).isEqualTo(reservedTotal);
        assertThat(finalState.available()).isEqualTo(capacity - reservedTotal);
        assertThat(finalState.version()).isEqualTo(successes);
        // every rejection was genuine: it could not have fit when it was rejected, so leftovers are < max qty
        assertThat(finalState.available()).isLessThan(4);
        assertInvariant(finalState);
    }

    @Test
    void mixedOperations_parallel_keepInvariantAndVersionEqualsSuccessfulWrites() {
        int capacity = 40;
        var id = seed("inv-race-ops", capacity);
        var random = new Random(7);

        var successes = Flux.range(0, 300)
                .flatMap(i -> {
                    var qty = new Quantity(1 + random.nextInt(3));
                    Mono<Inventory> op = switch (i % 4) {
                        case 0 -> repository.reserve(id, qty);
                        case 1 -> repository.confirmSale(id, qty);
                        case 2 -> repository.release(id, qty);
                        default -> repository.issueComplimentary(id, qty);
                    };
                    return op.map(inv -> 1).onErrorResume(InsufficientInventoryException.class, e -> Mono.just(0))
                            .subscribeOn(Schedulers.parallel());
                }, 300)
                .reduce(0, Integer::sum).block(TIMEOUT);

        var finalState = read(id);
        assertThat(finalState.version()).isEqualTo((long) successes);
        assertInvariant(finalState);
    }
}
