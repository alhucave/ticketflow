package com.ticketflow.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import org.junit.jupiter.api.Test;

class DomainExceptionsTest {

    private static final EventId EID = new EventId("e1");
    private static final OrderId OID = new OrderId("o1");

    @Test
    void eventNotFound_created_exposesIdAndMessage() {
        var ex = new EventNotFoundException(EID);
        assertThat(ex.eventId()).isEqualTo(EID);
        assertThat(ex).hasMessageContaining("e1");
    }

    @Test
    void orderNotFound_created_exposesIdAndMessage() {
        var ex = new OrderNotFoundException(OID);
        assertThat(ex.orderId()).isEqualTo(OID);
        assertThat(ex).hasMessageContaining("o1");
    }

    @Test
    void reservationExpired_created_exposesIdAndMessage() {
        var ex = new ReservationExpiredException(OID);
        assertThat(ex.orderId()).isEqualTo(OID);
        assertThat(ex).hasMessageContaining("o1");
    }

    @Test
    void insufficientInventory_created_exposesDetails() {
        var ex = new InsufficientInventoryException(EID, new Quantity(4));
        assertThat(ex.eventId()).isEqualTo(EID);
        assertThat(ex.requested()).isEqualTo(new Quantity(4));
        assertThat(ex).hasMessageContaining("e1").hasMessageContaining("4");
    }

    @Test
    void concurrentInventoryModification_created_exposesDetails() {
        var ex = new ConcurrentInventoryModificationException(EID, 9L);
        assertThat(ex.eventId()).isEqualTo(EID);
        assertThat(ex.expectedVersion()).isEqualTo(9L);
        assertThat(ex).hasMessageContaining("9");
    }

    @Test
    void orderAlreadyExists_created_exposesIdAndMessage() {
        var ex = new OrderAlreadyExistsException(OID);
        assertThat(ex.orderId()).isEqualTo(OID);
        assertThat(ex).hasMessageContaining("o1");
    }

    @Test
    void orderStatusConflict_created_exposesDetails() {
        var ex = new OrderStatusConflictException(OID,
                com.ticketflow.domain.model.TicketStatus.RESERVED, com.ticketflow.domain.model.TicketStatus.SOLD);
        assertThat(ex.orderId()).isEqualTo(OID);
        assertThat(ex.expected()).isEqualTo(com.ticketflow.domain.model.TicketStatus.RESERVED);
        assertThat(ex.actual()).isEqualTo(com.ticketflow.domain.model.TicketStatus.SOLD);
        assertThat(ex).hasMessageContaining("RESERVED").hasMessageContaining("SOLD");
    }

    @Test
    void idempotencyKeyReused_created_exposesDetails() {
        var key = new com.ticketflow.domain.model.IdempotencyKey("k");
        var ex = new IdempotencyKeyReusedException(key, OID);
        assertThat(ex.key()).isEqualTo(key);
        assertThat(ex.orderId()).isEqualTo(OID);
        assertThat(ex).hasMessageContaining("o1");
    }

    @Test
    void idempotentOrderNotActive_created_exposesDetails() {
        var key = new com.ticketflow.domain.model.IdempotencyKey("k");
        var ex = new IdempotentOrderNotActiveException(key, OID);
        assertThat(ex.key()).isEqualTo(key);
        assertThat(ex.orderId()).isEqualTo(OID);
        assertThat(ex).hasMessageContaining("o1").hasMessageContaining("new key");
    }

    @Test
    void orderEnqueueFailed_created_exposesDetails() {
        var cause = new RuntimeException("x");
        var ex = new OrderEnqueueFailedException(OID, false, cause);
        assertThat(ex.orderId()).isEqualTo(OID);
        assertThat(ex.reservationReleased()).isFalse();
        assertThat(ex).hasCause(cause).hasMessageContaining("NOT released");
        assertThat(new OrderEnqueueFailedException(OID, true, cause)).hasMessageContaining("released");
    }
}
