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
}
