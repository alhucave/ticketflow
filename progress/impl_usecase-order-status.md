# F-013 usecase-order-status — informe

## Archivos
Nuevos (src/main/java/com/ticketflow/usecase): `GetOrderStatusUseCase`, `OrderStatusView` (orderId, eventId, quantity, status, reservationExpiresAt, createdAt).
Modificados: `infrastructure/config/UseCaseConfig` (bean `getOrderStatusUseCase`).
Tests: `GetOrderStatusUseCaseTest` (5 estados + not-found, mock + StepVerifier), `GetOrderStatusUseCaseIT` (DynamoDB Local real: RESERVED con expiración tras compra, RESERVED -> PENDING_CONFIRMATION -> SOLD, AVAILABLE tras liberar, COMPLIMENTARY via save AVAILABLE + transition real, id desconocido), `UseCaseConfigTest` (bean), `DynamoDbOrderRepositoryTest.findById_usesConsistentRead`.

## Decisiones
- `OrderRepository.findById` emite vacío si no existe (contrato del puerto y del adaptador); el caso de uso lo traduce con `switchIfEmpty` a `OrderNotFoundException`.
- `DynamoDbOrderRepository.findById` ya usaba `consistentRead(true)`: no se cambió producción; solo se añadió el test que lo fija.
- Sin `.block()` en producción; dominio intacto. Vista devuelve los value objects del dominio (OrderId, EventId, Quantity), igual que `RequestPurchaseResult`.
- COMPLIMENTARY: el modelo permite AVAILABLE -> COMPLIMENTARY, se usa `transition` real.

## Verificación
- `./init.sh`: BUILD SUCCESSFUL, `==> init.sh OK` (gate JaCoCo 90% OK).
- `INCLUDE_INTEGRATION=true ./init.sh` (Colima, DOCKER_HOST por entorno): BUILD SUCCESSFUL, `==> init.sh OK`; GetOrderStatusUseCaseIT ejecutó 5 tests sin fallos ni omitidos.
