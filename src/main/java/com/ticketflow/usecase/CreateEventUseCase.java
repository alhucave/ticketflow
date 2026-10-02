package com.ticketflow.usecase;

import com.ticketflow.domain.exception.InvalidEventException;
import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Inventory;
import com.ticketflow.domain.port.EventRepository;
import com.ticketflow.domain.port.IdGenerator;
import com.ticketflow.domain.port.InventoryRepository;
import java.time.Clock;
import reactor.core.publisher.Mono;

/** Validates and creates an event, then initializes its inventory (available = capacity). */
public class CreateEventUseCase {

    private final EventRepository events;
    private final InventoryRepository inventories;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public CreateEventUseCase(
            EventRepository events, InventoryRepository inventories, IdGenerator idGenerator, Clock clock) {
        this.events = events;
        this.inventories = inventories;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    public Mono<Event> execute(CreateEventCommand command) {
        return Mono.defer(() -> {
            validate(command);
            EventId id = idGenerator.nextEventId();
            Event event = new Event(id, command.name(), command.startsAt(), command.venue(), command.capacity());
            return events.save(event)
                    .flatMap(saved -> inventories
                            .create(Inventory.initial(saved.id(), saved.capacity()))
                            .thenReturn(saved));
        });
    }

    private void validate(CreateEventCommand command) {
        if (command == null) {
            throw new InvalidEventException("Event data must not be null");
        }
        if (command.name() == null || command.name().isBlank()) {
            throw new InvalidEventException("Event name must not be blank");
        }
        if (command.venue() == null || command.venue().isBlank()) {
            throw new InvalidEventException("Event venue must not be blank");
        }
        if (command.startsAt() == null || !command.startsAt().isAfter(clock.instant())) {
            throw new InvalidEventException("Event date must be in the future");
        }
        if (command.capacity() <= 0) {
            throw new InvalidEventException("Event capacity must be greater than 0: " + command.capacity());
        }
    }
}
