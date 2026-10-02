package com.ticketflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.domain.exception.InvalidStateTransitionException;
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
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Runs against DynamoDB Local. Enabled with INCLUDE_INTEGRATION=true. */
@Tag("integration")
class DynamoDbOrderRepositoryIT {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    private static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000);

    private static DynamoDbAsyncClient client;
    private static DynamoDbOrderRepository repository;

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
        repository = new DynamoDbOrderRepository(client);
    }

    @AfterAll
    static void stop() {
        client.close();
        DYNAMO.stop();
    }

    private static Order order(String id, TicketStatus status, Instant expiresAt) {
        return new Order(new OrderId(id), new EventId("e-1"), new Quantity(2), status,
                new IdempotencyKey("key-" + id), expiresAt, NOW.minusSeconds(60));
    }

    private static String uid() {
        return UUID.randomUUID().toString();
    }

    @Test
    void saveAndFindById_order_roundTripsAllFields() {
        var order = new Order(new OrderId(uid()), new EventId("e-9"), new Quantity(4), TicketStatus.RESERVED,
                new IdempotencyKey(uid()), Instant.parse("2030-01-01T10:10:00.123456789Z"), NOW);
        repository.save(order).block(WAIT);

        StepVerifier.create(repository.findById(order.id())).expectNext(order).verifyComplete();
    }

    @Test
    void findById_unknown_completesEmpty() {
        StepVerifier.create(repository.findById(new OrderId("missing"))).verifyComplete();
    }

    @Test
    void save_duplicateId_isRejectedAndOriginalIsNotOverwritten() {
        var id = uid();
        var original = order(id, TicketStatus.RESERVED, NOW.plusSeconds(600));
        repository.save(original).block(WAIT);
        var other = new Order(original.id(), new EventId("e-other"), new Quantity(9), TicketStatus.RESERVED,
                new IdempotencyKey("other"), NOW.plusSeconds(900), NOW.minusSeconds(1));

        StepVerifier.create(repository.save(other)).expectError(OrderAlreadyExistsException.class).verify();

        StepVerifier.create(repository.findById(original.id())).expectNext(original).verifyComplete();
    }

    @Test
    void findByIdempotencyKey_existing_returnsOrder() {
        var order = order(uid(), TicketStatus.RESERVED, NOW.plusSeconds(600));
        repository.save(order).block(WAIT);

        StepVerifier.create(repository.findByIdempotencyKey(order.idempotencyKey()))
                .expectNext(order).verifyComplete();
        StepVerifier.create(repository.findByIdempotencyKey(new IdempotencyKey(uid()))).verifyComplete();
    }

    @Test
    void transition_expectedStatusMatches_updatesStatusAndAppendsAudit() {
        var order = order(uid(), TicketStatus.RESERVED, NOW.plusSeconds(600));
        repository.save(order).block(WAIT);

        StepVerifier.create(repository.transition(order.id(), TicketStatus.RESERVED,
                        TicketStatus.PENDING_CONFIRMATION, "consumer", NOW))
                .expectNext(new OrderAuditEntry(order.id(), NOW, TicketStatus.RESERVED,
                        TicketStatus.PENDING_CONFIRMATION, "consumer"))
                .verifyComplete();

        StepVerifier.create(repository.findById(order.id()))
                .expectNext(order.transitionTo(TicketStatus.PENDING_CONFIRMATION)).verifyComplete();
        StepVerifier.create(repository.findAuditTrail(order.id()))
                .expectNext(new OrderAuditEntry(order.id(), NOW, TicketStatus.RESERVED,
                        TicketStatus.PENDING_CONFIRMATION, "consumer"))
                .verifyComplete();
    }

    @Test
    void transition_staleExpectedStatus_failsWithConflictAndWritesNothing() {
        var order = order(uid(), TicketStatus.PENDING_CONFIRMATION, NOW.plusSeconds(600));
        repository.save(order).block(WAIT);

        StepVerifier.create(repository.transition(order.id(), TicketStatus.RESERVED,
                        TicketStatus.AVAILABLE, "scheduler", NOW))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(OrderStatusConflictException.class);
                    assertThat(((OrderStatusConflictException) e).actual())
                            .isEqualTo(TicketStatus.PENDING_CONFIRMATION);
                }).verify();

        StepVerifier.create(repository.findById(order.id())).expectNext(order).verifyComplete();
        StepVerifier.create(repository.findAuditTrail(order.id())).verifyComplete();
    }

    @Test
    void transition_unknownOrder_failsWithNotFoundAndWritesNoAudit() {
        var id = new OrderId(uid());

        StepVerifier.create(repository.transition(id, TicketStatus.RESERVED, TicketStatus.AVAILABLE, "x", NOW))
                .expectError(OrderNotFoundException.class).verify();

        StepVerifier.create(repository.findAuditTrail(id)).verifyComplete();
    }

    @Test
    void transition_invalidStateMachineMove_isRejectedBeforeWriting() {
        var order = order(uid(), TicketStatus.SOLD, NOW.plusSeconds(600));
        repository.save(order).block(WAIT);

        StepVerifier.create(repository.transition(order.id(), TicketStatus.SOLD, TicketStatus.AVAILABLE, "x", NOW))
                .expectError(InvalidStateTransitionException.class).verify();

        StepVerifier.create(repository.findById(order.id())).expectNext(order).verifyComplete();
    }

    @Test
    void transition_twoParallelFromSameStatus_exactlyOneWinsAndOneAuditEntry() {
        var order = order(uid(), TicketStatus.RESERVED, NOW.plusSeconds(600));
        repository.save(order).block(WAIT);

        List<Object> outcomes = Mono.zip(
                        repository.transition(order.id(), TicketStatus.RESERVED,
                                TicketStatus.PENDING_CONFIRMATION, "consumer", NOW)
                                .map(Object.class::cast).onErrorResume(e -> Mono.just(e)),
                        repository.transition(order.id(), TicketStatus.RESERVED,
                                TicketStatus.AVAILABLE, "scheduler", NOW)
                                .map(Object.class::cast).onErrorResume(e -> Mono.just(e)))
                .map(t -> List.of(t.getT1(), t.getT2()))
                .block(WAIT);

        assertThat(outcomes).filteredOn(o -> o instanceof OrderAuditEntry).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof OrderStatusConflictException).hasSize(1);
        var winner = (OrderAuditEntry) outcomes.stream().filter(o -> o instanceof OrderAuditEntry)
                .findFirst().orElseThrow();
        assertThat(repository.findById(order.id()).block(WAIT).status()).isEqualTo(winner.to());
        assertThat(repository.findAuditTrail(order.id()).collectList().block(WAIT)).containsExactly(winner);
    }

    @Test
    void transition_manyParallelFromSameStatus_exactlyOneWins() {
        var order = order(uid(), TicketStatus.RESERVED, NOW.plusSeconds(600));
        repository.save(order).block(WAIT);

        var results = reactor.core.publisher.Flux.range(0, 8)
                .flatMap(i -> repository.transition(order.id(), TicketStatus.RESERVED,
                                TicketStatus.PENDING_CONFIRMATION, "actor-" + i, NOW)
                        .map(Object.class::cast).onErrorResume(e -> Mono.just(e)))
                .collectList().block(WAIT);

        assertThat(results).filteredOn(o -> o instanceof OrderAuditEntry).hasSize(1);
        assertThat(results).filteredOn(o -> o instanceof OrderStatusConflictException).hasSize(7);
        assertThat(repository.findAuditTrail(order.id()).collectList().block(WAIT)).hasSize(1);
    }

    @Test
    void auditTrail_twoEntriesAtSameInstant_bothPersistedWithoutCollision() {
        var id = new OrderId(uid());
        var first = new OrderAuditEntry(id, NOW, TicketStatus.AVAILABLE, TicketStatus.RESERVED, "api");
        var second = new OrderAuditEntry(id, NOW, TicketStatus.RESERVED, TicketStatus.AVAILABLE, "scheduler");
        repository.saveAuditEntry(first).block(WAIT);
        repository.saveAuditEntry(second).block(WAIT);

        var trail = repository.findAuditTrail(id).collectList().block(WAIT);

        assertThat(trail).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void auditTrail_entriesAtDifferentPrecisions_areReturnedChronologically() {
        var id = new OrderId(uid());
        var whole = new OrderAuditEntry(id, NOW, TicketStatus.AVAILABLE, TicketStatus.RESERVED, "a");
        var fraction = new OrderAuditEntry(id, NOW.plusMillis(500), TicketStatus.RESERVED,
                TicketStatus.PENDING_CONFIRMATION, "b");
        var later = new OrderAuditEntry(id, NOW.plusSeconds(1), TicketStatus.PENDING_CONFIRMATION,
                TicketStatus.SOLD, "c");
        repository.saveAuditEntry(later).block(WAIT);
        repository.saveAuditEntry(fraction).block(WAIT);
        repository.saveAuditEntry(whole).block(WAIT);

        StepVerifier.create(repository.findAuditTrail(id)).expectNext(whole, fraction, later).verifyComplete();
    }

    @Test
    void findExpiredReservations_onlyExpirableStatusesBeforeNow() {
        var tag = uid().substring(0, 8);
        var past = NOW.minusSeconds(1);
        var expiredReserved = order("exp-r-" + tag, TicketStatus.RESERVED, past);
        var expiredPending = order("exp-p-" + tag, TicketStatus.PENDING_CONFIRMATION, past);
        var notYetExpired = order("live-" + tag, TicketStatus.RESERVED, NOW.plusSeconds(1));
        var exactlyNow = order("edge-" + tag, TicketStatus.RESERVED, NOW);
        var expiredSold = order("sold-" + tag, TicketStatus.SOLD, past);
        var expiredAvailable = order("avail-" + tag, TicketStatus.AVAILABLE, past);
        for (var o : List.of(expiredReserved, expiredPending, notYetExpired, exactlyNow, expiredSold,
                expiredAvailable)) {
            repository.save(o).block(WAIT);
        }

        var found = repository.findExpiredReservations(NOW).collectList().block(WAIT);

        assertThat(found).contains(expiredReserved, expiredPending)
                .doesNotContain(notYetExpired, exactlyNow, expiredSold, expiredAvailable);
        assertThat(found).allSatisfy(o -> {
            assertThat(o.status()).isIn(TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION);
            assertThat(o.reservationExpiresAt()).isBefore(NOW);
        });
    }

    @Test
    void findExpiredReservations_afterTransitionToSold_orderNoLongerListed() {
        var o = order(uid(), TicketStatus.PENDING_CONFIRMATION, NOW.minusSeconds(5));
        repository.save(o).block(WAIT);
        repository.transition(o.id(), TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD, "consumer", NOW)
                .block(WAIT);

        assertThat(repository.findExpiredReservations(NOW).collectList().block(WAIT))
                .extracting(Order::id).doesNotContain(o.id());
    }

    @Test
    void audit_sortKey_isTimestampHashUuid() {
        var id = new OrderId(uid());
        repository.saveAuditEntry(new OrderAuditEntry(id, NOW, TicketStatus.AVAILABLE, TicketStatus.RESERVED, "a"))
                .block(WAIT);

        var items = client.query(b -> b.tableName(DynamoDbTables.ORDER_AUDIT)
                .keyConditionExpression("orderId = :id")
                .expressionAttributeValues(Map.of(":id", AttributeValue.builder().s(id.value()).build())))
                .join().items();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("timestamp").s())
                .matches("2030-01-01T10:00:00\\.000000000Z#[0-9a-f-]{36}");
    }
}
