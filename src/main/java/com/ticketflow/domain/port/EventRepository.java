package com.ticketflow.domain.port;

import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Output port for event persistence. */
public interface EventRepository {

    /**
     * Persists a new event together with its initial inventory (available = capacity, version 0),
     * atomically. Callers must not create the inventory separately. Fails with
     * {@code EventAlreadyExistsException} when the event id is already taken.
     */
    Mono<Event> save(Event event);

    /** Emits the event, or completes empty when absent. */
    Mono<Event> findById(EventId id);

    Flux<Event> findAll();
}
