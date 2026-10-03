package com.ticketflow.infrastructure.web;

import reactor.core.publisher.Mono;

/**
 * Detaches a unit of work from the lifetime of the request that triggered it. When an HTTP client
 * disconnects, WebFlux cancels the request's subscription; for a purchase that would interrupt the
 * chain "reserve inventory, publish the message, compensate if publishing fails" half way and strand
 * a reservation without message. A detached chain runs to completion (or compensation) regardless.
 *
 * <p>Implemented with {@code cache()}: the source is subscribed once, and a subscriber that cancels only
 * stops listening. The Reactor context of the triggering request (correlation id) is pinned explicitly,
 * because after the only subscriber cancels, later resubscriptions inside the chain (retries) would
 * otherwise see an empty context.
 */
final class Detached {

    private Detached() {}

    static <T> Mono<T> detach(Mono<T> work) {
        return Mono.deferContextual(context -> work.contextWrite(context).cache());
    }
}
