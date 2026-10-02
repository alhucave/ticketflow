# Review — feature F-015 (sqs-publisher)

**Veredicto:** APPROVED

## Criterios de aceptación
- Publishing returns after SQS acknowledges the message: SqsOrderQueuePublisherTest (completa solo tras el ack) y SqsOrderQueuePublisherIT — [x]
- Integration test with LocalStack verifies the message arrives: SqsOrderQueuePublisherIT (cuerpo y atributos recibidos) y RequestPurchaseSqsEndToEndIT — [x]
- Endpoint and queue URL come from configuration: SqsConfigTest + IT (endpoint/cola/URL por propiedades) — [x]

## Checkpoints (CHECKPOINTS.md)
- C1: [x] `./init.sh` y `INCLUDE_INTEGRATION=true ./init.sh` (Colima) ejecutados por el reviewer, ambos BUILD SUCCESSFUL / init.sh OK; IT de SQS ejecutados (5+3+13 tests, 0 fallos, 0 skipped).
- C2: [x] Dominio sin cambios; adaptador y config en infrastructure.
- C3: [x] Tests con aserciones reales (mensaje recibido, reintentos acotados, no reintento en 4xx, URL cacheada, error claro de cola inexistente).
- C4: [x] Sin `.block()` ni `Thread.sleep` en src/main.
- C5: [x] N/A (sin cambios de inventario; la compensación usa el adaptador existente, verificada en el E2E).
- C6: [x] E2E verifica auditoría RESERVED->AVAILABLE con razón `enqueue failed`.
- C7: [x] Inglés, sin Lombok ni @Autowired.
- C8: [x] Solo `test`/`test` en compose/tests; sin secretos.
- C9: [x] Cobertura global 99.5% según informe; gate JaCoCo en verde en ambas ejecuciones.
- C10: [x] Alcance acotado a SQS; fallback eliminado de UseCaseConfig (pedido en la feature).
- C11: [x] README y docker-compose actualizados; application.yml con la cola configurable.
- C12: [x] RequestPurchaseSqsEndToEndIT usa adaptadores DynamoDB reales + SQS real (LocalStack): compra -> mensaje con orderId y reserva intacta; cola inexistente y SQS inalcanzable -> compensación atómica (inventario, versión, orden AVAILABLE, auditoría). Contrato `publish(Order): Mono<Void>` compatible con `RequestPurchaseUseCase.enqueue` (error propagado -> `compensate`).

## Comprobaciones clave
- Cliente SQS async real (`SqsAsyncClient`), `Mono.fromFuture`.
- Jackson 3 (`tools.jackson.databind.json.JsonMapper`) sin versión explícita en build.gradle.kts (gestionada por Spring Boot).
- Retry.backoff solo con filtro `isTransient` (throttling, 5xx, errores de conexión); 4xx y cola inexistente fallan sin reintento; se propaga el error original.
- URL de cola lazy, cacheada, sin cachear fallos, con `OrderQueueNotFoundException` clara.
- Configuración por `SqsProperties` (`ticketflow.sqs.*`), sin valores hardcodeados.

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
- El cache de URL usa TTL de 3650 días como "para siempre"; funciona, pero un TTL explícito configurable sería más expresivo.
- Jackson llega transitivamente vía webflux; declararlo explícitamente (sin versión) haría la dependencia más clara.
