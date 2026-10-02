package com.ticketflow.usecase;

import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.port.EventRepository;
import com.ticketflow.domain.port.InventoryRepository;
import reactor.core.publisher.Mono;

/** Returns an event with its current inventory snapshot. */
public class GetEventUseCase {

    private final EventRepository events;
    private final InventoryRepository inventories;

    public GetEventUseCase(EventRepository events, InventoryRepository inventories) {
        this.events = events;
        this.inventories = inventories;
    }

    public Mono<EventDetails> execute(EventId id) {
        return events.findById(id)
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(id)))
                .flatMap(event -> inventories.findByEventId(id)
                        .switchIfEmpty(Mono.error(() -> new EventNotFoundException(id)))
                        .map(inventory -> new EventDetails(event, inventory)));
    }
}
