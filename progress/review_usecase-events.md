# Review — feature F-010 (usecase-events)

**Veredicto:** APPROVED

`./init.sh` y `INCLUDE_INTEGRATION=true ./init.sh` (colima) ejecutados por el reviewer: ambos BUILD SUCCESSFUL, `init.sh OK`, JaCoCo >= 90%.

## Criterios de aceptación
- Rechaza input inválido con error de dominio de validación: CreateEventUseCaseTest (nombre, venue, fecha no futura con Clock fijo, capacidad <= 0, comando nulo; verifyNoInteractions de puertos) — [x]
- Devuelve evento con snapshot de inventario: GetAndListEventsUseCaseTest.getEvent_existing_returnsEventWithInventorySnapshot — [x]
- Id desconocido -> EventNotFoundException: getEvent_unknownId / getEvent_missingInventory — [x]
- Creación guarda evento e inicializa inventario (available = capacity): execute_validCommand_savesEventAndInitializesInventory — [x]
- Todo con StepVerifier, sin .block() — [x]
- ArchUnit usecase (sin infrastructure/Spring/AWS): UseCaseArchitectureTest — [x]
- Clock inyectado, IdGenerator como puerto en domain.port, excepción tipada InvalidEventException en domain.exception — [x]

## Contraste con la spec original
Crear y consultar eventos (nombre, fecha, lugar, capacidad) con inventario inicial = capacidad y consulta con inventario actual: cubierto. La actualización del inventario por compras corresponde a features posteriores.

## Checkpoints
- C1: [x]
- C2: [x] usecase solo importa domain y reactor/java.time; ArchUnit lo verifica
- C3: [x]
- C4: [x] sin .block()/Thread.sleep en src/main
- C5: [x] N/A (solo creación inicial vía puerto)
- C6: [x] N/A
- C7: [x]
- C8: [x]
- C9: [x]
- C10: [x] UseCaseConfig y la nota en architecture.md son wiring necesario
- C11: [x] architecture.md actualizado

## Observaciones no bloqueantes
- CreateEventUseCase.java:34-37: sin transacción entre events.save e inventories.create; un fallo deja un evento sin inventario (documentado por el implementer; Get responde not found).
- IdGenerator solo expone nextEventId; se ampliará con OrderId.
- UseCaseConfigTest solo comprueba que los beans se construyen (cobertura de config).
