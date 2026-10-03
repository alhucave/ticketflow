# Implementacion F-019 — web-orders-availability-api

Rama: `feature/F-019-web-orders-availability-api` (commit local, sin push). `feature_list.json`: F-019 en `in_progress` (no `done`).

## Archivos
Nuevos (`src/main/java/com/ticketflow/infrastructure/web`): `OrderController`, `AvailabilityController`, `PurchaseRequest`, `PurchaseAcceptedResponse`, `OrderStatusResponse`, `AvailabilityResponse`, `OrderMapper`, `InvalidIdempotencyKeyException`.
Modificados: `error/ApiExceptionHandler` (nuevos handlers + `respond(...)`), `README.md` (Endpoints), `feature_list.json` (status), `progress/current.md`.
Tests nuevos: `OrderControllerTest`, `AvailabilityControllerTest` (slice, WebTestClient/StepVerifier con `VirtualTimeScheduler`), `OrdersApiEndToEndIT` (C12).

## Decisiones de diseno
- **Idempotency-Key** se valida en `OrderMapper.toKey` (presente, no blanca, <=128, `[A-Za-z0-9._:-]`) con `required=false` en el header para controlar el 400 con ProblemDetail propio (`invalid-idempotency-key`), antes de leer el body y sin llegar al caso de uso.
- **Quantity** `@Min(1) @Max(10)` (`PurchaseRequest.MAX_QUANTITY`, documentado en Javadoc y README); `eventId` `@NotBlank`; `@NotNull` para campos ausentes -> 400 `validation-error` con `violations`.
- **202**: `Location: /orders/{id}` y body `{orderId,status,reservationExpiresAt}`. `reservationExpiresAt` se omite (NON_NULL) para SOLD/COMPLIMENTARY/AVAILABLE tanto en POST (replay) como en GET (`OrderMapper.visibleExpiry`, `switch` exhaustivo sobre los 5 estados). El replay devuelve la misma orden; su `status` puede haber avanzado (documentado).
- **SSE**: `AvailabilityController.stream` hace primero `execute(id)` (404 JSON antes de abrir el stream) y luego devuelve el flujo con `contentType(TEXT_EVENT_STREAM)`. `resilientStream` reintenta con `Retry.backoff(Long.MAX_VALUE, 1s).maxBackoff(30s).transientErrors(true)` (con jitter), excepto `EventNotFoundException`; solo se registra la clase del error (WARN), nada llega al cliente. Cancelar la peticion cancela el polling. Backoff/scheduler inyectables (constructor package-private) para probar con tiempo virtual.
- No se usa `produces` en el mapping del stream y `ApiExceptionHandler` ahora responde con `ResponseEntity<ProblemDetail>` con `Content-Type: application/problem+json` explicito (`respond(...)`): un cliente SSE manda `Accept: text/event-stream` y sin esto el problema se serializaba como `text/event-stream` (lo detecto un test). `problem(...)` sigue siendo el unico constructor del ProblemDetail; F-020 puede extender ambos helpers.
- Mapeos: InsufficientInventory/IdempotencyKeyReused/IdempotentOrderNotActive -> 409; OrderNotFound -> 404; OrderEnqueueFailed -> 503 con detalle fijo ("you may retry with a new Idempotency-Key"); 409 con textos fijos (no se filtran ids ni cantidades), sin trazas ni causas. Sin `.block()` en produccion; dominio intacto.

## Verificacion
- `./init.sh` (sin Docker): BUILD SUCCESSFUL, cobertura de lineas 99.6% (gate 90%), `==> init.sh OK`.
- `INCLUDE_INTEGRATION=true ./init.sh` (DOCKER_HOST colima + TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock): 521 tests, 0 fallos, BUILD SUCCESSFUL, `==> init.sh OK`.
- `OrdersApiEndToEndIT` (contexto real, DynamoDB Local 3.3.1, LocalStack 4.14.0 con cola + DLQ con redrive, consumer activo, sin sleeps fijos): compra -> 202+Location -> poll hasta SOLD -> availability sold=qty/reserved=0 -> replay misma orden -> 409 payload distinto -> 409 sobre capacidad -> 400 clave ausente/vacia/invalida/>128 y quantity 0/11 -> 404 orden/evento -> SSE emite valor actual y luego el cambio -> 404 problem+json en stream de evento inexistente -> 40 POST paralelos qty 1 sobre capacidad 15: exactamente 15 aceptados, 25 `409`, 15 SOLD, invariante cumplido.

