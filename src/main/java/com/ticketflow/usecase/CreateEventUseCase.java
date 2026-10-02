package com.ticketflow.usecase;

import com.ticketflow.domain.exception.InvalidEventException;
import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.port.EventRepository;
import com.ticketflow.domain.port.IdGenerator;
import java.time.Clock;
import reactor.core.publisher.Mono;

/**
 * Validates and creates an event. {@link EventRepository#save} persists the event together with its
 * initial inventory (available = capacity, version 0), so this use case never touches inventories.
 */
public class CreateEventUseCase {

    private final EventRepository events;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public CreateEventUseCase(
            EventRepository events, IdGenerator idGenerator, Clock clock) {
        this.events = events;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    public Mono<Event> execute(CreateEventCommand command) {
        return Mono.defer(() -> {
            validate(command);
            EventId id = idGenerator.nextEventId();
            Event event = new Event(id, command.name(), command.startsAt(), command.venue(), command.capacity());
            return events.save(event);
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
