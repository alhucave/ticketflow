# Review — feature F-019 (web-orders-availability-api)

**Veredicto:** APPROVED

## Criterios de aceptación
- POST /orders devuelve 202 inmediato con orderId (+ Location, body {orderId,status,reservationExpiresAt}): OrderControllerTest.purchase_validRequest_returns202WithLocationAndBody; OrdersApiEndToEndIT (adaptadores reales) — [x]
- Idempotency-Key ausente/en blanco/charset inválido/>128 -> 400; 128 exactos aceptado: OrderControllerTest (missing/blankOrBad/over128/exactly128), IT — [x]
- Body: eventId blank, quantity 0/11/ausente -> 400 con violations; límite 10 aceptado; JSON malformado 400 — [x]
- Replay misma clave+payload misma orden (202 sin expiry si final); payload distinto 409; orden liberada 409; sobre capacidad 409; enqueue fallido 503 sin causa — [x] (unit + IT para replay, 409 payload, 409 capacidad)
- GET /orders/{id} los 5 estados, expiry omitido en SOLD/COMPLIMENTARY/AVAILABLE, 404 — [x]
- Availability snapshot y stream SSE (valor inmediato, cambio, resubscribe con backoff en tiempo virtual, sin fuga de detalle, 404 JSON antes del stream, cancelación limpia) — [x] AvailabilityControllerTest + IT
- WebTestClient por endpoint — [x]

## Checkpoints (CHECKPOINTS.md)
- C1: [x] `./init.sh` y `INCLUDE_INTEGRATION=true ./init.sh` ejecutados por mí, ambos BUILD SUCCESSFUL / `init.sh OK`.
- C2: [x] Dominio sin cambios; web solo depende de usecase/domain.
- C3: [x] Tests con aserciones reales (cuerpo, cabeceras, verificación de que el caso de uso no se invoca, tiempo virtual).
- C4: [x] Sin `.block()`/`Thread.sleep` en `src/main` (grep). Los `blockFirst` están solo en el IT.
- C5: [x] Sin cambios de inventario nuevos; se reutilizan las escrituras condicionadas existentes.
- C6: [x] Sin cambios de transiciones.
- C7: [x] Inglés, sin Lombok; el único `@Autowired` está en un constructor (necesario con dos constructores), no en campo.
- C8: [x] Solo credenciales ficticias test/test.
- C9: [x] Gate JaCoCo >= 90% pasa (el implementer reporta 99.6%).
- C10: [x] Cambios acotados a web, README, estado de la feature.
- C11: [x] README actualizado (endpoints, errores, límites).
- C12: [x] OrdersApiEndToEndIT arranca el contexto completo con DynamoDB Local 3.3.1 + LocalStack 4.14.0 (cola con DLQ/redrive, consumer activo), sin sleeps fijos (Awaitility). Cubre replay, 409 payload distinto, 409 sobre capacidad, claves/cantidades inválidas 400, 404 orden/evento, SSE inicial+cambio, 404 problem+json en stream, ráfaga de 40 POST paralelos sobre capacidad 15: exactamente 15 aceptados, 25 x 409, 15 SOLD, invariante. Compatibilidad adaptador/caso de uso: el controller solo delega en `RequestPurchaseUseCase`, cuyo `placeReservation` mapea cancelaciones condicionales a InsufficientInventory/EventNotFound/OrderAlreadyExists; el 409 de capacidad y el replay se ven con el adaptador real. El orderId derivado de la clave hace seguro el reintento concurrente (PK condicional).

## Verificaciones específicas
- Fuga de internos: los handlers 409/503 usan texto fijo (sin ids/cantidades/causa); los 404 solo repiten el id que envió el cliente; el SSE solo registra el nombre de clase del error (WARN) y no lo envía; los errores no manejados quedan en el 500 por defecto de Spring sin detalle.
- Límites de validación: header (<=128, charset) validado antes de leer el body y antes del caso de uso; quantity 1..10; eventId @NotBlank.
- Cambio de firma de ApiExceptionHandler a `ResponseEntity<ProblemDetail>` con `application/problem+json` explícito: cohesivo (todos los handlers usan `respond(problem(...))`, Javadoc actualizado para F-020), JSON idéntico, necesario para clientes `Accept: text/event-stream`.
- Desconexión/reintento: una cancelación entre `placeReservation` y `publish` (o durante la compensación) deja la orden en RESERVED sin mensaje; no hay compra duplicada ni inventario inconsistente (invariante intacto) y se recupera por expiración (F-017), limitación ya documentada en docs/architecture.md. Reintentar con la misma clave devuelve la misma orden (no duplica).

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
1. En esa limitación (cancelación tras la transacción), un replay con la misma clave devuelve 202 aunque no haya mensaje en cola hasta que expire; ya documentado, y el scheduler está desactivado por defecto: conviene recordarlo en despliegue/F-020.
2. No hay IT/test web explícito de POST /orders con un eventId inexistente (el handler EventNotFound -> 404 existe y lo cubre la capa de persistencia); sería una prueba barata de añadir.
3. El 404 de orden/evento repite el id de la URL en `detail`; es entrada del propio cliente, sin riesgo.
