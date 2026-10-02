package com.ticketflow.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ticketflow.domain.port.EventRepository;
import com.ticketflow.domain.port.InventoryRepository;
import org.junit.jupiter.api.Test;

class UseCaseConfigTest {

    private final UseCaseConfig config = new UseCaseConfig();
    private final EventRepository events = mock(EventRepository.class);
    private final InventoryRepository inventories = mock(InventoryRepository.class);

    @Test
    void beans_areBuilt() {
        var ids = config.idGenerator();
        assertThat(ids.nextEventId()).isNotEqualTo(ids.nextEventId());
        assertThat(config.clock()).isNotNull();
        assertThat(config.createEventUseCase(events, ids, config.clock())).isNotNull();
        assertThat(config.getEventUseCase(events, inventories)).isNotNull();
        assertThat(config.listEventsUseCase(events)).isNotNull();
    }
}
