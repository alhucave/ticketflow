# Informe de implementación: F-024 observability (issue #24)

Estado: implementada y verificada; **pendiente de reviewer** (no marcada `done`). Rama `feature/F-024-observability`, commit local (sin push, sin PR).

## Archivos

**Nuevos (main)**
- `usecase/BusinessMetrics.java`: puerto sin framework (enums fijos de etiquetas, `NOOP`).
- `infrastructure/observability/OperationalMetrics.java`, `MicrometerMetrics.java` (implementa ambos puertos, series registradas al arrancar), `DependencyHealthIndicator.java` (readiness reactivo con timeout y caché), `QueueDepthMonitor.java` (gauges de cola con sondeo en segundo plano).
- `infrastructure/config/ObservabilityConfig.java`, `ObservabilityProperties.java`.
- `docs/observability.md`.

**Modificados (main)**
- Casos de uso: `RequestPurchaseUseCase`, `ProcessOrderUseCase`, `ReleaseExpiredReservationsUseCase`, `IssueComplimentaryUseCase` (constructor nuevo con `BusinessMetrics`; el antiguo delega con `NOOP`). `RequestPurchaseUseCase` añade un INFO «Order X placed» (primera línea de la traza de una orden).
- Adaptadores: `SqsOrderConsumer` (restaura `correlationId`, métricas, `messageAttributeNames`), `SqsOrderQueuePublisher` (publicaciones/reintentos), `ReservationExpirationScheduler` (resultado y duración del barrido), `RateLimitWebFilter`, `AdminKeyWebFilter`, `ApiExceptionHandler`, `ProblemWebExceptionHandler` (503 por dependencia).
- Config: `UseCaseConfig`, `SqsConfig`, `SqsConsumerConfig`, `ExpirationSchedulerConfig`, `RateLimitConfig`, `application.yml`, `build.gradle.kts` (+`micrometer-registry-prometheus`, filtro `@projectVersion@` en `application.yml`), `gradle.lockfile`.
- Contenedor: `Dockerfile`, `docker-compose.yml`, `docker/healthcheck/Healthcheck.java`, `.env.example`.
- Docs: `README.md`, `docs/security.md`, `docs/architecture.md`, `progress/current.md`; `feature_list.json` (F-024 `in_progress`).

**Tests nuevos**: `UseCaseMetricsTest`, `MicrometerMetricsTest` (incluye regla de cardinalidad), `DependencyHealthIndicatorTest` (UP/DOWN/timeout/caché/concurrencia), `QueueDepthMonitorTest`, `SqsOrderConsumerObservabilityTest` (correlationId del mensaje en contexto/MDC/logs, métricas), `SqsOrderQueuePublisherMetricsTest`, `ReservationExpirationSchedulerMetricsTest`, `RateLimitMetricsTest`, `DependencyUnavailableMetricsTest`, `ObservabilityConfigTest`, `ObservabilityEndpointsTest` (sin Docker: readiness DOWN determinista con puertos cerrados, liveness UP, 404 en el puerto público, endpoints no expuestos), `StructuredLoggingTest` + helper `JsonLogCapture` (línea JSON parseada con el correlationId, campos de servicio, traza, **sin secretos**), `PrometheusScrape` (helper), ITs C12: `ObservabilityEndToEndIT`, `ReadinessDownEndToEndIT`, `ReadinessDownMissingQueueEndToEndIT`. `src/test/resources/application.properties` fija `management.server.port=0` (los contextos de test cacheados no deben pelear por el 8081). `HealthEndpointTest` se sustituye por `ObservabilityEndpointsTest`; `HardeningEndToEndIT` ahora espera `404` en `/actuator/health` del puerto público.

## Decisiones de diseño (y por qué)

