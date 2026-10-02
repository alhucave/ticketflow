# Estado actual

Feature en curso: F-009 — order-repository
Plan:
- Extender el puerto OrderRepository con `transition(id, expected, target, actor, at)` (cond. sobre estado esperado + auditoria en TransactWriteItems).
- Nuevas excepciones de dominio: OrderAlreadyExistsException, OrderStatusConflictException.
- DynamoDbOrderRepository (save con attribute_not_exists, findById, GSI idempotencia, GSI status+expiry, audit SK = timestamp#uuid).
- Tests unitarios con mocks + IT con Testcontainers (concurrencia, mismo instante, duplicado, findExpired).
- Doc: architecture.md/README (SK de order_audit, timestamps de ancho fijo).
