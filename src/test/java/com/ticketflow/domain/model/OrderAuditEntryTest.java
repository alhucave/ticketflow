package com.ticketflow.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.domain.exception.InvalidStateTransitionException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class OrderAuditEntryTest {

    private static final OrderId OID = new OrderId("o1");
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void constructor_validTransition_createsEntry() {
        OrderAuditEntry entry =
                new OrderAuditEntry(OID, NOW, TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION, "consumer");
        assertThat(entry.actor()).isEqualTo("consumer");
        assertThat(entry.to()).isEqualTo(TicketStatus.PENDING_CONFIRMATION);
    }

    @Test
    void constructor_invalidTransition_throws() {
        assertThatThrownBy(() -> new OrderAuditEntry(OID, NOW, TicketStatus.SOLD, TicketStatus.RESERVED, "a"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void constructor_blankActor_throws(String actor) {
        assertThatThrownBy(() -> new OrderAuditEntry(OID, NOW, TicketStatus.AVAILABLE, TicketStatus.RESERVED, actor))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_nullField_throws() {
        TicketStatus a = TicketStatus.AVAILABLE;
        TicketStatus r = TicketStatus.RESERVED;
        assertThatThrownBy(() -> new OrderAuditEntry(null, NOW, a, r, "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderAuditEntry(OID, null, a, r, "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderAuditEntry(OID, NOW, null, r, "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderAuditEntry(OID, NOW, a, null, "a")).isInstanceOf(IllegalArgumentException.class);
    }
}
