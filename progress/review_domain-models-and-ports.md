# Review — feature F-005 (domain-models-and-ports)

**Veredicto:** APPROVED

Re-review completa desde cero (commits 82e7ea3 + 61dcaeb). `./init.sh` ejecutado de forma independiente: BUILD SUCCESSFUL, tests + jacocoTestCoverageVerification (90%) en verde, `==> init.sh OK`.

## Criterios de aceptación
- Records validan invariantes en compact constructors (cantidad positiva, nombre no blank, capacity > 0): Quantity, Event (name, venue, capacity), Inventory, Order, ids; cubierto por ValueObjectsTest, EventTest, InventoryTest, OrderTest — [x]
- Inventory impone available+reserved+pendingConfirmation+sold+complimentary = capacity: Inventory.java (suma en long, sin overflow); InventoryTest (suma menor, mayor, overflow, negativos) — [x]
- Domain sin imports de Spring ni AWS: DomainArchitectureTest (ArchUnit; incluye sanity de que importa clases, sin dependencias a usecase/infrastructure, Reactor solo en port, ports son interfaces) — [x]

## Checkpoints (CHECKPOINTS.md)
- C1: [x] init.sh verde, ejecutado por el reviewer
- C2: [x] domain sin Spring/AWS; Reactor solo en domain.port (verificado por ArchUnit y documentado en docs/architecture.md)
- C3: [x] cada criterio tiene tests con aserciones reales
- C4: [x] sin `.block()` ni `Thread.sleep` en src/main
- C5: [x] el puerto InventoryRepository.transition exige conditional write con expectedVersion y stock suficiente (la implementación es de F-008)
- C6: [x] Order.transitionTo y OrderAuditEntry validan contra TicketStatus; la auditoría tiene timestamp, from, to y actor
- C7: [x] inglés, sin Lombok ni @Autowired
- C8: [x] sin secretos
- C9: [x] cobertura global >= 90% (gate de JaCoCo en verde)
- C10: [x] scope acotado: dominio, tests, dependencia ArchUnit en testImplementation, ajuste de docs/architecture.md y progress
- C11: [x] docs/architecture.md y package-info actualizados

## Comparación con la especificación original
- Eventos (nombre, fecha, venue, capacidad total): Event(id, name, startsAt, venue, capacity), todos validados. [x]
- Cinco estados: Order.status usa TicketStatus (los cinco) y Inventory tiene un contador por estado. [x]
- Reserva de 10 minutos: el modelo guarda `reservationExpiresAt` (> createdAt) y el puerto expone `findExpiredReservations(now)`. El cálculo de now+10min es de F-012 (con Clock), no de esta feature. [x]
- Idempotencia: IdempotencyKey (no blank, máx. 128) en Order, y `OrderRepository.findByIdempotencyKey`. [x]
- Sin brechas bloqueantes.

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
1. TicketStatus no tiene estado de orden fallida o expirada. F-012 ("mark the order failed") y F-017 ("expired") lo necesitarán. Hay que decidir allí cómo representarlo, por ejemplo con un estado nuevo o un modelo de orden aparte, sin romper la matriz 5x5 de F-004.
2. `ConcurrentModificationException` tiene el mismo nombre simple que `java.util.ConcurrentModificationException`. Puede dar confusión con imports; conviene tenerlo presente en F-008.
3. `InventoryRepository.transition` es genérico; F-008 pide reserve/confirmSale/release/issueComplimentary, que se pueden implementar como envoltorios de `transition`. El puerto no pierde expresividad.
4. Event no valida que la fecha esté en el futuro. Es correcto: eso se valida en el caso de uso (F-010).
