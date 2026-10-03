package com.ticketflow.infrastructure.web;

import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.usecase.GetAvailabilityUseCase;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Availability of an event, counting sold and temporarily reserved tickets, as a snapshot and as a
 * server-sent-events stream.
 *
 * <p>The stream emits the current value immediately and then on every change. The use-case stream
 * fails once its transient-error retries are exhausted; here it is resubscribed with exponential
 * backoff so a hiccup never ends the client's connection, and the cause is only logged, never sent.
 * Cancelling the HTTP request cancels the polling.
 */
@RestController
@RequestMapping("/events/{id}/availability")
public class AvailabilityController {

    private static final Logger LOG = LoggerFactory.getLogger(AvailabilityController.class);
    private static final Duration MIN_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final GetAvailabilityUseCase availability;
    private final Duration minBackoff;
    private final Duration maxBackoff;
    private final Scheduler retryScheduler;

    @Autowired
    public AvailabilityController(GetAvailabilityUseCase availability) {
        this(availability, MIN_BACKOFF, MAX_BACKOFF, Schedulers.parallel());
    }

    AvailabilityController(GetAvailabilityUseCase availability, Duration minBackoff, Duration maxBackoff,
                           Scheduler retryScheduler) {
        this.availability = availability;
        this.minBackoff = minBackoff;
        this.maxBackoff = maxBackoff;
        this.retryScheduler = retryScheduler;
    }

    @GetMapping
    public Mono<AvailabilityResponse> get(@PathVariable String id) {
        return availability.execute(new EventId(id)).map(OrderMapper::toResponse);
    }

    /**
     * An unknown event is answered with a {@code 404} problem before the stream starts: the
     * existence check runs first and only then the response is committed as an event stream. The
     * content type is set on the response rather than with {@code produces} so that the problem
     * answer of an unknown event is not forced into {@code text/event-stream}.
     */
    @GetMapping("/stream")
    public Mono<ResponseEntity<Flux<AvailabilityResponse>>> stream(@PathVariable String id) {
        EventId eventId = new EventId(id);
        return availability.execute(eventId)
                .map(first -> ResponseEntity.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body(resilientStream(eventId)));
    }

    Flux<AvailabilityResponse> resilientStream(EventId eventId) {
        return Flux.defer(() -> availability.stream(eventId))
                .map(OrderMapper::toResponse)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, minBackoff)
                        .maxBackoff(maxBackoff)
                        .scheduler(retryScheduler)
                        .transientErrors(true)
                        .filter(error -> !(error instanceof EventNotFoundException))
                        .doBeforeRetry(signal -> LOG.warn(
                                "Availability stream of event {} failed ({}); resubscribing",
                                eventId.value(), signal.failure().getClass().getSimpleName())));
    }
}
