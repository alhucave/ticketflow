# Estado actual

Feature en curso: F-019 — web-orders-availability-api
Plan:
- DTOs + `OrderController` (POST /orders con Idempotency-Key validada, GET /orders/{id}) y `AvailabilityController` (snapshot + SSE con Retry.backoff).
- Ampliar `ApiExceptionHandler` (409/404/503/400) reutilizando el helper `problem(...)`.
- Tests de slice con WebTestClient + StepVerifier (tiempo virtual) para el SSE.
- IT C12 `OrdersApiEndToEndIT` con DynamoDB Local 3.3.1 + LocalStack SQS 4.14.0, consumer activo.
- Verificar `./init.sh`, variante de integracion y docker-compose real; README (Endpoints).
