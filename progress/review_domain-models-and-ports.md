# Review — feature F-005 (domain-models-and-ports)

**Veredicto:** APPROVED

## Criterios de aceptación
- Records validate invariants in compact constructors (positive quantity, non-blank name, capacity > 0): ValueObjectsTest, EventTest, InventoryTest, OrderTest, OrderAuditEntryTest — [x]
- Inventory enforces sum = capacity: InventoryTest (sum below/above, long-overflow, negative counters, capacity<=0, version) — [x]; Inventory.java computes the sum in long and rejects mismatches.
- Domain has no Spring/AWS imports (ArchUnit): DomainArchitectureTest (Spring/AWS, outer layers, Reactor not in model/exception, ports are interfaces) — [x]

## Checkpoints
- C1: [x] ./init.sh run independently by reviewer: BUILD SUCCESSFUL, init.sh OK
- C2: [x] grep shows no non-java/non-ticketflow imports in model/exception; reactor only in domain/port
- C3: [x]
- C4: [x] no block()/sleep
- C5: [x] InventoryRepository.transition is documented as conditional on version and sufficient stock (contract only; adapter is a later feature)
- C6: [x] Order.transitionTo and OrderAuditEntry reuse TicketStatus (F-004) rules; invalid from->to rejected
- C7: [x] English, no Lombok/@Autowired
- C8: [x]
- C9: [x] jacocoTestCoverageVerification passed in init.sh
- C10: [x] extras (Event fields, Inventory.initial, saveAuditEntry/findExpiredReservations) are reasonable port/model support; no unrelated changes
- C11: [x] docs/architecture.md updated (Reactor only in domain.port); package-info updated

## Specific checks
- ArchUnit 1.5.1: confirmed real; repo1.maven.org maven-metadata.xml lists latest/release 1.5.1.
- Inventory invariant: enforced in the compact constructor, overflow-safe.
- Reactor only in domain.port: confirmed (grep + ArchUnit test).
- TicketStatus reuse: no duplicated enum or transition logic; Order and OrderAuditEntry delegate to it.

## Cambios requeridos
None.

## Observaciones no bloqueantes
- Event.capacity vs Inventory.capacity consistency is not enforced at the model level; leave to use cases.
- Port-level conditional-write contract is only documented in Javadoc; adapter features must test it.
- The docs/architecture.md line 22 says usecase may use reactor-core; the domain.port exception is now consistent with it.
