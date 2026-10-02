package com.ticketflow.domain.port;

import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Output port for event persistence. */
public interface EventRepository {

    Mono<Event> save(Event event);

    /** Emits the event, or completes empty when absent. */
    Mono<Event> findById(EventId id);

    Flux<Event> findAll();
}