1. **Puertos de métricas**: dos interfaces (`BusinessMetrics` en `usecase`, `OperationalMetrics` en `infrastructure.observability`) con `NOOP`, una sola implementación Micrometer. `domain`/`usecase` siguen sin Micrometer (ArchUnit en verde). Las etiquetas salen **solo** de enums (`tag()`); los métodos no aceptan texto libre. Las series se registran todas al arrancar (a 0) para que las alertas no vean series ausentes. Probado: claves/valores de etiqueta de todas las series pertenecen a conjuntos fijos, usar las métricas no crea series nuevas, ninguna etiqueta se llama `orderId`/`eventId`/`key`/`ip`...
2. **Métricas opcionales en la capa web/config**: `ApiExceptionHandler`, `ProblemWebExceptionHandler`, `AdminKeyWebFilter`, `RateLimitConfig`, `SqsConfig`, `SqsConsumerConfig`, `ExpirationSchedulerConfig` reciben `ObjectProvider<OperationalMetrics>` (NOOP si no hay bean) para no romper los tests de slice (`@WebFluxTest`, `ApplicationContextRunner`) que importan solo esas clases.
3. **Gauges de cola**: `QueueDepthMonitor` (`SmartLifecycle`), `Flux.interval` + `concatMap` (sin solapes), timeout por sondeo, DLQ descubierta por la *redrive policy* de la cola y resuelta con `SqsQueueUrlResolver`. En fallo conserva el último valor, cuenta `queue.stats.refreshes{result=error}` y crece `queue.stats.age.seconds`. Antes del primer éxito: `NaN`. **Activación por defecto `false`** (como el consumer; docker-compose la activa). Edad del mensaje más antiguo: **no expuesta** (`GetQueueAttributes` no la da; solo CloudWatch), documentado.
4. **Actuator**: puerto de gestión separado (`8081`), solo `health,info,prometheus`, `show-details/components: never`, grupos `liveness` (solo `livenessState`) y `readiness` (`readinessState,dynamodb,sqs`). Artefacto verificado: `io.micrometer:micrometer-registry-prometheus` **1.17.1** (BOM de Micrometer vía Spring Boot 4.1.1; cliente Prometheus `prometheus-metrics-*` 1.7.0; el `*-simpleclient` está en desuso). Dirección por defecto `127.0.0.1` (una ejecución local no lo expone a la LAN); la imagen y compose fijan `0.0.0.0` dentro del contenedor y compose lo publica solo en `127.0.0.1:8081`.
5. **Healthcheck del contenedor = liveness** del puerto de gestión (no readiness): una caída de DynamoDB/SQS no debe marcar el contenedor unhealthy. Readiness se verifica aparte.
6. **Readiness**: `DependencyHealthIndicator` reactivo (`DescribeTable orders` y `GetQueueUrl`/`GetQueueAttributes`), timeout 2 s, caché 5 s (también del DOWN, y comparte la llamada en vuelo). Respuesta solo `UP`/`DOWN`; el motivo (clase de la excepción) va al log.
7. **Logs JSON**: formato ECS integrado de Spring Boot 4.1.1 (`logging.structured.format.console=ecs`; también existen `logstash`/`gelf`). `correlationId` sale del MDC como campo de primer nivel en cada línea; `service.name/version/environment` por `logging.structured.ecs.service.*` (`version` se rellena al construir con `@projectVersion@`; `environment` = `TICKETFLOW_ENVIRONMENT`). JSON por defecto en el contenedor (`ENV` del Dockerfile + `environment` de compose); en local sigue el patrón legible (documentado el cambio). Los tests capturan la salida con el `StructuredLogEncoder` real de Boot alimentado por el `Environment` de la app (independiente del estado global de logging de la JVM, que comparten los contextos cacheados).
8. **Consumer y correlationId**: **no** lo restauraba (el publisher lo enviaba, nadie lo leía; además el `ReceiveMessageRequest` no pedía el atributo, así que SQS ni lo devolvía). Ahora: `messageAttributeNames("correlationId")`, validación con `CorrelationId.resolve` (misma regla que el filtro web; ausente o inseguro -> UUID nuevo) y `contextWrite` solo del procesamiento de ese mensaje (MDC por propagación automática, sin fugas; probado). Línea INFO nueva «Order X processed as Y» (antes DEBUG) para poder seguir la compra; el warn de venenoso/no confirmado también lleva el id del mensaje.
9. **Trazas**: sin OpenTelemetry; X-Ray/ADOT documentado como opción de producción (referencia a F-026).

