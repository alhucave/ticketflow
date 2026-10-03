package com.ticketflow.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.infrastructure.persistence.DynamoDbTables;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

/**
 * The reconciliation check every concurrency scenario runs once the system is quiescent. It cross-checks the
 * {@code inventory} table against the {@code orders} table and the {@code order_audit} table, reading them straight
 * from DynamoDB (strongly consistent, never through the application under test):
 *
 * <ol>
 *   <li><b>Inventory invariant</b>: {@code available + reserved + pendingConfirmation + sold + complimentary =
 *       capacity} and no counter is negative.</li>
 *   <li><b>Counters recomputed from orders</b>: scan of the orders of the event, summing quantities per status;
 *       {@code reserved}, {@code pendingConfirmation}, {@code sold} and {@code complimentary} must equal those sums
 *       and {@code available = capacity - sums}. An order in status AVAILABLE (released) holds nothing.</li>
 *   <li><b>Audit chains</b>: the audit entries of each order form a valid transition chain that starts at its
 *       creation entry ({@code AVAILABLE -> RESERVED} or {@code AVAILABLE -> COMPLIMENTARY}), follows
 *       {@code from -> to} links with no fork and no orphan entry, uses only transitions allowed by
 *       {@link TicketStatus#canTransitionTo}, and its last {@code to} equals the order's current status.</li>
 *   <li><b>No accepted order is lost</b>: every order id the caller was told about (202/201) exists in the table
 *       (and, with {@code exactly}, nothing else does: rejected requests left no order behind).</li>
 * </ol>
 *
 * <p><b>Known limitation (audit order).</b> The audit sort key is {@code <timestamp>#<uuid>}: two entries written in
 * the same instant sort randomly against each other, and clocks of different nodes can skew. So this check NEVER
 * relies on the stored (timestamp) order: it follows the {@code from -> to} links instead, which are exact regardless
 * of timestamps. Only the existence and shape of the chain are asserted, not the wall-clock order of its entries.
 *
 * <p>The check is only meaningful at quiescence (no purchase, consumer step or sweep in flight): the three tables are
 * read one after the other, not in one snapshot.
 */
public final class Reconciliation {

    /** One order row plus its audit entries, as stored. */
    public record OrderRow(String orderId, int quantity, TicketStatus status, List<Audit> audit) { }

    /** One audit entry as stored. */
    public record Audit(TicketStatus from, TicketStatus to, String actor, String reason) { }

    /** What the check read. */
    public record Report(Map<String, Integer> counters, List<OrderRow> orders) {

        public OrderRow order(String orderId) {
            return orders.stream().filter(o -> o.orderId().equals(orderId)).findFirst().orElseThrow();
        }

        public int quantityIn(TicketStatus status) {
            return orders.stream().filter(o -> o.status() == status).mapToInt(OrderRow::quantity).sum();
        }

        public long countIn(TicketStatus status) {
            return orders.stream().filter(o -> o.status() == status).count();
        }
    }

    private static final List<String> COUNTERS =
            List.of("available", "reserved", "pendingConfirmation", "sold", "complimentary");

    private final DynamoDbAsyncClient dynamo;

    public Reconciliation(DynamoDbAsyncClient dynamo) {
        this.dynamo = dynamo;
    }

    /** Runs checks 1-3. */
    public Report check(String eventId) {
        return check(eventId, Set.of(), false);
    }

    /**
     * Runs checks 1-3 plus: every id in {@code accepted} exists as an order and, when {@code exactly}, the orders of
     * the event are exactly those ids.
     */
    public Report check(String eventId, Set<String> accepted, boolean exactly) {
        Map<String, Integer> counters = readCounters(eventId);
        int capacity = counters.get("capacity");

        // 1. inventory invariant
        counters.forEach((name, value) -> assertThat(value).as("counter %s of %s", name, eventId).isNotNegative());
        int total = COUNTERS.stream().mapToInt(counters::get).sum();
        assertThat(total).as("available+reserved+pendingConfirmation+sold+complimentary of %s = capacity", eventId)
                .isEqualTo(capacity);

        // 2. counters recomputed from the orders table
        List<OrderRow> orders = readOrders(eventId);
        Map<TicketStatus, Integer> sums = new EnumMap<>(TicketStatus.class);
        orders.forEach(o -> sums.merge(o.status(), o.quantity(), Integer::sum));
        int reserved = sums.getOrDefault(TicketStatus.RESERVED, 0);
        int pending = sums.getOrDefault(TicketStatus.PENDING_CONFIRMATION, 0);
        int sold = sums.getOrDefault(TicketStatus.SOLD, 0);
        int complimentary = sums.getOrDefault(TicketStatus.COMPLIMENTARY, 0);
        assertThat(counters.get("reserved")).as("reserved of %s vs sum(RESERVED orders)", eventId).isEqualTo(reserved);
        assertThat(counters.get("pendingConfirmation")).as("pendingConfirmation of %s vs sum(PENDING_CONFIRMATION)",
                eventId).isEqualTo(pending);
        assertThat(counters.get("sold")).as("sold of %s vs sum(SOLD orders)", eventId).isEqualTo(sold);
        assertThat(counters.get("complimentary")).as("complimentary of %s vs sum(COMPLIMENTARY orders)", eventId)
                .isEqualTo(complimentary);
        assertThat(counters.get("available")).as("available of %s vs capacity - held", eventId)
                .isEqualTo(capacity - reserved - pending - sold - complimentary);

        // 3. audit chains
        orders.forEach(Reconciliation::assertValidChain);

        // 4. no accepted order lost (and, optionally, nothing else created)
        Set<String> ids = new HashSet<>();
        orders.forEach(o -> ids.add(o.orderId()));
        assertThat(ids).as("every accepted order of %s exists", eventId).containsAll(accepted);
        if (exactly) {
            assertThat(ids).as("orders of %s are exactly the accepted ones", eventId).isEqualTo(accepted);
        }
        return new Report(counters, orders);
    }

