package com.ticketflow.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EventTest {

    private static final EventId ID = new EventId("e1");
    private static final Instant WHEN = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void constructor_validArguments_createsEvent() {
        Event event = new Event(ID, "Concert", WHEN, 100);
        assertThat(event.name()).isEqualTo("Concert");
        assertThat(event.capacity()).isEqualTo(100);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void constructor_blankName_throws(String name) {
        assertThatThrownBy(() -> new Event(ID, name, WHEN, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5})
    void constructor_nonPositiveCapacity_throws(int capacity) {
        assertThatThrownBy(() -> new Event(ID, "Concert", WHEN, capacity))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_nullIdOrDate_throws() {
        assertThatThrownBy(() -> new Event(null, "Concert", WHEN, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Event(ID, "Concert", null, 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
