package com.ticketflow.infrastructure.web;

import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.Inventory;
import com.ticketflow.usecase.CreateEventCommand;
import com.ticketflow.usecase.EventDetails;

/** Maps web DTOs to and from use-case and domain types, keeping the domain free of web concerns. */
final class EventMapper {

    private EventMapper() {}

    static CreateEventCommand toCommand(CreateEventRequest request) {
        return new CreateEventCommand(request.name(), request.startsAt(), request.venue(), request.capacity());
    }

    static EventResponse toResponse(Event event) {
        return new EventResponse(
                event.id().value(), event.name(), event.startsAt(), event.venue(), event.capacity());
    }

    static EventDetailsResponse toResponse(EventDetails details) {
        Event event = details.event();
        return new EventDetailsResponse(
                event.id().value(), event.name(), event.startsAt(), event.venue(), event.capacity(),
                toResponse(details.inventory()));
    }

    static InventoryResponse toResponse(Inventory inventory) {
        return new InventoryResponse(inventory.available(), inventory.reserved(),
                inventory.pendingConfirmation(), inventory.sold(), inventory.complimentary());
    }
}
