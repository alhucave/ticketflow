# Informe F-010 — usecase-events

## Archivos
Nuevos (main): `domain/exception/InvalidEventException`, `domain/port/IdGenerator`, `usecase/{CreateEventCommand,EventDetails,CreateEventUseCase,GetEventUseCase,ListEventsUseCase}`, `infrastructure/config/UseCaseConfig`.
Nuevos (test): `usecase/{CreateEventUseCaseTest,GetAndListEventsUseCaseTest,UseCaseArchitectureTest}`, `infrastructure/config/UseCaseConfigTest`.
Modificados: `docs/architecture.md` (nota de wiring), `feature_list.json` (F-010 in_progress), `progress/current.md`.

## Decisiones
- Use cases sin Spring (consistente con "usecase solo conoce puertos"); beans en `UseCaseConfig`, que tambien provee `Clock.systemUTC()` e `IdGenerator` (`EventId::generate`).
- `IdGenerator.nextEventId()` tipado (solo eventos por ahora; se ampliara cuando haga falta OrderId).
- Validacion en el use case antes de construir `Event` (el record lanza IAE): `InvalidEventException` para nombre/venue en blanco, fecha no estrictamente futura (Clock inyectado), capacidad <= 0 o comando nulo. Se ejecuta en `Mono.defer`, error reactivo, sin tocar puertos.
- Create: `events.save` y luego `inventories.create(Inventory.initial)`; devuelve el `Event`. No hay transaccion entre ambas tablas: si falla la creacion del inventario queda un evento sin inventario (Get responde EventNotFound en ese caso). Limitacion documentada; compensacion queda fuera de scope.
- Get: `EventDetails(event, inventory)`; evento o inventario ausente -> `EventNotFoundException`.
- List: devuelve `Flux<Event>` directo de `findAll()` (simple, sin paginacion ni inventario).
- ArchUnit: `UseCaseArchitectureTest` prohibe infrastructure, Spring y AWS en `usecase`.

## Verificacion
`./init.sh` -> BUILD SUCCESSFUL, `==> init.sh OK` (JaCoCo >= 90% verde).
`INCLUDE_INTEGRATION=true ./init.sh` (colima) -> BUILD SUCCESSFUL, `==> init.sh OK`.
