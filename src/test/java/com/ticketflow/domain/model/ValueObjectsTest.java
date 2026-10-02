package com.ticketflow.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ValueObjectsTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void eventId_blank_throws(String value) {
        assertThatThrownBy(() -> new EventId(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void orderId_blank_throws(String value) {
        assertThatThrownBy(() -> new OrderId(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generate_called_returnsDistinctValidIds() {
        assertThat(EventId.generate()).isNotEqualTo(EventId.generate());
        assertThat(OrderId.generate()).isNotEqualTo(OrderId.generate());
        assertThat(EventId.generate().value()).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void quantity_nonPositive_throws(int value) {
        assertThatThrownBy(() -> new Quantity(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quantity_positive_keepsValue() {
        assertThat(new Quantity(3).value()).isEqualTo(3);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void idempotencyKey_blank_throws(String value) {
        assertThatThrownBy(() -> new IdempotencyKey(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idempotencyKey_tooLong_throws() {
        String tooLong = "k".repeat(IdempotencyKey.MAX_LENGTH + 1);
        assertThatThrownBy(() -> new IdempotencyKey(tooLong)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new IdempotencyKey("k".repeat(IdempotencyKey.MAX_LENGTH)).value()).hasSize(128);
    }
}
