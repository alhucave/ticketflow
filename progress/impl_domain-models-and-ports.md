# Informe F-005 — domain-models-and-ports

Rama: `feature/F-005-domain-models-and-ports` (sin push). Estado en feature_list.json: `in_progress`.

## Archivos
Main (`com.ticketflow.domain`):
- model: EventId, OrderId, Quantity, IdempotencyKey, Event, Inventory, Order, OrderAuditEntry
- exception: EventNotFoundException, OrderNotFoundException, InsufficientInventoryException, ReservationExpiredException, ConcurrentModificationException
- port: EventRepository, InventoryRepository, OrderRepository, OrderQueuePublisher
- package-info.java (texto actualizado)
Test: ValueObjectsTest, EventTest, InventoryTest, OrderTest, OrderAuditEntryTest, DomainExceptionsTest, DomainArchitectureTest (ArchUnit), PortsContractTest.
Otros: build.gradle.kts (+ `testImplementation com.tngtech.archunit:archunit:1.5.1`), docs/architecture.md (domain permite Reactor solo en port), progress/current.md.

## Decisiones
- ArchUnit 1.5.1: verificado en repo1.maven.org maven-metadata.xml (release 1.5.1, lastUpdated 2026-09-25). La API search.maven.org devolvia 1.4.1 (indice desactualizado), se descarto. Funciona con clases Java 25 (5 tests ejecutados).
- Reactor permitido en `domain.port` (pedido por el leader); AGENTS/architecture decian "sin Reactor", se ajusto la doc. ArchUnit prohibe Reactor en model/exception y exige que port sean interfaces.
- Inventory: suma calculada en long (evita overflow), negativos y capacity<=0 rechazados, `Inventory.initial(...)`.
- Order: valida no-null y expiresAt > createdAt; `transitionTo` delega en TicketStatus (reusa F-004). OrderAuditEntry valida que from->to sea valida (C6) y actor no blank.
- Event: id, name, startsAt, capacity (campos no especificados por la feature; minimo razonable).
- InventoryRepository expone `transition(eventId, from, to, quantity, expectedVersion)` generico: conditional write con version e inventario suficiente; documenta InsufficientInventory/ConcurrentModification. Auditoria y busqueda de expirados viven en OrderRepository (`saveAuditEntry`, `findAuditTrail`, `findExpiredReservations`).
- Excepciones extienden RuntimeException, con sufijo `Exception` (coherente con InvalidStateTransitionException).

## Salida de ./init.sh
```
==> Validating feature_list.json
OK: 26 features, in_progress=[]
==> Building and verifying (tests + 90% coverage gate)
> Task :test
> Task :jacocoTestCoverageVerification
> Task :check
> Task :build
> Task :jacocoTestReport
BUILD SUCCESSFUL in 19s
==> init.sh OK
```
(Ejecutado antes de pasar F-005 a in_progress; el estado se cambia despues y la validacion de init.sh lo permite: max 1 in_progress.)
