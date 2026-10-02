# Informe: F-011 usecase-availability

## Archivos
- Nuevo `usecase/Availability.java`: record snapshot (eventId, available, reserved, pendingConfirmation, sold, complimentary, capacity).
- Nuevo `usecase/GetAvailabilityUseCase.java`: `execute(EventId)` (Mono) y `stream(EventId)` (Flux).
- Modificado `infrastructure/config/UseCaseConfig.java`: bean con `ticketflow.availability.poll-interval` (default 1s) y `Schedulers.parallel()`.
- Nuevo `test/.../GetAvailabilityUseCaseTest.java` (6 tests, StepVerifier.withVirtualTime).
- Modificado `test/.../EventUseCasesIT.java`: 2 tests nuevos con adaptadores DynamoDB reales (C12).
- `feature_list.json` (in_progress) y `progress/current.md`.

## Decisiones
- Solo depende de `InventoryRepository.findByEventId`: el inventario existe siempre que existe el evento (EventRepository.save los crea atomicamente); vacio => `EventNotFoundException`. Evita una lectura extra por poll.
- `available` del inventario ya excluye reserved/pending (invariante); el snapshot los expone por separado.
- Stream: `Flux.interval(Duration.ZERO, poll, scheduler)` + `onBackpressureDrop` + `concatMap(..,1)` + `distinctUntilChanged`; primer valor inmediato, emite solo al cambiar, nunca completa solo (cancelar detiene el polling), error de evento desconocido termina el flujo. Scheduler y Duration inyectables; intervalo no positivo se rechaza.
- Sin `.block()` en produccion.

## Verificacion
- `./init.sh` (sin Docker): BUILD SUCCESSFUL, `==> init.sh OK` (gate JaCoCo 90% ok).
- `INCLUDE_INTEGRATION=true ./init.sh` (Colima): BUILD SUCCESSFUL, `==> init.sh OK`; EventUseCasesIT 4/4, GetAvailabilityUseCaseTest 6/6, 0 fallos.
- IT prueba: crear evento => available=capacity; tras `reserve(5)` snapshot reserved=5, available=45; el stream emite el cambio; evento desconocido => EventNotFound (snapshot y stream).
