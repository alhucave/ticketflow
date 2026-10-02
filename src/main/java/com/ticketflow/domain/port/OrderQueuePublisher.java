package com.ticketflow.domain.port;

import com.ticketflow.domain.model.Order;
import reactor.core.publisher.Mono;

/** Output port that enqueues an order for asynchronous confirmation. */
public interface OrderQueuePublisher {

    Mono<Void> publish(Order order);
}
