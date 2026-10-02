package com.ticketflow.usecase;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Inventory;
import com.ticketflow.domain.port.EventRepository;
import com.ticketflow.domain.port.InventoryRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GetAndListEventsUseCaseTest {

    private static final EventId ID = new EventId("evt-1");
    private static final Event EVENT = new Event(ID, "Rock", Instant.parse("2030-01-01T00:00:00Z"), "Arena", 10);

    private final EventRepository events = mock(EventRepository.class);
    private final InventoryRepository inventories = mock(InventoryRepository.class);

    @Test
    void getEvent_existing_returnsEventWithInventorySnapshot() {
        Inventory inventory = new Inventory(ID, 7, 1, 0, 2, 0, 10, 3);
        when(events.findById(ID)).thenReturn(Mono.just(EVENT));
        when(inventories.findByEventId(ID)).thenReturn(Mono.just(inventory));

        StepVerifier.create(new GetEventUseCase(events, inventories).execute(ID))
                .expectNext(new EventDetails(EVENT, inventory))
                .verifyComplete();
    }

    @Test
    void getEvent_unknownId_failsWithEventNotFound() {
        when(events.findById(ID)).thenReturn(Mono.empty());

        StepVerifier.create(new GetEventUseCase(events, inventories).execute(ID))
                .expectError(EventNotFoundException.class)
                .verify();
    }

    @Test
    void getEvent_missingInventory_failsWithEventNotFound() {
        when(events.findById(ID)).thenReturn(Mono.just(EVENT));
        when(inventories.findByEventId(ID)).thenReturn(Mono.empty());

        StepVerifier.create(new GetEventUseCase(events, inventories).execute(ID))
                .expectError(EventNotFoundException.class)
                .verify();
    }

    @Test
    void listEvents_streamsAllEvents() {
        Event other = new Event(new EventId("evt-2"), "Jazz", EVENT.startsAt(), "Hall", 5);
        when(events.findAll()).thenReturn(Flux.just(EVENT, other));

        StepVerifier.create(new ListEventsUseCase(events).execute())
                .expectNext(EVENT, other)
                .verifyComplete();
    }

    @Test
    void listEvents_noEvents_completesEmpty() {
        when(events.findAll()).thenReturn(Flux.empty());

        StepVerifier.create(new ListEventsUseCase(events).execute()).verifyComplete();
    }
}
