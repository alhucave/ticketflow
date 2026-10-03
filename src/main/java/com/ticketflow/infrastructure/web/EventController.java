package com.ticketflow.infrastructure.web;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.usecase.CreateEventUseCase;
import com.ticketflow.usecase.GetEventUseCase;
import com.ticketflow.usecase.ListEventsUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive events API. Domain errors propagate as typed exceptions and are translated to HTTP in
 * {@link com.ticketflow.infrastructure.web.error.ApiExceptionHandler}.
 *
 * <p>{@code GET /events} returns the events only (no inventory): attaching the inventory would cost
 * one extra read per event; clients fetch {@code GET /events/{id}} for the counters.
 */
@RestController
@RequestMapping("/events")
public class EventController {

    private final CreateEventUseCase createEvent;
    private final GetEventUseCase getEvent;
    private final ListEventsUseCase listEvents;

    public EventController(CreateEventUseCase createEvent, GetEventUseCase getEvent, ListEventsUseCase listEvents) {
        this.createEvent = createEvent;
        this.getEvent = getEvent;
        this.listEvents = listEvents;
    }

    @PostMapping
    public Mono<ResponseEntity<EventResponse>> create(@Valid @RequestBody Mono<CreateEventRequest> request) {
        return request
                .map(EventMapper::toCommand)
                .flatMap(createEvent::execute)
                .map(event -> ResponseEntity
                        .created(UriComponentsBuilder.fromPath("/events/{id}").build(event.id().value()))
                        .body(EventMapper.toResponse(event)));
    }

    @GetMapping("/{id}")
    public Mono<EventDetailsResponse> get(@PathVariable String id) {
        return getEvent.execute(new EventId(id)).map(EventMapper::toResponse);
    }

    @GetMapping
    public Flux<EventResponse> list() {
        return listEvents.execute().map(EventMapper::toResponse);
    }
}
