# Estado actual

Feature en curso: F-014 — usecase-process-order
Plan:
- Nuevo puerto `OrderFulfillmentRepository` (markPendingConfirmation, confirmSale): orden + auditoria + contador de inventario en un TransactWriteItems; adaptador DynamoDB reutilizando helpers de DynamoDbOrderPlacementRepository.
- `ProcessOrderUseCase` (maquina de estados idempotente, resultado sellado, perdedor de carrera relee la orden), cableado en UseCaseConfig.
- Tests unitarios (StepVerifier) + ITs Testcontainers con adaptadores reales (concurrencia, recuperacion tras caida, expiracion).
- Actualizar docs/architecture.md; correr ./init.sh en ambas variantes.
