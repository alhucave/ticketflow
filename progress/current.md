# Estado actual

Feature en curso: F-021 — complimentary-issuance
Plan:
- Dominio: `OrderId.complimentaryFromIdempotencyKey` (namespace propio), `Order.complimentary` (sin ventana de reserva).
- Puerto `OrderPlacementRepository.issueComplimentary`: un TransactWriteItems (inventario available -> complimentary, orden, auditoria).
- `IssueComplimentaryUseCase` idempotente + `POST /events/{id}/complimentary` protegido por `X-Admin-Key` (AdminKeyWebFilter, deshabilitado por defecto).
- Tests unitarios/web + IT C12 con DynamoDB Local y E2E @SpringBootTest; verificar con docker-compose real.
Ultimas completadas: F-001 a F-020 (APPROVED)
