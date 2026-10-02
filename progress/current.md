# Estado actual

Feature en curso: F-005 — domain-models-and-ports (implementada, pendiente de review)
Plan:
- Value objects (EventId, OrderId, Quantity, IdempotencyKey) y records (Event, Inventory, Order, OrderAuditEntry) con validacion en constructor compacto
- Excepciones de dominio y puertos reactivos (Mono/Flux)
- ArchUnit 1.5.1 (test) para pureza del dominio
- Tests unitarios; gate JaCoCo 90% en verde
Ultimas completadas: F-001, F-002, F-003, F-004 (APPROVED)
