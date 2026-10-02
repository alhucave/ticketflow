# Estado actual

Feature en curso: F-015 — sqs-publisher
Plan:
- Adaptador `SqsOrderQueuePublisher` (SDK v2 async, Jackson 3, retry solo transitorios, URL de cola lazy+cache).
- `SqsProperties`/`SqsConfig`; se elimina el fallback de `UseCaseConfig`.
- Tests unitarios (StepVerifier), IT LocalStack y IT C12 de punta a punta.
- docker-compose y README (SQS, at-least-once).
Estado: implementado, pendiente de revision (ver progress/impl_sqs-publisher.md).