## Fallos / desviaciones encontrados (por favor revisar)

- **Desviación visible**: la métrica `ticketflow.orders.created` se llama **`ticketflow.orders.placed`**. El cliente Prometheus 1.x reserva el sufijo `_created` y publica el contador `ticketflow.orders.created` como `ticketflow_orders_total` (nombre engañoso, verificado en el scrape). Mismo significado: reserva hecha (no replays). Documentado en `docs/observability.md` y en `MicrometerMetrics`.
- **`ticketflow.conflicts` se cuenta en los casos de uso, no en los adaptadores DynamoDB**: así no hay que tocar 5 repositorios ni sus constructores, pero un reintento interno de un adaptador que no llega a propagarse no se cuenta, y `inventory_insufficient` se cuenta donde se ve `InsufficientInventoryException`. Documentado.
- **Los tests `@SpringBootTest` no sirven el puerto de gestión separado en entorno MOCK** y desactivan la exportación de métricas: se pasó a `RANDOM_PORT` + `management.server.port=0` + `@AutoConfigureMetrics`. `src/test/resources/application.properties` evita que varios contextos cacheados choquen en el 8081.
- **Readiness `DOWN` hasta que existan las tablas**: `DescribeTable orders` falla si DynamoDB Local (en memoria) se reinicia y pierde las tablas; el aprovisionamiento solo corre al arrancar la app, así que tras reiniciar solo DynamoDB la readiness sigue `DOWN` hasta reiniciar la app (visto en la verificación de compose; es semánticamente correcto: la app no puede servir sin tablas). Liveness/healthcheck del contenedor siguen `UP`/`healthy`.
- DLQ descubierta por el nombre de la ARN de la *redrive policy*: asume misma cuenta/región que el cliente SQS (cierto en LocalStack y en el caso normal de AWS).
- `service.node` sale como `{}` en el JSON (campo ECS vacío de Boot; inocuo).
- El endpoint `info` está expuesto pero vacío (no se genera `build-info`).
- Volumen de logs: +1 INFO por compra aceptada y por mensaje procesado (documentado en las notas de coste).
- El `Healthcheck` ahora usa `MANAGEMENT_SERVER_PORT` (antes `SERVER_PORT`).

## `./init.sh` (sin Docker)

```
[Incubating] Problems report is available at: file://<repo>/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/9.8.0/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 30s
11 actionable tasks: 11 executed
==> init.sh OK
exit=0
```
Cobertura de líneas global (JaCoCo, sin integración): **99,4 %** (mínimo 90 %).

## `INCLUDE_INTEGRATION=true ./init.sh` (Colima)

```
For more on this, please refer to https://docs.gradle.org/9.8.0/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 3m 33s
11 actionable tasks: 11 executed
==> init.sh OK
exit=0
```
918 tests, 0 fallos, 0 errores, 0 omitidos; cobertura 99,5 %. Incluye `ObservabilityEndToEndIT` (compra -> consumer vende -> `/actuator/prometheus` con `ticketflow_orders_placed_total=1`, `ticketflow_orders_sold_total=1`, contadores del consumer, gauges de cola en 0 y DLQ 0; mensaje venenoso -> contador `poison` -> DLQ=1 visible en el gauge; línea JSON de la API y del consumer con el mismo `correlationId`; puerto público 404 en `/actuator/*`), `ReadinessDownEndToEndIT` (DynamoDB inalcanzable) y `ReadinessDownMissingQueueEndToEndIT` (cola inexistente). Sin sleeps fijos (Awaitility).

