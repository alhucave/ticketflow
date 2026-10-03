# Estado actual

Feature en curso: F-020 — web-error-handling
Plan:
- ApiExceptionHandler: un unico traductor Throwable -> problem+json (mapeos de dominio, framework, catch-all 500) compartido con un WebExceptionHandler (@Order -2) para errores fuera del dispatcher (ruta inexistente, 405, filtros).
- CorrelationIdWebFilter: valida/genera X-Correlation-Id, contexto Reactor `correlationId`, MDC via micrometer context-propagation, cabecera en todas las respuestas.
- RateLimitExceededException (429 + Retry-After) para F-023.
- Tests: slice WebFluxTest + MDC concurrente + IT end-to-end (DynamoDB + SQS reales).
- README / docs/architecture.md y verificacion con docker-compose real.