## docker-compose real (`docker-compose up --build -d`, consumer + scheduler habilitados)
```
$ POST /events (capacity 20) -> 201 Created, Location: /events/87393e2c-5b5f-470e-bd9c-a42bef0513ae
$ POST /orders  (Idempotency-Key: demo-key-1, quantity 3)
HTTP/1.1 202 Accepted
Location: /orders/bcb8a659-aedf-562d-a829-8a0d1030a974
{"orderId":"bcb8a659-aedf-562d-a829-8a0d1030a974","status":"RESERVED","reservationExpiresAt":"2026-10-03T01:13:26.726661177Z"}
$ poll GET /orders/{id}
{"orderId":"bcb8a659-...","eventId":"87393e2c-...","quantity":3,"status":"PENDING_CONFIRMATION","reservationExpiresAt":"2026-10-03T01:13:26.726661177Z","createdAt":"2026-10-03T01:03:26.726661177Z"}
{"orderId":"bcb8a659-...","eventId":"87393e2c-...","quantity":3,"status":"SOLD","createdAt":"2026-10-03T01:03:26.726661177Z"}
$ GET /events/{id}/availability
{"available":17,"reserved":0,"pendingConfirmation":0,"sold":3,"complimentary":0,"capacity":20}
$ replay same key/payload -> 202 Accepted
{"orderId":"bcb8a659-aedf-562d-a829-8a0d1030a974","status":"SOLD"}
$ same key, quantity 4 -> 409 application/problem+json
{"detail":"This Idempotency-Key was already used with a different request; use a new key for a new request","instance":"/orders","status":409,"title":"Idempotency-Key reused","type":"urn:ticketflow:problem:idempotency-key-reused"}
$ missing key -> 400
{"detail":"Idempotency-Key header is required","instance":"/orders","status":400,"title":"Invalid Idempotency-Key","type":"urn:ticketflow:problem:invalid-idempotency-key"}
$ quantity 11 -> 400
{"detail":"The request body has invalid fields","instance":"/orders","status":400,"title":"Validation failed","type":"urn:ticketflow:problem:validation-error","violations":[{"field":"quantity","message":"must be less than or equal to 10"}]}
$ GET /orders/nope -> 404
{"detail":"Order not found: nope","instance":"/orders/nope","status":404,"title":"Order not found","type":"urn:ticketflow:problem:order-not-found"}
$ SSE unknown event (Accept: text/event-stream) -> 404 application/problem+json
{"detail":"Event not found: nope","instance":"/events/nope/availability/stream","status":404,"title":"Event not found","type":"urn:ticketflow:problem:event-not-found"}
$ curl -sN --max-time 8 .../availability/stream  (purchase of 2 issued after 2 s)
data:{"available":20,"reserved":0,"pendingConfirmation":0,"sold":0,"complimentary":0,"capacity":20}

data:{"available":18,"reserved":0,"pendingConfirmation":0,"sold":2,"complimentary":0,"capacity":20}

(curl exit 28 = timeout, expected)
```
`docker-compose down -v` ejecutado al final (la ejecucion del SSE se hizo en una segunda tanda de compose con un evento nuevo, mismo comportamiento).

## Notas para el reviewer
- Cambio de firma de los handlers existentes (`ProblemDetail` -> `ResponseEntity<ProblemDetail>`); los tests de EventController siguen en verde y el JSON es identico.
- Los intermedios `reserved`/`pendingConfirmation` pueden no verse en el SSE (polling cada 1 s por `GetAvailabilityUseCase`), por diseno de F-011.
