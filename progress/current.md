# Estado actual

Feature en curso: F-023 — security-hardening (PARTE 1 de 2: hardening a nivel de aplicación, rama `feature/F-023a-app-hardening`; la parte 2 —CI scans, dependabot, compose/contenedor, docs/security.md— queda pendiente; F-023 sigue `in_progress`)
Plan:
- Rate limiting por cliente (token bucket + Caffeine acotado) en escrituras y límite de intentos fallidos de `X-Admin-Key`.
- Límites de entrada: tamaño de body (413), cantidad máxima configurable, `Idempotency-Key` 16-128, ids de ruta validados sin eco.
- Seguridad de reintentos: republicar en replay de `RESERVED` y desacoplar reserva+publicación de la cancelación del cliente.
- Ciclo de vida: token de generación por arranque en `SqsOrderConsumer` y `ReservationExpirationScheduler`.
- Mapeo de fallos transitorios a 503 + `Retry-After`, cabeceras de seguridad y ocultar secretos en `toString`.
Ultimas completadas: F-001 a F-021 (APPROVED). Fase 7 completa.
Siguiente: F-023 parte 2, F-022, F-024, F-025; Fase 9 — F-026 (AWS docs)
