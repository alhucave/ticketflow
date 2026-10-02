package com.ticketflow.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InventoryTest {

    private static final EventId ID = new EventId("e1");

    @Test
    void constructor_countersSumToCapacity_createsInventory() {
        Inventory inv = new Inventory(ID, 50, 10, 5, 30, 5, 100, 7);
        assertThat(inv.version()).isEqualTo(7);
        assertThat(inv.capacity()).isEqualTo(100);
    }

    @Test
    void constructor_sumBelowCapacity_throws() {
        assertThatThrownBy(() -> new Inventory(ID, 50, 10, 5, 30, 4, 100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }

    @Test
    void constructor_sumAboveCapacity_throws() {
        assertThatThrownBy(() -> new Inventory(ID, 50, 10, 5, 30, 6, 100, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_overflowingCountersDoNotWrapToCapacity_throws() {
        assertThatThrownBy(() -> new Inventory(ID, Integer.MAX_VALUE, Integer.MAX_VALUE, 2, 0, 0, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_negativeCounter_throwsForEachField() {
        assertThatThrownBy(() -> new Inventory(ID, -1, 101, 0, 0, 0, 100, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("available");
        assertThatThrownBy(() -> new Inventory(ID, 101, -1, 0, 0, 0, 100, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reserved");
        assertThatThrownBy(() -> new Inventory(ID, 101, 0, -1, 0, 0, 100, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pendingConfirmation");
        assertThatThrownBy(() -> new Inventory(ID, 101, 0, 0, -1, 0, 100, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sold");
        assertThatThrownBy(() -> new Inventory(ID, 101, 0, 0, 0, -1, 100, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("complimentary");
    }

    @Test
    void constructor_nonPositiveCapacity_throws() {
        assertThatThrownBy(() -> new Inventory(ID, 0, 0, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_negativeVersionOrNullId_throws() {
        assertThatThrownBy(() -> new Inventory(ID, 10, 0, 0, 0, 0, 10, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Inventory(null, 10, 0, 0, 0, 0, 10, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void initial_capacity_allAvailableVersionZero() {
        Inventory inv = Inventory.initial(ID, 25);
        assertThat(inv).isEqualTo(new Inventory(ID, 25, 0, 0, 0, 0, 25, 0));
    }
}
