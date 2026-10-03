package com.ticketflow.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.exception.IdempotencyKeyReusedException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.exception.InvalidStateTransitionException;
import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Inventory;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderQueuePublisher;
import com.ticketflow.infrastructure.persistence.DynamoDbEventRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbInventoryRepository;
import com.ticketflow.infrastructure.persistence.DynamoDbOrderFulfillmentRepository;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * C12: complimentary issuance (and its interplay with purchases, the expiry sweep and order processing)
 * over the REAL DynamoDB adapters (DynamoDB Local). Enabled with INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
class IssueComplimentaryUseCaseIT {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000);

    private static DynamoDbAsyncClient client;
    private static DynamoDbEventRepository events;
    private static DynamoDbInventoryRepository inventories;
    private static DynamoDbOrderRepository orders;
    private static DynamoDbOrderPlacementRepository placement;
    private static DynamoDbOrderFulfillmentRepository fulfillment;
    private static IssueComplimentaryUseCase useCase;
    private static RequestPurchaseUseCase purchases;
    private static final List<Order> PUBLISHED = new CopyOnWriteArrayList<>();

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
        fulfillment = new DynamoDbOrderFulfillmentRepository(client);
        useCase = new IssueComplimentaryUseCase(placement, orders, CLOCK);
        OrderQueuePublisher queue = order -> Mono.fromRunnable(() -> PUBLISHED.add(order));
        purchases = new RequestPurchaseUseCase(placement, orders, queue, CLOCK, Duration.ofMinutes(10));
    }

    @AfterAll
    static void stop() {
        client.close();
        DYNAMO.stop();
    }

    private static EventId newEvent(int capacity) {
        var event = new Event(EventId.generate(), "Concert", Instant.parse("2031-01-01T00:00:00Z"), "Arena", capacity);
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

    private static IssueComplimentaryCommand comp(EventId event, int qty, String key, String reason) {
        return new IssueComplimentaryCommand(event, new Quantity(qty), new IdempotencyKey(key), reason);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void execute_issue_movesAvailableToComplimentaryAndSoldStaysZero() {
        var event = newEvent(20);

        var result = useCase.execute(comp(event, 5, key(), "VIP")).block(WAIT);

        assertThat(result.status()).isEqualTo(TicketStatus.COMPLIMENTARY);
        assertThat(result.replayed()).isFalse();
        var inv = inventory(event);
        assertThat(inv.complimentary()).isEqualTo(5);
        assertThat(inv.available()).isEqualTo(15);
        assertThat(inv.sold()).isZero();
        assertThat(inv.reserved()).isZero();
        assertThat(inv.version()).isEqualTo(1);
        assertInvariant(inv);
    }

    @Test
    void execute_issue_storesComplimentaryOrderAndAuditEntryWithActorAndReason() {
        var event = newEvent(20);

        var result = useCase.execute(comp(event, 2, key(), "Press pass <b>x</b>")).block(WAIT);

        var stored = orders.findById(result.orderId()).block(WAIT);
        assertThat(stored.status()).isEqualTo(TicketStatus.COMPLIMENTARY);
        assertThat(stored.quantity()).isEqualTo(new Quantity(2));
        assertThat(stored.createdAt()).isEqualTo(NOW);
        assertThat(stored.reservationExpiresAt()).isEqualTo(NOW);
        var trail = orders.findAuditTrail(result.orderId()).collectList().block(WAIT);
        assertThat(trail).hasSize(1);
        assertThat(trail.get(0).from()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(trail.get(0).to()).isEqualTo(TicketStatus.COMPLIMENTARY);
        assertThat(trail.get(0).actor()).isEqualTo(IssueComplimentaryUseCase.ACTOR);
        assertThat(trail.get(0).reason()).isEqualTo("Press pass <b>x</b>");
        assertThat(trail.get(0).timestamp()).isEqualTo(NOW);
        assertThat(PUBLISHED).noneMatch(o -> o.id().equals(result.orderId()));
    }

    @Test
    void execute_noReason_auditEntryHasNullReason() {
        var event = newEvent(5);
        var result = useCase.execute(comp(event, 1, key(), null)).block(WAIT);

        var trail = orders.findAuditTrail(result.orderId()).collectList().block(WAIT);
        assertThat(trail).hasSize(1);
        assertThat(trail.get(0).reason()).isNull();
    }

    @Test
    void execute_cannotExceedAvailable_capacityTenBuyFourCompSixOkOneMoreFails() {
        var event = newEvent(10);
        purchases.execute(new RequestPurchaseCommand(event, new Quantity(4), new IdempotencyKey(key()))).block(WAIT);

        useCase.execute(comp(event, 6, key(), null)).block(WAIT);
        var afterOk = inventory(event);
        assertThat(afterOk.available()).isZero();
        assertThat(afterOk.complimentary()).isEqualTo(6);
        assertThat(afterOk.reserved()).isEqualTo(4);

        var failedKey = new IdempotencyKey(key());
        StepVerifier.create(useCase.execute(comp(event, 1, failedKey.value(), null)))
                .expectError(InsufficientInventoryException.class).verify();

        assertThat(inventory(event)).isEqualTo(afterOk);
        assertThat(orders.findById(OrderId.complimentaryFromIdempotencyKey(failedKey)).block(WAIT)).isNull();
        assertInvariant(inventory(event));
    }

    @Test
    void execute_unknownEvent_failsAndCreatesNothing() {
        var k = new IdempotencyKey(key());

        StepVerifier.create(useCase.execute(new IssueComplimentaryCommand(
                        new EventId("missing-" + k.value()), new Quantity(1), k, null)))
                .expectError(EventNotFoundException.class).verify();

        assertThat(orders.findById(OrderId.complimentaryFromIdempotencyKey(k)).block(WAIT)).isNull();
    }

    @Test
    void execute_sameKeySequentially_issuesOnce() {
        var event = newEvent(20);
        var cmd = comp(event, 3, key(), "r");

        var first = useCase.execute(cmd).block(WAIT);
        var second = useCase.execute(cmd).block(WAIT);

        assertThat(second.orderId()).isEqualTo(first.orderId());
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        var inv = inventory(event);
        assertThat(inv.complimentary()).isEqualTo(3);
        assertThat(inv.version()).isEqualTo(1);
        assertThat(orders.findAuditTrail(first.orderId()).collectList().block(WAIT)).hasSize(1);
    }

    @Test
    void execute_sameKeyTwentyInParallel_issuesExactlyOnce() {
        var event = newEvent(50);
        var cmd = comp(event, 2, key(), "burst");

        var results = Flux.range(0, 20).flatMap(i -> useCase.execute(cmd), 20).collectList().block(WAIT);

        assertThat(results).hasSize(20);
        assertThat(results.stream().map(IssueComplimentaryResult::orderId).distinct()).hasSize(1);
        assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1);
        var inv = inventory(event);
        assertThat(inv.complimentary()).isEqualTo(2);
        assertThat(inv.available()).isEqualTo(48);
        assertThat(inv.version()).isEqualTo(1);
        assertThat(orders.findAuditTrail(results.get(0).orderId()).collectList().block(WAIT)).hasSize(1);
    }

    @Test
    void execute_sameKeyDifferentPayload_failsAndChangesNothing() {
        var event = newEvent(50);
        var k = key();
        useCase.execute(comp(event, 3, k, "a")).block(WAIT);
        var before = inventory(event);

        StepVerifier.create(useCase.execute(comp(event, 4, k, "a")))
                .expectError(IdempotencyKeyReusedException.class).verify();
        StepVerifier.create(useCase.execute(comp(event, 3, k, "different reason")))
                .expectError(IdempotencyKeyReusedException.class).verify();
        StepVerifier.create(useCase.execute(comp(event, 3, k, null)))
                .expectError(IdempotencyKeyReusedException.class).verify();
        StepVerifier.create(useCase.execute(comp(newEvent(50), 3, k, "a")))
                .expectError(IdempotencyKeyReusedException.class).verify();

        assertThat(inventory(event)).isEqualTo(before);
    }

    @Test
    void execute_sameKeyAsAPurchase_doesNotCollide() {
        var event = newEvent(20);
        var k = key();

        var purchase = purchases.execute(new RequestPurchaseCommand(event, new Quantity(2), new IdempotencyKey(k)))
                .block(WAIT);
        var complimentary = useCase.execute(comp(event, 2, k, null)).block(WAIT);

        assertThat(complimentary.orderId()).isNotEqualTo(purchase.orderId());
        assertThat(complimentary.replayed()).isFalse();
        var inv = inventory(event);
        assertThat(inv.reserved()).isEqualTo(2);
        assertThat(inv.complimentary()).isEqualTo(2);
        assertInvariant(inv);
    }

    @Test
    void execute_concurrentComplimentaryAndPurchasesOverLimitedCapacity_neverOversell() {
        int capacity = 20;
        var event = newEvent(capacity);

        var outcomes = Flux.range(0, 60)
                .flatMap(i -> (i % 2 == 0
                        ? useCase.execute(comp(event, 1, key(), null)).map(r -> (Object) r)
                        : purchases.execute(new RequestPurchaseCommand(
                                event, new Quantity(1), new IdempotencyKey(key()))).map(r -> (Object) r))
                        .onErrorResume(e -> Mono.just(e)), 60)
                .collectList().block(WAIT);

        long compOk = outcomes.stream().filter(IssueComplimentaryResult.class::isInstance).count();
        long buyOk = outcomes.stream().filter(RequestPurchaseResult.class::isInstance).count();
        var failures = outcomes.stream().filter(Throwable.class::isInstance).map(Throwable.class::cast).toList();
        assertThat(compOk + buyOk).isEqualTo(capacity);
        assertThat(failures).hasSize(60 - capacity)
                .allSatisfy(e -> assertThat(e).isInstanceOf(InsufficientInventoryException.class));
        var inv = inventory(event);
        assertThat(inv.available()).isZero();
        assertThat(inv.complimentary()).isEqualTo((int) compOk);
        assertThat(inv.reserved()).isEqualTo((int) buyOk);
        assertThat(inv.sold()).isZero();
        assertInvariant(inv);
    }

    @Test
    void sweepAndProcessing_neverTouchComplimentaryOrders() {
        var event = newEvent(10);
        var result = useCase.execute(comp(event, 4, key(), null)).block(WAIT);
        var before = inventory(event);
        var farFuture = Clock.fixed(NOW.plus(Duration.ofDays(365)), ZoneOffset.UTC);

        assertThat(orders.findExpiredReservations(farFuture.instant()).collectList().block(WAIT))
                .noneMatch(o -> o.id().equals(result.orderId()));
        var sweep = new ReleaseExpiredReservationsUseCase(orders, placement, farFuture, 4, 100);
        sweep.execute().block(WAIT);
        var processed = new ProcessOrderUseCase(orders, fulfillment, placement, farFuture)
                .execute(result.orderId()).block(WAIT);

        assertThat(processed).isEqualTo(new ProcessOrderResult.AlreadyProcessed(result.orderId(), TicketStatus.COMPLIMENTARY));
        assertThat(orders.findById(result.orderId()).block(WAIT).status()).isEqualTo(TicketStatus.COMPLIMENTARY);
        assertThat(inventory(event)).isEqualTo(before);
        assertThat(orders.findAuditTrail(result.orderId()).collectList().block(WAIT)).hasSize(1);
    }

    @Test
    void releaseReservation_onComplimentaryOrder_isRejectedAndCountersUntouched() {
        var event = newEvent(10);
        var result = useCase.execute(comp(event, 4, key(), null)).block(WAIT);
        var order = orders.findById(result.orderId()).block(WAIT);
        var before = inventory(event);

        for (TicketStatus expected : TicketStatus.values()) {
            StepVerifier.create(placement.releaseReservation(order, expected, "test", "nope", NOW))
                    .expectErrorSatisfies(e -> assertThat(e).isInstanceOfAny(
                            InvalidStateTransitionException.class, IllegalArgumentException.class,
                            com.ticketflow.domain.exception.OrderStatusConflictException.class))
                    .verify();
        }

        assertThat(inventory(event)).isEqualTo(before);
        assertThat(orders.findById(order.id()).block(WAIT).status()).isEqualTo(TicketStatus.COMPLIMENTARY);
    }
}
