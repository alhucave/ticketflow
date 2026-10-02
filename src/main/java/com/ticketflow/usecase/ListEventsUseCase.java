package com.ticketflow.usecase;

import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.port.EventRepository;
import reactor.core.publisher.Flux;

/** Lists all events as a stream (no pagination yet; the port exposes findAll only). */
public class ListEventsUseCase {

    private final EventRepository events;

    public ListEventsUseCase(EventRepository events) {
        this.events = events;
    }

    public Flux<Event> execute() {
        return events.findAll();
    }
}
