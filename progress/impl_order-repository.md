# Informe F-009 — order-repository

## Archivos
Nuevos: `infrastructure/persistence/DynamoDbOrderRepository.java`, `domain/exception/OrderAlreadyExistsException.java`, `domain/exception/OrderStatusConflictException.java`, tests `DynamoDbOrderRepositoryTest` (mocks) y `DynamoDbOrderRepositoryIT` (Testcontainers, 15 tests).
Modificados: `domain/port/OrderRepository.java` (nuevo `transition`), `DynamoDbTables.java` (solo javadoc), `DomainExceptionsTest`, `docs/architecture.md`, `feature_list.json` (in_progress), `progress/current.md`.

## Decisiones de diseño
- El puerto no tenia operacion de transicion: se anadio `transition(id, expected, target, actor, at)` que devuelve el `OrderAuditEntry` escrito (evita una lectura posterior racy). `save` actua como create con guarda `attribute_not_exists(orderId)` -> `OrderAlreadyExistsException`.
- Transicion = `TransactWriteItems` (Update condicionado a `attribute_exists AND status = :expected` + Put de auditoria). Se valida la maquina de estados (via `OrderAuditEntry`/`TicketStatus`) antes de tocar DynamoDB. `ReturnValuesOnConditionCheckFailure=ALL_OLD` distingue orden inexistente (`OrderNotFoundException`) de estado obsoleto (`OrderStatusConflictException` con el estado real).
- SK de `order_audit` = `<timestamp 9 decimales>#<uuid>`; el esquema (`timestamp` como S) no cambia, solo su contenido; documentado en architecture.md y javadoc.
- Timestamps de claves con formato de ancho fijo (9 decimales) porque `Instant.toString()` varia de longitud y rompe el orden lexicografico (afecta `reservationExpiresAt < :now` del GSI). Los datos de F-008/otros no usan este campo todavia.
- Reintentos (`Retry.backoff`) solo para errores transitorios: throttling, 5xx, y `TransactionCanceledException` cuyas razones son TransactionConflict/Throttling (sin ConditionalCheckFailed). Tras reintentar, el perdedor de una carrera ve el conflicto de estado real.
- `findExpiredReservations`: una Query al GSI por cada estado (RESERVED, PENDING_CONFIRMATION) con `reservationExpiresAt < :now` (estricto), con paginacion.
- Sin `.block()` en produccion; dominio sin Spring/AWS.

## Preguntas abiertas / limitaciones
- TicketStatus no tiene estado failed/expired y no se invento uno. F-012 ("mark the order failed") y F-017 ("transitions it to expired") lo necesitan: con las transiciones actuales una orden expirada/fallida solo puede volver a `AVAILABLE` (RESERVED/PENDING -> AVAILABLE), que pasaria a ser su estado terminal; pero entonces una orden AVAILABLE queda sin marca de por que. Decision de producto pendiente (anadir `EXPIRED`/`FAILED` o reutilizar `AVAILABLE`).
- Un GSI no garantiza unicidad: dos `save` concurrentes con la misma idempotencyKey pero distinto orderId pueden crear dos ordenes. F-012 debe usar un orderId derivado de la key o una escritura de reserva de clave; fuera de scope aqui.
- Las lecturas por GSI (`findByIdempotencyKey`, `findExpiredReservations`) son eventualmente consistentes (limitacion de DynamoDB).
- `InventoryRepository` sigue sin operaciones de `pendingConfirmation` (ya anotado en F-014).

## Verificacion
- `INCLUDE_INTEGRATION=true ./init.sh` (Colima): BUILD SUCCESSFUL, "init.sh OK"; DynamoDbOrderRepositoryIT: tests=15 failures=0 errors=0.
- `./init.sh` (sin Docker): BUILD SUCCESSFUL, jacocoTestCoverageVerification (90%) OK, "init.sh OK".
