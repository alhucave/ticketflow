# Estado actual

Feature en curso: F-012 — usecase-purchase-request (implementada, pendiente de reviewer)
Plan:
- Puerto `OrderPlacementRepository` con `placeReservation` y `releaseReservation` (un `TransactWriteItems` cada uno).
- `orderId` derivado del Idempotency-Key (`OrderId.fromIdempotencyKey`) para unicidad por clave.
- `RequestPurchaseUseCase`: transaccion, publicar despues, compensar si falla el publish.
- Tests unitarios (StepVerifier) + IT con DynamoDB Local (concurrencia, idempotencia, compensacion).
- Informe en `progress/impl_usecase-purchase-request.md`.
