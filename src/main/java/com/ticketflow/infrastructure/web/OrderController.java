package com.ticketflow.infrastructure.web;

import com.ticketflow.usecase.GetOrderStatusUseCase;
import com.ticketflow.usecase.RequestPurchaseUseCase;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * Asynchronous purchases. {@code POST /orders} reserves, enqueues and answers {@code 202}
 * immediately; the order is processed later and its progress is read with {@code GET /orders/{id}}.
 * Domain errors are translated in {@link com.ticketflow.infrastructure.web.error.ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final RequestPurchaseUseCase requestPurchase;
    private final GetOrderStatusUseCase getOrderStatus;
    private final int maxQuantity;

    /** @param maxQuantity most tickets one order may hold ({@code ticketflow.orders.max-quantity}) */
    public OrderController(RequestPurchaseUseCase requestPurchase, GetOrderStatusUseCase getOrderStatus,
                           @Value("${ticketflow.orders.max-quantity:10}") int maxQuantity) {
        if (maxQuantity < 1) {
            throw new IllegalArgumentException("ticketflow.orders.max-quantity must be at least 1");
        }
        this.requestPurchase = requestPurchase;
        this.getOrderStatus = getOrderStatus;
        this.maxQuantity = maxQuantity;
    }

    /**
     * Required header {@code Idempotency-Key}: retrying with the same key and payload returns the
     * same order (same {@code 202} body, although its status may have advanced); the same key with a
     * different payload is a {@code 409}. {@code quantity} may not exceed the configured maximum per order
     * ({@code 400}). Reserve + publish run detached from the request subscription: a client that
     * disconnects mid-flight cannot interrupt them (see {@link Detached}).
     */
    @PostMapping
    public Mono<ResponseEntity<PurchaseAcceptedResponse>> purchase(
            @RequestHeader(name = OrderMapper.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody Mono<PurchaseRequest> request) {
        return Mono.fromSupplier(() -> OrderMapper.toKey(idempotencyKey))
                .flatMap(key -> request.map(body -> OrderMapper.toCommand(checked(body), key)))
                .flatMap(command -> Detached.detach(requestPurchase.execute(command)))
                .map(result -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .location(UriComponentsBuilder.fromPath("/orders/{id}").build(result.orderId().value()))
                        .body(OrderMapper.toResponse(result)));
    }

    @GetMapping("/{id}")
    public Mono<OrderStatusResponse> get(@PathVariable String id) {
        return getOrderStatus.execute(PathIds.orderId(id)).map(OrderMapper::toResponse);
    }

    private PurchaseRequest checked(PurchaseRequest body) {
        if (body.quantity() > maxQuantity) {
            throw new InvalidRequestFieldException("quantity", "must be less than or equal to " + maxQuantity);
        }
        return body;
    }
}
