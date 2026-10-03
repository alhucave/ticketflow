package com.ticketflow.infrastructure.web;

import com.ticketflow.domain.model.OrderId;
import com.ticketflow.usecase.GetOrderStatusUseCase;
import com.ticketflow.usecase.RequestPurchaseUseCase;
import jakarta.validation.Valid;
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

    public OrderController(RequestPurchaseUseCase requestPurchase, GetOrderStatusUseCase getOrderStatus) {
        this.requestPurchase = requestPurchase;
        this.getOrderStatus = getOrderStatus;
    }

    /**
     * Required header {@code Idempotency-Key}: retrying with the same key and payload returns the
     * same order (same {@code 202} body, although its status may have advanced); the same key with a
     * different payload is a {@code 409}.
     */
    @PostMapping
    public Mono<ResponseEntity<PurchaseAcceptedResponse>> purchase(
            @RequestHeader(name = OrderMapper.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody Mono<PurchaseRequest> request) {
        return Mono.fromSupplier(() -> OrderMapper.toKey(idempotencyKey))
                .flatMap(key -> request.map(body -> OrderMapper.toCommand(body, key)))
                .flatMap(requestPurchase::execute)
                .map(result -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .location(UriComponentsBuilder.fromPath("/orders/{id}").build(result.orderId().value()))
                        .body(OrderMapper.toResponse(result)));
    }

    @GetMapping("/{id}")
    public Mono<OrderStatusResponse> get(@PathVariable String id) {
        return getOrderStatus.execute(new OrderId(id)).map(OrderMapper::toResponse);
    }
}
