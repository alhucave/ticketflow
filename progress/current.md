# Estado actual

Feature en curso: F-024 — observability (in_progress; pendiente de reviewer, NO marcada done)
Plan:
- Puertos `BusinessMetrics` (usecase) y `OperationalMetrics` (infra) con NOOP + `MicrometerMetrics` (series fijas, etiquetas de enums), llamados desde casos de uso y adaptadores.
- Actuator en puerto de gestión propio (8081): solo health (liveness/readiness sin detalles), info y prometheus; 8080 responde 404 a /actuator/**.
- Readiness reactivo de DynamoDB y SQS (timeout + caché breve); gauges de cola con sondeo periódico no bloqueante (`QueueDepthMonitor`).
- Logs JSON ECS con correlationId (por propiedad; JSON en contenedor), consumer SQS restaura el correlationId del mensaje.
- docs/observability.md, README, docs/security.md, Dockerfile/compose/Healthcheck actualizados; informe en `progress/impl_observability.md`.

Ultimas completadas: F-001 a F-021 y F-023 (APPROVED)
Siguiente: F-022 (concurrency-tests, al final para validar el comportamiento definitivo), F-025 (docs-and-requests), F-026 (AWS docs)
