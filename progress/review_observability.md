# Review — feature F-024 (observability)

**Veredicto:** APPROVED

## Criterios de aceptación
- Puerto de métricas sin frameworks (usecase/BusinessMetrics, no-op por defecto) + adaptador Micrometer en infrastructure/observability: cubierto por MicrometerMetricsTest y UseCaseMetricsTest — [x]
- Nombres ticketflow.*; call sites (pedidos, replays, rechazos, vendidos, liberados, cortesía, ProcessOrder, conflictos, sweep + timer, consumer ok/failed/poison + timer, publisher ok/failed/retries, rate limit, 503): cubiertos por UseCaseMetricsTest, SqsOrderConsumerObservabilityTest, SqsOrderQueuePublisherMetricsTest, ReservationExpirationSchedulerMetricsTest, RateLimitMetricsTest, DependencyUnavailableMetricsTest, ObservabilityEndToEndIT — [x]
- Gauges de cola con poller no bloqueante, configurable, flag, fallo conserva último valor + contador de error: QueueDepthMonitorTest — [x]
- Actuator en puerto de gestión (solo health/info/prometheus), 8080 sin /actuator, show-details never: ObservabilityEndpointsTest, ObservabilityEndToEndIT; verificado con docker-compose (8081: health/liveness/readiness/info/prometheus 200; env/beans/heapdump/loggers/metrics 404; 8080 /actuator/* 404; puerto 8081 publicado solo en 127.0.0.1) — [x]
- Liveness independiente de externos; readiness reactiva, timeout, cache, solo UP/DOWN: DependencyHealthIndicatorTest, ReadinessDownEndToEndIT, ReadinessDownMissingQueueEndToEndIT — [x]
- Logs JSON con correlationId/service, patrón humano por defecto, sin secretos/excepciones al cliente: StructuredLoggingTest — [x]
- Consumer restaura correlationId validado (CorrelationId.resolve): SqsOrderConsumerObservabilityTest — [x]
- Dockerfile/compose/README/docs/security.md/docs/observability.md (español) actualizados — [x]
- E2E C12 con DynamoDB+SQS reales y Awaitility: ObservabilityEndToEndIT (3 tests, 0 fallos) — [x]

## Checkpoints (CHECKPOINTS.md)
- C1: [x] `./init.sh` y `INCLUDE_INTEGRATION=true ./init.sh` ejecutados por el reviewer, ambos en verde (la segunda con ITs de Testcontainers, 0 fallos).
- C2: [x] usecase/domain sin imports de Spring/Micrometer/AWS (grep vacío).
- C3: [x]
- C4: [x] sin `.block()` ni `Thread.sleep` en src/main (grep vacío); readiness con Mono.timeout + cache().
- C5: [x] sin cambios en la lógica de inventario.
- C6: [x] sin cambios de transiciones.
- C7: [x] sin Lombok; los `@Autowired` encontrados son de constructor y preexistentes.
- C8: [x] sin secretos; health no expone detalles ni causas (solo se registra la clase de la excepción).
- C9: [x] compuerta jacocoTestCoverageVerification (90%) pasa en init.sh.
- C10: [x]
- C11: [x]
- C12: [x] ejecutado: ObservabilityEndToEndIT y los ITs de casos de uso pasan con adaptadores reales.

## Verificaciones específicas
- Tags: todos los valores de tag provienen de enums fijos o literales ("result" ok/error, "queue"/"state" de enums del monitor); no hay Tag.of/.tags con entrada de usuario (orderId, eventId, idempotency key, direcciones, mensajes de excepción). Todas las series se registran al arranque a partir de los enums.
- Compose reverificado y apagado con `down -v`; la imagen sana con HEALTHCHECK sobre el puerto de gestión.

## Observaciones no bloqueantes
- Desviación documentada: `ticketflow.orders.placed` en lugar de `orders.created` (el sufijo `_created` está reservado por el cliente Prometheus); aceptable y documentada en docs/observability.md y MicrometerMetrics.
- Conviene que el main session confirme que el nombre `placed` le sirve a la especificación original.
