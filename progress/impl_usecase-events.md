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

## Fix: double inventory creation
Defecto: `CreateEventUseCase` llamaba `events.save` y luego `inventories.create`, pero `DynamoDbEventRepository.save` (F-007) ya escribe evento + inventario inicial (`Inventory.initial`: available = capacity, reserved/pending/sold/complimentary = 0, version 0) en un `TransactWriteItems` con `attribute_not_exists`; el segundo call fallaba siempre con `EventAlreadyExistsException` en DynamoDB real. (La decision "Create: save y luego inventories.create" de arriba queda obsoleta.)

Archivos:
- `usecase/CreateEventUseCase`: solo `events.save`; sin dependencia `InventoryRepository`; Javadoc del contrato.
- `domain/port/EventRepository`: Javadoc de `save` (persiste evento + inventario inicial atomicamente).
- `infrastructure/config/UseCaseConfig`: `createEventUseCase(events, ids, clock)`.
- Tests: `CreateEventUseCaseTest` (sin mock de inventarios; `verifyNoMoreInteractions(events)`, propaga error de save), `UseCaseConfigTest` actualizado.
- Nuevo `usecase/EventUseCasesIT` (@Tag integration, dynamodb-local:3.3.1, adaptadores reales + provisioner): create -> get (inventario = `Inventory.initial`, available 120, version 0) -> list lo contiene; input invalido (nombre en blanco, fecha pasada, capacidad 0) rechazado y conteos de ambas tablas sin cambios.

Verificacion: `./init.sh` -> BUILD SUCCESSFUL, `==> init.sh OK` (JaCoCo 90% verde).
`INCLUDE_INTEGRATION=true ./init.sh` (colima, DOCKER_HOST + TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE) -> BUILD SUCCESSFUL, `==> init.sh OK`; EventUseCasesIT ejecutado (2 tests).
