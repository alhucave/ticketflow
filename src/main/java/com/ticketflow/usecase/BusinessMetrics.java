package com.ticketflow.usecase;

/**
 * Outbound port for the business metrics of the use cases (orders created, sold, released, conflicts...).
 * Framework-free: the Micrometer implementation lives in {@code infrastructure.observability}. Use cases
 * receive {@link #NOOP} by default so they stay testable without a metrics registry.
 *
 * <p><b>Cardinality rule.</b> Every tag value comes from one of the enums below (a fixed, small set); no
 * method accepts an order id, event id, idempotency key, client address or any other user input, so a metric
 * can never create unbounded series.
 */
public interface BusinessMetrics {

    BusinessMetrics NOOP = new BusinessMetrics() { };

    /** Why a purchase request was refused. */
    enum PurchaseRejection {
        INSUFFICIENT_INVENTORY("insufficient_inventory"),
        IDEMPOTENCY_KEY_REUSED("idempotency_key_reused"),
        ORDER_NOT_ACTIVE("order_not_active"),
        /** The queue publish failed after the reservation (the reservation is compensated). */
        ENQUEUE_FAILED("enqueue_failed");

        private final String tag;

        PurchaseRejection(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /** Why a reservation went back to AVAILABLE. */
    enum ReleaseReason {
        EXPIRED("expired"),
        PUBLISH_FAILED("publish_failed");

        private final String tag;

        ReleaseReason(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /** Terminal outcome of processing one order message ({@link ProcessOrderResult}). */
    enum ProcessOutcome {
        SOLD("sold"),
        RELEASED_AS_EXPIRED("released_as_expired"),
        ALREADY_PROCESSED("already_processed"),
        ORDER_MISSING("order_missing");

        private final String tag;

        ProcessOutcome(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }

        static ProcessOutcome of(ProcessOrderResult result) {
            return switch (result) {
                case ProcessOrderResult.Sold sold -> SOLD;
                case ProcessOrderResult.ReleasedAsExpired released -> RELEASED_AS_EXPIRED;
                case ProcessOrderResult.AlreadyProcessed processed -> ALREADY_PROCESSED;
                case ProcessOrderResult.OrderMissing missing -> ORDER_MISSING;
            };
        }
    }

    /** Kind of conditional-write failure observed (the guard that said no). */
    enum ConflictType {
        /** {@code available >= qty} (or the equivalent source counter) did not hold. */
        INVENTORY_INSUFFICIENT("inventory_insufficient"),
        /** The order was no longer in the expected status. */
        ORDER_STATUS("order_status");

        private final String tag;

        ConflictType(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /** Operation that hit the conflict. */
    enum Operation {
        PURCHASE("purchase"),
        COMPLIMENTARY("complimentary"),
        PROCESS_ORDER("process_order"),
        EXPIRATION_SWEEP("expiration_sweep");

        private final String tag;

        Operation(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /** A new order was placed: its reservation was made (not a replay). */
    default void orderPlaced() { }

    /** A purchase request hit an existing order of the same idempotency key and payload. */
    default void purchaseReplayed() { }

    default void purchaseRejected(PurchaseRejection reason) { }

    /** This invocation completed a sale. */
    default void orderSold() { }

    /** A reservation went back to AVAILABLE. */
    default void orderReleased(ReleaseReason reason) { }

    default void complimentaryIssued() { }

    default void orderProcessed(ProcessOutcome outcome) { }

    /** A conditional write was refused (benign contention or a guard that protected the inventory). */
    default void conflict(ConflictType type, Operation operation) { }
}