## Verificación con el docker-compose endurecido real

`docker-compose up --build -d` (read_only, cap_drop ALL, no-new-privileges, puertos solo en 127.0.0.1), salida literal (líneas JSON recortadas a 330 caracteres):

```
--- docker-compose ps
NAME                      IMAGE                          COMMAND                  SERVICE      CREATED              STATUS                    PORTS
ticketflow-app-1          ticketflow:local               "java -XX:MaxRAMPerc…"   app          About a minute ago   Up 54 seconds (healthy)   127.0.0.1:8080-8081->8080-8081/tcp
ticketflow-dynamodb-1     amazon/dynamodb-local:3.3.1    "java -jar DynamoDBL…"   dynamodb     About a minute ago   Up 59 seconds (healthy)   127.0.0.1:8000->8000/tcp
ticketflow-localstack-1   localstack/localstack:4.14.0   "docker-entrypoint.sh"   localstack   About a minute ago   Up 59 seconds (healthy)   4510-4559/tcp, 5678/tcp, 127.0.0.1:4566->4566/tcp
--- docker inspect health
healthy
--- liveness/readiness/health on 8081
GET /actuator/health/liveness
HTTP/1.1 200 OK
{"status":"UP"}
GET /actuator/health/readiness
HTTP/1.1 200 OK
{"status":"UP"}
GET /actuator/health
HTTP/1.1 200 OK
{"groups":["liveness","readiness"],"status":"UP"}
--- public 8080 /actuator/*
GET :8080/actuator/health
HTTP/1.1 404 Not Found
X-Correlation-Id: c2dcbe8a-e380-4fe1-8823-59f61e649d20
GET :8080/actuator/prometheus
HTTP/1.1 404 Not Found
X-Correlation-Id: 5fcb8084-a501-4007-833a-9217290775ed
GET :8080/actuator
HTTP/1.1 404 Not Found
X-Correlation-Id: 84b1375c-b165-44d2-9022-d4385e67aa6e
--- other endpoints on 8081
env -> 404
beans -> 404
heapdump -> 404
loggers -> 404
metrics -> 404
--- create event
EVENT=e75436d3-1cd5-4918-83f5-6b59bab38ed9
--- purchase
HTTP/1.1 202 Accepted
X-Correlation-Id: corr-compose-demo-1
{"orderId":"b2a89ec5-329c-5a63-b095-05ff38883535","status":"RESERVED","reservationExpiresAt":"2026-10-03T04:41:28.707962699Z"}ORDER=b2a89ec5-329c-5a63-b095-05ff38883535
poll 1: SOLD
--- oversell attempt (409) and replay
oversell: 409
replay: 202
--- prometheus ticketflow_ series (non-bucket)
ticketflow_conflicts_total{application="ticketflow",operation="purchase",type="inventory_insufficient"} 1.0
ticketflow_consumer_messages_total{application="ticketflow",outcome="processed"} 1.0
ticketflow_consumer_processing_duration_seconds_count{application="ticketflow"} 1
ticketflow_consumer_processing_duration_seconds_sum{application="ticketflow"} 0.050615681
ticketflow_consumer_processing_duration_seconds_max{application="ticketflow"} 0.050615681
ticketflow_expiration_sweep_duration_seconds_count{application="ticketflow"} 1
ticketflow_expiration_sweep_duration_seconds_sum{application="ticketflow"} 0.096
ticketflow_expiration_sweep_duration_seconds_max{application="ticketflow"} 0.096
ticketflow_expiration_sweeps_total{application="ticketflow",result="ok"} 1.0
ticketflow_orders_placed_total{application="ticketflow"} 1.0
ticketflow_orders_processed_total{application="ticketflow",outcome="sold"} 1.0
ticketflow_orders_sold_total{application="ticketflow"} 1.0
ticketflow_purchases_rejected_total{application="ticketflow",reason="insufficient_inventory"} 1.0
ticketflow_purchases_replayed_total{application="ticketflow"} 1.0
ticketflow_queue_publish_total{application="ticketflow",outcome="ok"} 1.0
ticketflow_queue_stats_age_seconds{application="ticketflow"} 6.868
ticketflow_queue_stats_refreshes_total{application="ticketflow",result="ok"} 5.0
--- queue gauges
ticketflow_queue_messages{application="ticketflow",queue="dlq",state="in_flight"} 0.0
ticketflow_queue_messages{application="ticketflow",queue="dlq",state="visible"} 0.0
ticketflow_queue_messages{application="ticketflow",queue="orders",state="in_flight"} 0.0
ticketflow_queue_messages{application="ticketflow",queue="orders",state="visible"} 0.0
ticketflow_queue_publish_total{application="ticketflow",outcome="failed"} 0.0
ticketflow_queue_publish_total{application="ticketflow",outcome="ok"} 1.0
ticketflow_queue_publish_retries_total{application="ticketflow"} 0.0
ticketflow_queue_stats_age_seconds{application="ticketflow"} 6.895
ticketflow_queue_stats_refreshes_total{application="ticketflow",result="error"} 0.0
ticketflow_queue_stats_refreshes_total{application="ticketflow",result="ok"} 5.0
--- JSON log lines with correlation id
{"@timestamp":"2026-10-03T04:31:28.735910265Z","log":{"level":"INFO","logger":"com.ticketflow.usecase.RequestPurchaseUseCase"},"process":{"pid":1,"thread":{"name":"sdk-async-response-0-10"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","environment":"local","node":{}},"message":"Order b2a89ec5-329c-5a63-b095-05ff388 ...
{"@timestamp":"2026-10-03T04:31:28.741279648Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.messaging.SqsQueueUrlResolver"},"process":{"pid":1,"thread":{"name":"sdk-async-response-3-14"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","environment":"local","node":{}},"message":"Resolved SQS queue 'ord ...
{"@timestamp":"2026-10-03T04:31:28.806473259Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.messaging.SqsOrderConsumer"},"process":{"pid":1,"thread":{"name":"sdk-async-response-3-17"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","environment":"local","node":{}},"message":"Order b2a89ec5-329c-5a63-b ...
--- total log lines / non-JSON lines / errors
      33
0
0
--- WARN/ERROR lines in app log
0
--- full app log (first 40 lines, trimmed)
{"@timestamp":"2026-10-03T04:30:35.491971442Z","log":{"level":"INFO","logger":"com.ticketflow.TicketflowApplication"},"process":{"pid":1,"thread":{"name":"main"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","environment":"local","node":{}},"messa
{"@timestamp":"2026-10-03T04:30:35.542962918Z","log":{"level":"INFO","logger":"com.ticketflow.TicketflowApplication"},"process":{"pid":1,"thread":{"name":"main"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","environment":"local","node":{}},"messa
{"@timestamp":"2026-10-03T04:30:38.896104427Z","log":{"level":"INFO","logger":"org.springframework.boot.reactor.netty.NettyWebServer"},"process":{"pid":1,"thread":{"name":"main"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","environment":"local",
{"@timestamp":"2026-10-03T04:30:38.973928049Z","log":{"level":"INFO","logger":"org.springframework.boot.actuate.endpoint.web.EndpointLinksResolver"},"process":{"pid":1,"thread":{"name":"main"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","environ
{"@timestamp":"2026-10-03T04:30:39.052527264Z","log":{"level":"INFO","logger":"org.springframework.boot.reactor.netty.NettyWebServer"},"process":{"pid":1,"thread":{"name":"main"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","environment":"local",
{"@timestamp":"2026-10-03T04:30:39.067842353Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.scheduler.ReservationExpirationScheduler"},"process":{"pid":1,"thread":{"name":"main"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","envi
{"@timestamp":"2026-10-03T04:30:39.071560864Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.observability.QueueDepthMonitor"},"process":{"pid":1,"thread":{"name":"main"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","environment":
{"@timestamp":"2026-10-03T04:30:39.458617275Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.messaging.SqsOrderConsumer"},"process":{"pid":1,"thread":{"name":"main"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","environment":"loca
{"@timestamp":"2026-10-03T04:30:39.463818700Z","log":{"level":"INFO","logger":"com.ticketflow.TicketflowApplication"},"process":{"pid":1,"thread":{"name":"main"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","environment":"local","node":{}},"messa
{"@timestamp":"2026-10-03T04:30:39.846757580Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.messaging.SqsQueueUrlResolver"},"process":{"pid":1,"thread":{"name":"sdk-async-response-3-0"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT
{"@timestamp":"2026-10-03T04:30:39.846835802Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.messaging.SqsQueueUrlResolver"},"process":{"pid":1,"thread":{"name":"sdk-async-response-3-1"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT
{"@timestamp":"2026-10-03T04:30:39.946689667Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.messaging.SqsQueueUrlResolver"},"process":{"pid":1,"thread":{"name":"sdk-async-response-3-3"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT
{"@timestamp":"2026-10-03T04:30:40.196375571Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.persistence.DynamoDbTableProvisioner"},"process":{"pid":1,"thread":{"name":"sdk-async-response-0-1"}},"service":{"name":"ticketflow","version":"0.0.1-S
{"@timestamp":"2026-10-03T04:30:40.207424115Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.persistence.DynamoDbTableProvisioner"},"process":{"pid":1,"thread":{"name":"sdk-async-response-0-2"}},"service":{"name":"ticketflow","version":"0.0.1-S
{"@timestamp":"2026-10-03T04:30:40.221004364Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.persistence.DynamoDbTableProvisioner"},"process":{"pid":1,"thread":{"name":"sdk-async-response-0-4"}},"service":{"name":"ticketflow","version":"0.0.1-S
{"@timestamp":"2026-10-03T04:30:40.227312802Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.persistence.DynamoDbTableProvisioner"},"process":{"pid":1,"thread":{"name":"sdk-async-response-0-5"}},"service":{"name":"ticketflow","version":"0.0.1-S
{"@timestamp":"2026-10-03T04:30:40.262371811Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.persistence.DynamoDbTableProvisioner"},"process":{"pid":1,"thread":{"name":"sdk-async-response-0-7"}},"service":{"name":"ticketflow","version":"0.0.1-S
{"@timestamp":"2026-10-03T04:30:40.278932902Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.persistence.DynamoDbTableProvisioner"},"process":{"pid":1,"thread":{"name":"sdk-async-response-0-0"}},"service":{"name":"ticketflow","version":"0.0.1-S
{"@timestamp":"2026-10-03T04:30:40.289399523Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.persistence.DynamoDbTableProvisioner"},"process":{"pid":1,"thread":{"name":"sdk-async-response-0-2"}},"service":{"name":"ticketflow","version":"0.0.1-S
{"@timestamp":"2026-10-03T04:30:40.298320263Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.persistence.DynamoDbTableProvisioner"},"process":{"pid":1,"thread":{"name":"sdk-async-response-0-3"}},"service":{"name":"ticketflow","version":"0.0.1-S
{"@timestamp":"2026-10-03T04:30:40.298518402Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.config.DynamoDbConfig"},"process":{"pid":1,"thread":{"name":"sdk-async-response-0-3"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","envir
{"@timestamp":"2026-10-03T04:31:28.377807300Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.web.error.ApiExceptionHandler"},"process":{"pid":1,"thread":{"name":"reactor-http-nio-1"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","e
{"@timestamp":"2026-10-03T04:31:28.398909318Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.web.error.ApiExceptionHandler"},"process":{"pid":1,"thread":{"name":"reactor-http-nio-3"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","e
{"@timestamp":"2026-10-03T04:31:28.410490618Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.web.error.ApiExceptionHandler"},"process":{"pid":1,"thread":{"name":"reactor-http-nio-2"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","e
{"@timestamp":"2026-10-03T04:31:28.441970725Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.web.error.ApiExceptionHandler"},"process":{"pid":1,"thread":{"name":"reactor-http-nio-4"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","e
{"@timestamp":"2026-10-03T04:31:28.453897115Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.web.error.ApiExceptionHandler"},"process":{"pid":1,"thread":{"name":"reactor-http-nio-1"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","e
{"@timestamp":"2026-10-03T04:31:28.465151244Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.web.error.ApiExceptionHandler"},"process":{"pid":1,"thread":{"name":"reactor-http-nio-3"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","e
{"@timestamp":"2026-10-03T04:31:28.476779384Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.web.error.ApiExceptionHandler"},"process":{"pid":1,"thread":{"name":"reactor-http-nio-2"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","e
{"@timestamp":"2026-10-03T04:31:28.488214414Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.web.error.ApiExceptionHandler"},"process":{"pid":1,"thread":{"name":"reactor-http-nio-4"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","e
{"@timestamp":"2026-10-03T04:31:28.735910265Z","log":{"level":"INFO","logger":"com.ticketflow.usecase.RequestPurchaseUseCase"},"process":{"pid":1,"thread":{"name":"sdk-async-response-0-10"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","environmen
{"@timestamp":"2026-10-03T04:31:28.741279648Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.messaging.SqsQueueUrlResolver"},"process":{"pid":1,"thread":{"name":"sdk-async-response-3-14"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHO
{"@timestamp":"2026-10-03T04:31:28.806473259Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.messaging.SqsOrderConsumer"},"process":{"pid":1,"thread":{"name":"sdk-async-response-3-17"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT",
{"@timestamp":"2026-10-03T04:31:28.877528031Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.web.error.ApiExceptionHandler"},"process":{"pid":1,"thread":{"name":"sdk-async-response-0-15"}},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHO
--- LAN reachability of 8081 (should refuse)
LAN ip=192.168.1.37
http_code=000
--- read-only rootfs / caps
ReadonlyRootfs=true CapDrop=[ALL] SecOpt=[no-new-privileges:true] Ports={"8080/tcp":[{"HostIp":"127.0.0.1","HostPort":"8080"}],"8081/tcp":[{"HostIp":"127.0.0.1","HostPort":"8081"}]}
--- redis docker env
MANAGEMENT_SERVER_ADDRESS=0.0.0.0
TICKETFLOW_OBSERVABILITY_QUEUE_METRICS_ENABLED=true
LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
TICKETFLOW_ENVIRONMENT=local
ADMIN_API_KEY=
--- stop dynamodb: readiness DOWN, liveness UP
HTTP/1.1 503 Service Unavailable
{"status":"DOWN"}
HTTP/1.1 200 OK
{"status":"UP"}
container health: healthy
--- start dynamodb again: readiness recovers (tables are in-memory, so they are re-provisioned on app restart only)
HTTP/1.1 503 Service Unavailable
{"status":"DOWN"}
 Container ticketflow-app-1 Stopping 
 Container ticketflow-app-1 Stopped 
 Container ticketflow-app-1 Removing 
 Container ticketflow-app-1 Removed 
 Container ticketflow-localstack-1 Stopping 
 Container ticketflow-dynamodb-1 Stopping 
 Container ticketflow-dynamodb-1 Stopped 
 Container ticketflow-dynamodb-1 Removing 
 Container ticketflow-dynamodb-1 Removed 
 Container ticketflow-localstack-1 Stopped 
 Container ticketflow-localstack-1 Removing 
 Container ticketflow-localstack-1 Removed 
 Network ticketflow_default Removing 
 Network ticketflow_default Removed 

```
