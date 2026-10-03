package com.ticketflow.infrastructure.web;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.usecase.IssueComplimentaryCommand;
import com.ticketflow.usecase.IssueComplimentaryUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * Admin-only issuance of complimentary tickets. The route is protected by {@link AdminKeyWebFilter}
 * (it never reaches this controller without a valid {@code X-Admin-Key}).
 */
@RestController
@RequestMapping("/events")
public class ComplimentaryController {

    private final IssueComplimentaryUseCase issueComplimentary;

    public ComplimentaryController(IssueComplimentaryUseCase issueComplimentary) {
        this.issueComplimentary = issueComplimentary;
    }

    /**
     * Required header {@code Idempotency-Key}. {@code 201} for the first issuance and for a replay of
     * the same key and payload (same body and {@code Location}, like {@code POST /orders} answers the
     * same code on replay); the same key with a different payload is a {@code 409}.
     */
    @PostMapping("/{id}/complimentary")
    public Mono<ResponseEntity<ComplimentaryResponse>> issue(
            @PathVariable String id,
            @RequestHeader(name = OrderMapper.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody Mono<ComplimentaryRequest> request) {
        EventId eventId = PathIds.eventId(id);
        return Mono.fromSupplier(() -> OrderMapper.toKey(idempotencyKey))
                .flatMap(key -> request.map(body -> new IssueComplimentaryCommand(
                        eventId, new Quantity(body.quantity()), key, body.reason())))
                .flatMap(command -> Detached.detach(issueComplimentary.execute(command)))
                .map(result -> ResponseEntity.status(HttpStatus.CREATED)
                        .location(UriComponentsBuilder.fromPath("/orders/{id}").build(result.orderId().value()))
                        .body(new ComplimentaryResponse(result.orderId().value(), result.eventId().value(),
                                result.quantity().value(), result.status().name())));
    }
}
