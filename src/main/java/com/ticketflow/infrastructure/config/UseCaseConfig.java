package com.ticketflow.infrastructure.config;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.port.EventRepository;
import com.ticketflow.domain.port.IdGenerator;
import com.ticketflow.domain.port.InventoryRepository;
import com.ticketflow.usecase.CreateEventUseCase;
import com.ticketflow.usecase.GetEventUseCase;
import com.ticketflow.usecase.ListEventsUseCase;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free use cases and their infrastructure helpers. */
@Configuration
public class UseCaseConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IdGenerator idGenerator() {
        return EventId::generate;
    }

    @Bean
    CreateEventUseCase createEventUseCase(
            EventRepository events, InventoryRepository inventories, IdGenerator ids, Clock clock) {
        return new CreateEventUseCase(events, inventories, ids, clock);
    }

    @Bean
    GetEventUseCase getEventUseCase(EventRepository events, InventoryRepository inventories) {
        return new GetEventUseCase(events, inventories);
    }

    @Bean
    ListEventsUseCase listEventsUseCase(EventRepository events) {
        return new ListEventsUseCase(events);
    }
}
