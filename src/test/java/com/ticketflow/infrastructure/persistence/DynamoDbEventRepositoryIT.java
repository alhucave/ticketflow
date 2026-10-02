package com.ticketflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.domain.exception.EventAlreadyExistsException;
import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Runs against DynamoDB Local. Enabled with INCLUDE_INTEGRATION=true. */
@Tag("integration")
class DynamoDbEventRepositoryIT {

    private static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000);

    private static DynamoDbAsyncClient client;
    private static DynamoDbEventRepository repository;

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
        repository = new DynamoDbEventRepository(client);
    }

    @AfterAll
    static void stop() {
        client.close();
        DYNAMO.stop();
    }

    private static Event event(String id) {
        return new Event(new EventId(id), "Concert " + id, Instant.parse("2030-05-06T07:08:09.123Z"), "Arena", 75);
    }

    @Test
    void save_newEvent_createsInventoryWithAvailableEqualToCapacity() {
        StepVerifier.create(repository.save(event("it-inv"))).expectNext(event("it-inv")).verifyComplete();

        var item = client.getItem(b -> b.tableName(DynamoDbTables.INVENTORY)
                .key(java.util.Map.of("eventId", AttributeValue.builder().s("it-inv").build()))).join().item();
        assertThat(item.get("available").n()).isEqualTo("75");
        assertThat(item.get("capacity").n()).isEqualTo("75");
        assertThat(item.get("version").n()).isEqualTo("0");
        assertThat(item.get("reserved").n()).isEqualTo("0");
    }

    @Test
    void findById_savedEvent_roundTripsAllFieldsIncludingInstant() {
        repository.save(event("it-find")).block(Duration.ofSeconds(10));

        StepVerifier.create(repository.findById(new EventId("it-find")))
                .expectNext(event("it-find")).verifyComplete();
    }

    @Test
    void findById_unknownId_completesEmpty() {
        StepVerifier.create(repository.findById(new EventId("missing"))).verifyComplete();
    }

    @Test
    void save_duplicateId_isRejectedAndOriginalIsNotOverwritten() {
        repository.save(event("it-dup")).block(Duration.ofSeconds(10));
        var other = new Event(new EventId("it-dup"), "Other", Instant.parse("2031-01-01T00:00:00Z"), "Club", 5);

        StepVerifier.create(repository.save(other)).expectError(EventAlreadyExistsException.class).verify();

        StepVerifier.create(repository.findById(new EventId("it-dup"))).expectNext(event("it-dup")).verifyComplete();
    }

    @Test
    void findAll_savedEvents_returnsThem() {
        repository.save(event("it-all-1")).block(Duration.ofSeconds(10));
        repository.save(event("it-all-2")).block(Duration.ofSeconds(10));

        var ids = repository.findAll().map(e -> e.id().value()).collectList().block(Duration.ofSeconds(10));
        assertThat(ids).contains("it-all-1", "it-all-2");
    }
}
