package com.ticketflow.usecase;

import com.ticketflow.domain.exception.OrderNotFoundException;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.port.OrderRepository;
import reactor.core.publisher.Mono;

/** Returns the current status of an order (strongly consistent read). */
public class GetOrderStatusUseCase {

    private final OrderRepository orders;

    public GetOrderStatusUseCase(OrderRepository orders) {
        this.orders = orders;
    }

    /** @throws OrderNotFoundException (as an error signal) when the order does not exist */
    public Mono<OrderStatusView> execute(OrderId id) {
        return orders.findById(id)
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(id)))
                .map(OrderStatusView::from);
    }
}
