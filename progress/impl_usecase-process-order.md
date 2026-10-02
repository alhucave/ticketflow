# Informe: F-014 usecase-process-order

Rama: `feature/F-014-usecase-process-order` (desde main a45e909). Sin push, sin PR, feature en `in_progress`.

## Archivos
Nuevos:
- `domain/port/OrderFulfillmentRepository` (markPendingConfirmation, confirmSale).
- `infrastructure/persistence/DynamoDbOrderFulfillmentRepository`.
- `usecase/ProcessOrderUseCase`, `usecase/ProcessOrderResult` (sealed: Sold, ReleasedAsExpired, AlreadyProcessed(status), OrderMissing).
- Tests: `ProcessOrderUseCaseTest`, `ProcessOrderUseCaseIT`, `DynamoDbOrderFulfillmentRepositoryTest`, `DynamoDbOrderFulfillmentRepositoryIT`.
Modificados: `DynamoDbOrderPlacementRepository` (helpers `moveInventory`, `updateOrder(order, expected, target)`, `putAudit`, `isConditionFailure`, `hasItem`, `n` pasan a package-private para reutilizarlos; comportamiento de placement/release sin cambios), `UseCaseConfig` (bean `processOrderUseCase`), `UseCaseConfigTest`, `PortsContractTest`, `docs/architecture.md` (flujo paso 2), `feature_list.json` (in_progress), `progress/current.md`.

## Decisiones de diseno
- Puerto nuevo cohesivo `OrderFulfillmentRepository` en vez de ensanchar `OrderPlacementRepository`: placement = crear/compensar reserva; fulfillment = reserva -> venta. `InventoryRepository` no cambia (el contador pendingConfirmation se mueve solo dentro de la transaccion de orden; las operaciones sueltas de inventario seguirian sin ser atomicas con la orden).
- Cada paso = un `TransactWriteItems` `[0] update orden (status, eventId y quantity condicionados), [1] audit put, [2] update inventario (source >= qty, version+1)`. Mapeo: condicion de orden -> `OrderNotFoundException`/`OrderStatusConflictException` (con estado real); condicion de inventario -> `InsufficientInventoryException` (item presente) o `EventNotFoundException`. Condicion de orden tiene precedencia. Transitorios (TransactionConflict, throttling) se reintentan con la misma politica que placement.
- Caso de uso: lectura consistente (`findById` ya usa `consistentRead`). Conflicto -> relee y reevalua (maximo `MAX_ATTEMPTS=5` lecturas; cada conflicto implica progreso de otro worker y una orden tiene a lo sumo 2 pasos + liberacion). El perdedor del paso 1 que encuentra PENDING_CONFIRMATION ayuda a completar la venta; sigue habiendo exactamente un `Sold` porque `confirmSale` solo lo gana uno. Agotados los intentos se propaga el conflicto (reintento SQS). `OrderNotFoundException` durante un paso -> `OrderMissing`. Errores transitorios propagan.
- Expiracion: `!reservationExpiresAt.isAfter(now)` = expirada (borde exacto expira). Se evalua una vez por lectura; una reserva valida al pasar a PENDING se confirma en la misma ejecucion (el reloj se relee solo para el timestamp de auditoria).
- Razon de liberacion `reservation expired`, actor `order-processor`.

## Defectos / observaciones encontrados (no corregidos, fuera de scope)
1. Clave de ordenacion de auditoria `<timestamp>#<uuid>`: dos entradas del mismo instante se ordenan aleatoriamente. Con reloj fijo el IT fallaba de forma intermitente (SOLD antes que PENDING_CONFIRMATION en `findAuditTrail`); en produccion el reloj real tiene resolucion de microsegundos y los pasos de una orden estan serializados por la condicion de estado, pero skew entre nodos podria invertir el orden. Mitigacion en los ITs: reloj que avanza 1 ms por lectura. Sugerencia: anadir un contador de secuencia por orden si se necesita orden estricto.
2. `docs/architecture.md` (paso 4, scheduler) dice `expiresAt < now`, mientras F-014 trata `expiresAt <= now` como expirada. Inconsistencia menor de borde; F-017 deberia alinearla.
3. Sin mecanismo de reentrega de un mensaje si el proceso muere entre `markPendingConfirmation` y `confirmSale`: lo cubre la reentrega SQS (F-016) o el barrido de expiracion (F-017); verificado en el IT de recuperacion tras caida.

## Verificacion
- Unitarios: todas las ramas del caso de uso (missing, SOLD/COMPLIMENTARY/AVAILABLE, RESERVED valido, PENDING valido, expirada desde ambos estados, bordes, perdedor en paso 1/confirm/release, conflicto agotado, not found, errores propagados) y del adaptador (exito, condiciones, precedencia, retry).
- ITs (Testcontainers, adaptadores reales, cola falsa): compra->proceso->SOLD con contadores (sold=qty, reserved=0, pending=0, available reducido, version 3), auditoria RESERVED->PENDING->SOLD, mismo mensaje 3 veces, 20 workers en paralelo sobre la misma orden (un solo Sold), recuperacion tras caida, expirada (desde RESERVED y PENDING) sin venta, AVAILABLE/COMPLIMENTARY/SOLD sin escrituras, missing, 30 ordenes en paralelo, 10 ordenes x 3 entregas concurrentes. ITs del adaptador incl. contador corrupto. Sin sleeps; ejecutados 8 veces seguidas en verde tras fijar el reloj.
- `./init.sh`: `BUILD SUCCESSFUL ... ==> init.sh OK` (exit 0).
- `INCLUDE_INTEGRATION=true ./init.sh`: `BUILD SUCCESSFUL ... ==> init.sh OK` (exit 0); 360 tests, 0 fallos, 0 omitidos; cobertura de lineas 99.4%.
- Sin `.block()` en codigo de produccion (grep vacio); dominio sin dependencias nuevas.
