package com.ticketflow.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.exception.IdempotencyKeyReusedException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.exception.OrderEnqueueFailedException;
import com.ticketflow.domain.exception.OrderStatusConflictException;
import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Inventory;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderAuditEntry;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderQueuePublisher;
import com.ticketflow.infrastructure.persistence.DynamoDbEventRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbInventoryRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbOrderPlacementRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbOrderRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbTableProvisioner;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Purchase request over the REAL DynamoDB adapters (DynamoDB Local) with an in-memory queue.
 * Enabled with INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
class RequestPurchaseUseCaseIT {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    private static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000);

    private static DynamoDbAsyncClient client;
    private static DynamoDbEventRepository events;
    private static DynamoDbInventoryRepository inventories;
    private static DynamoDbOrderRepository orders;
    private static DynamoDbOrderPlacementRepository placement;

    /** In-memory queue; can be switched to fail. */
    private static final class FakeQueue implements OrderQueuePublisher {
        final List<Order> published = new CopyOnWriteArrayList<>();
        final AtomicBoolean failing = new AtomicBoolean();

        @Override
        public Mono<Void> publish(Order order) {
            return Mono.defer(() -> {
                if (failing.get()) {
                    return Mono.error(new IllegalStateException("queue unavailable"));
                }
                published.add(order);
                return Mono.empty();
            });
        }
    }

    private FakeQueue queue;
    private RequestPurchaseUseCase useCase;

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
        events = new DynamoDbEventRepository(client);
        inventories = new DynamoDbInventoryRepository(client);
        orders = new DynamoDbOrderRepository(client);
        placement = new DynamoDbOrderPlacementRepository(client);
    }

    @AfterAll
    static void stop() {
        client.close();
        DYNAMO.stop();
    }

    @BeforeEach
    void setUp() {
        queue = new FakeQueue();
        useCase = new RequestPurchaseUseCase(placement, orders, queue,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10));
    }

    private static EventId newEvent(int capacity) {
        var event = new Event(EventId.generate(), "Concert", Instant.parse("2031-01-01T00:00:00Z"), "Arena",
                capacity);
        events.save(event).block(WAIT);
        return event.id();
    }

    private static Inventory inventory(EventId id) {
        return inventories.findByEventId(id).block(WAIT);
    }

    private static void assertInvariant(Inventory inv) {
        assertThat(inv.available() + inv.reserved() + inv.pendingConfirmation() + inv.sold() + inv.complimentary())
                .isEqualTo(inv.capacity());
    }

    private static RequestPurchaseCommand command(EventId event, int qty, String key) {
        return new RequestPurchaseCommand(event, new Quantity(qty), new IdempotencyKey(key));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void execute_purchase_returnsOrderIdReservesInventoryAndEnqueues() {
        var event = newEvent(50);
        var key = key();

        var result = useCase.execute(command(event, 4, key)).block(WAIT);

        assertThat(result).isNotNull();
        assertThat(result.orderId()).isEqualTo(OrderId.fromIdempotencyKey(new IdempotencyKey(key)));
        assertThat(result.status()).isEqualTo(TicketStatus.RESERVED);
        assertThat(result.replayed()).isFalse();
        var inv = inventory(event);
        assertThat(inv.reserved()).isEqualTo(4);
        assertThat(inv.available()).isEqualTo(46);
        assertThat(inv.version()).isEqualTo(1);
        assertInvariant(inv);

        var stored = orders.findById(result.orderId()).block(WAIT);
        assertThat(stored.status()).isEqualTo(TicketStatus.RESERVED);
        assertThat(stored.quantity()).isEqualTo(new Quantity(4));
        assertThat(queue.published).containsExactly(stored);

        var trail = orders.findAuditTrail(result.orderId()).collectList().block(WAIT);
        assertThat(trail).hasSize(1);
        assertThat(trail.get(0).from()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(trail.get(0).to()).isEqualTo(TicketStatus.RESERVED);
    }

    @Test
    void execute_purchase_expiryIsNowPlusTenMinutesWithFixedClock() {
        var event = newEvent(10);

        var result = useCase.execute(command(event, 1, key())).block(WAIT);

        assertThat(result.reservationExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        var stored = orders.findById(result.orderId()).block(WAIT);
        assertThat(stored.reservationExpiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(stored.createdAt()).isEqualTo(NOW);
    }

    @Test
    void execute_sameKeyRepeatedSequentially_returnsSameOrderAndReservesOnce() {
        var event = newEvent(50);
        var cmd = command(event, 3, key());

        var first = useCase.execute(cmd).block(WAIT);
        var second = useCase.execute(cmd).block(WAIT);
        var third = useCase.execute(cmd).block(WAIT);

        assertThat(second.orderId()).isEqualTo(first.orderId());
        assertThat(third.orderId()).isEqualTo(first.orderId());
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        var inv = inventory(event);
        assertThat(inv.reserved()).isEqualTo(3);
        assertThat(inv.available()).isEqualTo(47);
        assertThat(inv.version()).isEqualTo(1);
        assertThat(queue.published).hasSize(1);
        assertThat(orders.findAuditTrail(first.orderId()).collectList().block(WAIT)).hasSize(1);
    }

    @Test
    void execute_sameKeyFiftyInParallel_createsOneOrderAndOneReservation() {
        var event = newEvent(50);
        var cmd = command(event, 2, key());

        var results = Flux.range(0, 50)
                .flatMap(i -> useCase.execute(cmd), 50)
                .collectList().block(WAIT);

        assertThat(results).hasSize(50);
        assertThat(results.stream().map(RequestPurchaseResult::orderId).distinct()).hasSize(1);
        assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1);
        var inv = inventory(event);
        assertThat(inv.reserved()).isEqualTo(2);
        assertThat(inv.available()).isEqualTo(48);
        assertThat(inv.version()).isEqualTo(1);
        assertThat(queue.published).hasSize(1);
        assertThat(orders.findAuditTrail(results.get(0).orderId()).collectList().block(WAIT)).hasSize(1);
    }

    @Test
    void execute_sameKeyDifferentPayload_failsAndDoesNotReserveAgain() {
        var event = newEvent(50);
        var key = key();
        useCase.execute(command(event, 3, key)).block(WAIT);

        StepVerifier.create(useCase.execute(command(event, 5, key)))
                .expectError(IdempotencyKeyReusedException.class).verify();
        StepVerifier.create(useCase.execute(command(newEvent(10), 3, key)))
                .expectError(IdempotencyKeyReusedException.class).verify();

        var inv = inventory(event);
        assertThat(inv.reserved()).isEqualTo(3);
        assertThat(inv.version()).isEqualTo(1);
        assertThat(queue.published).hasSize(1);
    }

    @Test
    void execute_sameKeyReplayedWhenSoldOut_stillReturnsExistingOrder() {
        var event = newEvent(3);
        var cmd = command(event, 3, key());
        var first = useCase.execute(cmd).block(WAIT);
        assertThat(inventory(event).available()).isZero();

        var replay = useCase.execute(cmd).block(WAIT);

        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(replay.replayed()).isTrue();
    }

    @Test
    void execute_hundredParallelDistinctKeysOverCapacityThirty_exactlyThirtyOrders() {
        var event = newEvent(30);

        var outcomes = Flux.range(0, 100)
                .flatMap(i -> useCase.execute(command(event, 1, key()))
                        .map(r -> (Object) r)
                        .onErrorResume(e -> Mono.just(e)), 100)
                .collectList().block(WAIT);

        var successes = outcomes.stream().filter(RequestPurchaseResult.class::isInstance)
                .map(RequestPurchaseResult.class::cast).toList();
        var failures = outcomes.stream().filter(Throwable.class::isInstance).map(Throwable.class::cast).toList();
        assertThat(successes).hasSize(30);
        assertThat(failures).hasSize(70).allSatisfy(e -> assertThat(e).isInstanceOf(InsufficientInventoryException.class));
        assertThat(successes.stream().map(RequestPurchaseResult::orderId).distinct()).hasSize(30);
        var inv = inventory(event);
        assertThat(inv.reserved()).isEqualTo(30);
        assertThat(inv.available()).isZero();
        assertInvariant(inv);
        assertThat(queue.published).hasSize(30);
        for (var r : successes) {
            assertThat(orders.findById(r.orderId()).block(WAIT).status()).isEqualTo(TicketStatus.RESERVED);
        }
    }

    @Test
    void execute_quantityLargerThanAvailable_failsWithoutSideEffects() {
        var event = newEvent(5);
        var key = new IdempotencyKey(key());

        StepVerifier.create(useCase.execute(new RequestPurchaseCommand(event, new Quantity(6), key)))
                .expectError(InsufficientInventoryException.class).verify();

        assertThat(inventory(event)).isEqualTo(Inventory.initial(event, 5));
        assertThat(orders.findById(OrderId.fromIdempotencyKey(key)).block(WAIT)).isNull();
        assertThat(queue.published).isEmpty();
    }

    @Test
    void execute_unknownEvent_failsWithEventNotFoundAndCreatesNoOrder() {
        var key = new IdempotencyKey(key());

        StepVerifier.create(useCase.execute(new RequestPurchaseCommand(
                        new EventId("missing-" + key.value()), new Quantity(1), key)))
                .expectError(EventNotFoundException.class).verify();

        assertThat(orders.findById(OrderId.fromIdempotencyKey(key)).block(WAIT)).isNull();
    }

    @Test
    void execute_publishFails_reservationFullyReleasedAndOrderBackToAvailable() {
        var event = newEvent(20);
        var key = new IdempotencyKey(key());
        queue.failing.set(true);
        // The audit sort key is the timestamp, so a clock that moves lets us assert trail order.
        var ticks = new java.util.concurrent.atomic.AtomicLong();
        useCase = new RequestPurchaseUseCase(placement, orders, queue, new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return NOW.plusSeconds(ticks.getAndIncrement());
            }
        }, Duration.ofMinutes(10));

        StepVerifier.create(useCase.execute(new RequestPurchaseCommand(event, new Quantity(5), key)))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(OrderEnqueueFailedException.class);
                    assertThat(((OrderEnqueueFailedException) e).reservationReleased()).isTrue();
                })
                .verify();

        var inv = inventory(event);
        assertThat(inv.available()).isEqualTo(20);
        assertThat(inv.reserved()).isZero();
        assertThat(inv.version()).isEqualTo(2);
        assertInvariant(inv);
        var id = OrderId.fromIdempotencyKey(key);
        assertThat(orders.findById(id).block(WAIT).status()).isEqualTo(TicketStatus.AVAILABLE);
        var trail = orders.findAuditTrail(id).collectList().block(WAIT);
        assertThat(trail).extracting(OrderAuditEntry::to)
                .containsExactly(TicketStatus.RESERVED, TicketStatus.AVAILABLE);
        assertThat(trail.get(1).from()).isEqualTo(TicketStatus.RESERVED);
        assertThat(trail.get(1).reason()).isEqualTo(RequestPurchaseUseCase.PUBLISH_FAILED_REASON);
    }

    @Test
    void releaseReservation_calledTwice_secondConflictsAndCountersUnchanged() {
        var event = newEvent(10);
        var result = useCase.execute(command(event, 4, key())).block(WAIT);
        var order = orders.findById(result.orderId()).block(WAIT);

        placement.releaseReservation(order, TicketStatus.RESERVED, "test", "first", NOW).block(WAIT);
        var afterFirst = inventory(event);
        assertThat(afterFirst.available()).isEqualTo(10);

        StepVerifier.create(placement.releaseReservation(order, TicketStatus.RESERVED, "test", "second", NOW))
                .expectError(OrderStatusConflictException.class).verify();

        assertThat(inventory(event)).isEqualTo(afterFirst);
        assertThat(orders.findAuditTrail(order.id()).collectList().block(WAIT)).hasSize(2);
    }

    @Test
    void releaseReservation_concurrentReleases_exactlyOneWins() {
        var event = newEvent(10);
        var result = useCase.execute(command(event, 4, key())).block(WAIT);
        var order = orders.findById(result.orderId()).block(WAIT);

        var outcomes = Flux.range(0, 10)
                .flatMap(i -> placement.releaseReservation(order, TicketStatus.RESERVED, "test", "race", NOW)
                        .map(entry -> (Object) entry)
                        .onErrorResume(e -> Mono.just(e)), 10)
                .collectList().block(WAIT);

        assertThat(outcomes.stream().filter(OrderAuditEntry.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(OrderStatusConflictException.class::isInstance)).hasSize(9);
        var inv = inventory(event);
        assertThat(inv.available()).isEqualTo(10);
        assertThat(inv.reserved()).isZero();
        assertThat(inv.version()).isEqualTo(2);
    }
}
