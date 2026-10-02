package com.ticketflow.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.domain.exception.InvalidStateTransitionException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final OrderId OID = new OrderId("o1");
    private static final EventId EID = new EventId("e1");
    private static final IdempotencyKey KEY = new IdempotencyKey("k1");

    private static Order order(TicketStatus status) {
        return new Order(OID, EID, new Quantity(2), status, KEY, NOW.plusSeconds(600), NOW);
    }

    @Test
    void constructor_validArguments_createsOrder() {
        Order order = order(TicketStatus.RESERVED);
        assertThat(order.quantity().value()).isEqualTo(2);
        assertThat(order.status()).isEqualTo(TicketStatus.RESERVED);
    }

    @Test
    void constructor_nullField_throwsForEachField() {
        Instant exp = NOW.plusSeconds(1);
        Quantity q = new Quantity(1);
        TicketStatus s = TicketStatus.RESERVED;
        assertThatThrownBy(() -> new Order(null, EID, q, s, KEY, exp, NOW)).hasMessageContaining("id");
        assertThatThrownBy(() -> new Order(OID, null, q, s, KEY, exp, NOW)).hasMessageContaining("eventId");
        assertThatThrownBy(() -> new Order(OID, EID, null, s, KEY, exp, NOW)).hasMessageContaining("quantity");
        assertThatThrownBy(() -> new Order(OID, EID, q, null, KEY, exp, NOW)).hasMessageContaining("status");
        assertThatThrownBy(() -> new Order(OID, EID, q, s, null, exp, NOW)).hasMessageContaining("idempotencyKey");
        assertThatThrownBy(() -> new Order(OID, EID, q, s, KEY, null, NOW))
                .hasMessageContaining("reservationExpiresAt");
        assertThatThrownBy(() -> new Order(OID, EID, q, s, KEY, exp, null)).hasMessageContaining("createdAt");
    }

    @Test
    void constructor_expiryNotAfterCreation_throws() {
        assertThatThrownBy(() -> new Order(OID, EID, new Quantity(1), TicketStatus.RESERVED, KEY, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transitionTo_validTransition_returnsCopyWithNewStatus() {
        Order original = order(TicketStatus.RESERVED);
        Order moved = original.transitionTo(TicketStatus.PENDING_CONFIRMATION);
        assertThat(moved.status()).isEqualTo(TicketStatus.PENDING_CONFIRMATION);
        assertThat(original.status()).isEqualTo(TicketStatus.RESERVED);
        assertThat(moved.id()).isEqualTo(original.id());
    }

    @Test
    void transitionTo_invalidTransition_throws() {
        assertThatThrownBy(() -> order(TicketStatus.SOLD).transitionTo(TicketStatus.AVAILABLE))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
