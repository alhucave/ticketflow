# Review — feature F-011 (usecase-availability)

**Veredicto:** APPROVED

## Criterios de aceptación
- Availability reflects reserved and pending as not available: GetAvailabilityUseCaseTest.execute_reservedAndPending_areNotAvailable (4/3/2/1 exposed separately) y EventUseCasesIT.availability_afterCreateAndReserve_reflectsReservedAndStreamsChange (reserve(5) real => available 45, reserved 5) — [x]
- Stream emits updates and completes/cancels cleanly: stream_emitsFirstValueAndOnlyChanges (virtual time, distinctUntilChanged), stream_cancel_stopsPolling (no further findByEventId after cancel), IT stream take(2) tras reserve real — [x]
- Unknown event yields EventNotFound: execute_unknownEvent / stream_unknownEvent (unit) y availability_unknownEvent_failsWithEventNotFound (IT, adaptador real) — [x]

## Verificación C12 (contrato de adaptadores, leído en DynamoDbInventoryRepository)
- findByEventId: GetItem con consistentRead(true); devuelve Mono vacío si no hay item (filter hasItem/!isEmpty). El use case lo traduce con switchIfEmpty -> EventNotFoundException: compatible.
- Inventario creado atómicamente con el evento (F-007/F-010), por lo que "inventario ausente == evento desconocido" es válido.
- reserve = un UpdateItem condicionado: available -= q, reserved += q, version += 1. Por tanto `available` excluye reserved/pending (invariante); el snapshot mapea los contadores 1:1. El IT lo demuestra con el adaptador real (50 -> 45/5).
- La lectura consistente garantiza que el polling ve las escrituras propias de reserve.
- Nota: el adaptador no tiene operación que mueva a pendingConfirmation todavía; el use case solo lee el contador, sin dependencia de ello.

## Checkpoints (CHECKPOINTS.md)
- C1: [x] `./init.sh` OK y `INCLUDE_INTEGRATION=true ./init.sh` OK (ejecutados por el reviewer; EventUseCasesIT 4/4, 0 fallos, GetAvailabilityUseCaseTest 6/6)
- C2: [x] usecase solo importa domain.* y reactor; wiring en infrastructure.config.UseCaseConfig; sin Spring en el use case
- C3: [x] ver criterios
- C4: [x] sin `.block()` en src/main (grep); los `.block(Duration)` están solo en tests
- C5: [x] no modifica inventario (solo lectura)
- C6: [x] n/a, sin transiciones
- C7: [x] inglés, sin Lombok, inyección por constructor, `@Value` en parámetro de @Bean
- C8: [x]
- C9: [x] gate JaCoCo 90% pasa
- C10: [x] cambios limitados a use case, bean, tests y bookkeeping
- C11: [x] sin cambios de docs necesarios para un use case interno (el endpoint y su documentación son F-0xx posterior); ver observación
- C12: [x] IT de punta a punta con DynamoDB real vía Testcontainers; contrato verificado arriba

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
- La propiedad `ticketflow.availability.poll-interval` no está documentada en README/application.yml; conviene añadirla cuando llegue el endpoint SSE (F-0xx).
- Un error transitorio de DynamoDB (tras agotar reintentos) termina el stream; el controlador podría aplicar un retry/onErrorResume.
- Las importaciones de EventUseCasesIT no están en orden alfabético (EventNotFoundException tras EventId); cosmético.
