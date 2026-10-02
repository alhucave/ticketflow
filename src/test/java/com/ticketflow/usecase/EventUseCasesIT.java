package com.ticketflow.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.domain.exception.InvalidEventException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.model.Inventory;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.infrastructure.persistence.DynamoDbEventRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbInventoryRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbTableProvisioner;
import com.ticketflow.infrastructure.persistence.DynamoDbTables;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/** End-to-end use cases over real DynamoDB adapters. Enabled with INCLUDE_INTEGRATION=true. */
@Tag("integration")
class EventUseCasesIT {

    private static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000);

    private static DynamoDbAsyncClient client;
    private static CreateEventUseCase create;
    private static GetEventUseCase get;
    private static ListEventsUseCase list;
    private static GetAvailabilityUseCase availability;
    private static DynamoDbInventoryRepository inventoriesRepo;

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
        var events = new DynamoDbEventRepository(client);
        var inventories = new DynamoDbInventoryRepository(client);
        create = new CreateEventUseCase(events, () -> EventId.generate(), Clock.systemUTC());
        get = new GetEventUseCase(events, inventories);
        list = new ListEventsUseCase(events);
        inventoriesRepo = inventories;
        availability = new GetAvailabilityUseCase(inventories, Duration.ofMillis(100), Schedulers.parallel());
    }

    @AfterAll
    static void stop() {
        client.close();
        DYNAMO.stop();
    }

    @Test
    void createGetList_endToEnd_inventoryInitializedOnce() {
        Instant startsAt = Instant.now().plus(Duration.ofDays(30));
        var created = create.execute(new CreateEventCommand("Rock", startsAt, "Arena", 120))
                .block(Duration.ofSeconds(10));
        assertThat(created).isNotNull();

        StepVerifier.create(get.execute(created.id()))
                .assertNext(details -> {
                    assertThat(details.event()).isEqualTo(created);
                    assertThat(details.inventory()).isEqualTo(Inventory.initial(created.id(), 120));
                    assertThat(details.inventory().available()).isEqualTo(120);
                    assertThat(details.inventory().version()).isZero();
                })
                .verifyComplete();

        var listed = list.execute().collectList().block(Duration.ofSeconds(10));
        assertThat(listed).contains(created);
    }

    @Test
    void create_invalidInput_isRejectedWithoutWritingAnything() {
        int eventsBefore = count(DynamoDbTables.EVENTS);
        int inventoryBefore = count(DynamoDbTables.INVENTORY);
        StepVerifier.create(create.execute(new CreateEventCommand(" ", Instant.now().plusSeconds(3600), "Arena", 10)))
                .expectError(InvalidEventException.class)
                .verify();
        StepVerifier.create(create.execute(new CreateEventCommand("Rock", Instant.now().minusSeconds(60), "Arena", 10)))
                .expectError(InvalidEventException.class)
                .verify();
        StepVerifier.create(create.execute(new CreateEventCommand("Rock", Instant.now().plusSeconds(3600), "Arena", 0)))
                .expectError(InvalidEventException.class)
                .verify();

        assertThat(count(DynamoDbTables.EVENTS)).isEqualTo(eventsBefore);
        assertThat(count(DynamoDbTables.INVENTORY)).isEqualTo(inventoryBefore);
    }

    @Test
    void availability_afterCreateAndReserve_reflectsReservedAndStreamsChange() {
        var created = create.execute(new CreateEventCommand("Jazz", Instant.now().plus(Duration.ofDays(5)), "Hall", 50))
                .block(Duration.ofSeconds(10));
        assertThat(created).isNotNull();

        StepVerifier.create(availability.execute(created.id()))
                .expectNext(new Availability(created.id(), 50, 0, 0, 0, 0, 50))
                .verifyComplete();

        StepVerifier.create(availability.stream(created.id()).take(2))
                .expectNext(new Availability(created.id(), 50, 0, 0, 0, 0, 50))
                .then(() -> inventoriesRepo.reserve(created.id(), new Quantity(5)).block(Duration.ofSeconds(10)))
                .expectNext(new Availability(created.id(), 45, 5, 0, 0, 0, 50))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        StepVerifier.create(availability.execute(created.id()))
                .assertNext(a -> {
                    assertThat(a.reserved()).isEqualTo(5);
                    assertThat(a.available()).isEqualTo(45);
                })
                .verifyComplete();
    }

    @Test
    void availability_unknownEvent_failsWithEventNotFound() {
        StepVerifier.create(availability.execute(new EventId("missing")))
                .expectError(EventNotFoundException.class)
                .verify();
        StepVerifier.create(availability.stream(new EventId("missing")))
                .expectError(EventNotFoundException.class)
                .verify();
    }

    private static int count(String table) {
        return client.scan(b -> b.tableName(table)).join().count();
    }
}
