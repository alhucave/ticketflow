# Review — feature F-010 (usecase-events), re-review of 799c164 + 85d8bf0

**Veredicto:** APPROVED

## Compatibilidad con los adaptadores reales
- Create -> `DynamoDbEventRepository.save`: el adaptador escribe evento + `Inventory.initial` en un `TransactWriteItems` con `attribute_not_exists`; el use case solo llama `events.save`, sin `inventories.create`. Duplicado -> `EventAlreadyExistsException` (propagado tal cual, test unitario). Compatible. El IT sobre DynamoDB Local lo demuestra (inventario available=120, version 0, creado una sola vez).
- Validacion previa a los puertos: el `Event` record lanza IAE, pero el use case valida antes y emite `InvalidEventException`; el IT comprueba que no se escribe nada en ninguna tabla.
- Get -> `findById` / `findByEventId`: ambos adaptadores reportan ausencia como `Mono` vacio (filtro `hasItem`), no como error; el use case usa `switchIfEmpty` -> `EventNotFoundException`. Compatible. Lecturas con `consistentRead(true)`.
- List -> `findAll`: scan paginado con `expand`, `Flux` completo sin orden garantizado ni consistencia fuerte; el use case no promete orden. Compatible.

## Criterios de aceptacion
- Rechaza input invalido con error de validacion de dominio: `CreateEventUseCaseTest` (nombre, venue, fecha no futura, capacidad <= 0, comando nulo; `verifyNoInteractions(events)`) y `EventUseCasesIT.create_invalidInput_...` — [x]
- Devuelve evento con snapshot de inventario actual: `GetAndListEventsUseCaseTest.getEvent_existing_...`, `getEvent_unknownId_...`, `getEvent_missingInventory_...` y `EventUseCasesIT.createGetList_endToEnd_...` con adaptadores reales — [x]
- Tests unitarios con puertos mockeados y StepVerifier: todos los tests de `usecase/*Test` — [x]

## Checkpoints
- C1: [x] `./init.sh` OK y `INCLUDE_INTEGRATION=true ./init.sh` OK (colima); se ejecuto `EventUseCasesIT`.
- C2: [x] usecase sin Spring/AWS/infrastructure (`UseCaseArchitectureTest`); domain limpio.
- C3: [x]
- C4: [x] sin `.block()`/`Thread.sleep` en main (el `.block` del IT es codigo de test).
- C5: [x] N/A, la feature no modifica contadores; el inventario inicial lo escribe el adaptador.
- C6: [x] N/A, sin transiciones.
- C7: [x] Ingles, sin Lombok; `@Autowired` solo en constructores preexistentes (F-008/F-009), no en esta feature.
- C8: [x] credenciales ficticias test/test.
- C9: [x] JaCoCo >= 90% verde en init.sh.
- C10: [x] scope acotado (`IdGenerator` y `UseCaseConfig` son necesarios para el wiring descrito).
- C11: [x] `docs/architecture.md` actualizado con el wiring de use cases; Javadoc de `EventRepository.save` documenta el contrato atomico.

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
- `progress/impl_usecase-events.md` mantiene la decision obsoleta "save y luego inventories.create" (marcada como obsoleta mas abajo); conviene limpiarla.
- `ListEventsUseCase` no ofrece paginacion ni inventario; aceptable segun el acceptance, a revisar en F-018 si se necesita.
- `GetEventUseCase` hace dos lecturas secuenciales; podrian ser concurrentes (`Mono.zip`) si la latencia importa.
