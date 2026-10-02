package com.ticketflow.domain.model;

import static com.ticketflow.domain.model.TicketStatus.AVAILABLE;
import static com.ticketflow.domain.model.TicketStatus.COMPLIMENTARY;
import static com.ticketflow.domain.model.TicketStatus.PENDING_CONFIRMATION;
import static com.ticketflow.domain.model.TicketStatus.RESERVED;
import static com.ticketflow.domain.model.TicketStatus.SOLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ticketflow.domain.exception.InvalidStateTransitionException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class TicketStatusTest {

    private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED = Map.of(
            AVAILABLE, EnumSet.of(RESERVED, COMPLIMENTARY),
            RESERVED, EnumSet.of(PENDING_CONFIRMATION, AVAILABLE),
            PENDING_CONFIRMATION, EnumSet.of(SOLD, AVAILABLE),
            SOLD, EnumSet.noneOf(TicketStatus.class),
            COMPLIMENTARY, EnumSet.noneOf(TicketStatus.class));

    static Stream<Arguments> fullMatrix() {
        return Stream.of(TicketStatus.values())
                .flatMap(from -> Stream.of(TicketStatus.values())
                        .map(to -> Arguments.of(from, to, ALLOWED.get(from).contains(to))));
    }

    @Test
    void matrix_covers25Pairs() {
        assertEquals(25, fullMatrix().count());
    }

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @MethodSource("fullMatrix")
    void canTransitionTo_fullMatrix_matchesArchitectureRules(TicketStatus from, TicketStatus to, boolean allowed) {
        assertEquals(allowed, from.canTransitionTo(to));
    }

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @MethodSource("fullMatrix")
    void transitionTo_fullMatrix_returnsTargetOrThrows(TicketStatus from, TicketStatus to, boolean allowed) {
        if (allowed) {
            assertEquals(to, from.transitionTo(to));
        } else {
            var ex = assertThrows(InvalidStateTransitionException.class, () -> from.transitionTo(to));
            assertEquals(from, ex.from());
            assertEquals(to, ex.to());
            assertTrue(ex.getMessage().contains(from + " -> " + to));
        }
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void isFinal_eachStatus_onlySoldAndComplimentary(TicketStatus status) {
        assertEquals(status == SOLD || status == COMPLIMENTARY, status.isFinal());
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"SOLD", "COMPLIMENTARY"})
    void finalStatus_anyTarget_hasNoOutgoingTransitions(TicketStatus status) {
        for (TicketStatus target : TicketStatus.values()) {
            assertFalse(status.canTransitionTo(target));
        }
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void isSale_eachStatus_onlySold(TicketStatus status) {
        assertEquals(status == SOLD, status.isSale());
    }

    @Test
    void isSale_reservedPendingAndComplimentary_areNotSales() {
        assertFalse(RESERVED.isSale());
        assertFalse(PENDING_CONFIRMATION.isSale());
        assertFalse(COMPLIMENTARY.isSale());
    }
}