    /**
     * Valid chain regardless of timestamps: exactly one creation entry, then follow from -> to links; no forks, no
     * orphans, only allowed transitions, ends at the order's current status.
     */
    static void assertValidChain(OrderRow order) {
        List<Audit> entries = order.audit();
        assertThat(entries).as("audit trail of %s", order.orderId()).isNotEmpty();
        entries.forEach(e -> assertThat(e.from().canTransitionTo(e.to()))
                .as("%s: %s -> %s is a valid transition", order.orderId(), e.from(), e.to()).isTrue());
        List<Audit> creations = entries.stream().filter(e -> e.from() == TicketStatus.AVAILABLE
                && (e.to() == TicketStatus.RESERVED || e.to() == TicketStatus.COMPLIMENTARY)).toList();
        assertThat(creations).as("exactly one creation entry of %s: %s", order.orderId(), entries).hasSize(1);

        List<Audit> unused = new ArrayList<>(entries);
        Audit current = creations.get(0);
        unused.remove(current);
        TicketStatus last = current.to();
        while (true) {
            TicketStatus from = last;
            List<Audit> next = unused.stream().filter(e -> e.from() == from).toList();
            assertThat(next.size()).as("no fork after %s in %s: %s", from, order.orderId(), entries)
                    .isLessThanOrEqualTo(1);
            if (next.isEmpty()) {
                break;
            }
            unused.remove(next.get(0));
            last = next.get(0).to();
        }
        assertThat(unused).as("every audit entry of %s is part of the chain", order.orderId()).isEmpty();
        assertThat(last).as("last audit state of %s equals its status", order.orderId()).isEqualTo(order.status());
    }

    private Map<String, Integer> readCounters(String eventId) {
        var item = dynamo.getItem(GetItemRequest.builder().tableName(DynamoDbTables.INVENTORY).consistentRead(true)
                .key(Map.of("eventId", s(eventId))).build()).join().item();
        assertThat(item).as("inventory of %s", eventId).isNotEmpty();
        Map<String, Integer> counters = new java.util.HashMap<>();
        for (String name : List.of("available", "reserved", "pendingConfirmation", "sold", "complimentary",
                "capacity")) {
            counters.put(name, Integer.parseInt(item.get(name).n()));
        }
        return counters;
    }

    private List<OrderRow> readOrders(String eventId) {
        List<OrderRow> rows = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            var response = dynamo.scan(ScanRequest.builder().tableName(DynamoDbTables.ORDERS).consistentRead(true)
                    .filterExpression("eventId = :e").expressionAttributeValues(Map.of(":e", s(eventId)))
                    .exclusiveStartKey(startKey).build()).join();
            for (var item : response.items()) {
                String orderId = item.get("orderId").s();
                rows.add(new OrderRow(orderId, Integer.parseInt(item.get("quantity").n()),
                        TicketStatus.valueOf(item.get("status").s()), readAudit(orderId)));
            }
            startKey = response.hasLastEvaluatedKey() ? response.lastEvaluatedKey() : null;
        } while (startKey != null);
        return rows;
    }

    private List<Audit> readAudit(String orderId) {
        List<Audit> entries = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            var response = dynamo.query(QueryRequest.builder().tableName(DynamoDbTables.ORDER_AUDIT)
                    .consistentRead(true).keyConditionExpression("orderId = :o")
                    .expressionAttributeValues(Map.of(":o", s(orderId))).exclusiveStartKey(startKey).build()).join();
            for (var item : response.items()) {
                entries.add(new Audit(TicketStatus.valueOf(item.get("from").s()),
                        TicketStatus.valueOf(item.get("to").s()), item.get("actor").s(),
                        item.containsKey("reason") ? item.get("reason").s() : null));
            }
            startKey = response.hasLastEvaluatedKey() ? response.lastEvaluatedKey() : null;
        } while (startKey != null);
        return entries;
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
