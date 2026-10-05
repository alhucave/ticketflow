# Observabilidad

Qué se mide, cómo se expone y cómo se usa en la operación diaria (F-024). La parte de AWS (CloudWatch, X-Ray, alarmas) está **diseñada, sin desplegar**, en [`aws.md`](aws.md#6-observabilidad-en-aws); aquí está la observabilidad **real de la aplicación**, que funciona igual en local y en producción.

## Resumen

| Pilar | Mecanismo | Dónde |
|-------|-----------|-------|
| Métricas | Micrometer, prefijo `ticketflow.` (+ las métricas estándar de JVM, HTTP y Reactor Netty) | `GET /actuator/prometheus` en el puerto de gestión |
| Logs | JSON estructurado (formato ECS de Spring Boot) con `correlationId` en **cada** línea | stdout |
| Sondas | `liveness` y `readiness` (Actuator health groups) | `GET /actuator/health/liveness`, `/actuator/health/readiness` |
| Trazas | Solo *correlation id* propagado API -> cola -> consumer; sin OpenTelemetry (ver «Trazas») | logs |

## Puertos y seguridad del Actuator

- Actuator se sirve en un **puerto de gestión propio** (`management.server.port`, por defecto `8081`; variable `MANAGEMENT_SERVER_PORT`). El puerto público (`8080`) responde `404` a `/actuator/**` (probado).
- Solo se exponen `health`, `info` y `prometheus`. No hay `env`, `beans`, `heapdump`, `loggers`, `metrics`... (probado: `404`).
- `health` muestra únicamente `{"status":"UP"}` o `{"status":"DOWN"}` (`show-details: never`, `show-components: never`): el motivo del fallo no sale por HTTP, solo en el log del servidor (nombre de la clase de la excepción).
- Dirección de escucha: por defecto `127.0.0.1` (`MANAGEMENT_SERVER_ADDRESS`), para que una ejecución local no lo exponga a la red. La imagen Docker lo cambia a `0.0.0.0` (necesario dentro del contenedor) y `docker-compose.yml` lo publica **solo** en `127.0.0.1:8081`. El endpoint de Prometheus no tiene autenticación: en un despliegue real debe quedar en una red privada (ver `docs/security.md`, limitación 10).
- El `HEALTHCHECK` de la imagen consulta **liveness** en el puerto de gestión (`docker/healthcheck/Healthcheck.java`). Se usa liveness a propósito: una caída de DynamoDB o SQS no debe marcar el contenedor como no saludable ni provocar reinicios.

## Sondas de salud

| Grupo | Incluye | Para qué |
|-------|---------|----------|
| `liveness` | solo `livenessState` | «el proceso está vivo»: **nunca** depende de sistemas externos |
| `readiness` | `readinessState`, `dynamodb`, `sqs` | «puede atender tráfico»: balanceador, dashboards, despliegues |

- `dynamodb`: `DescribeTable` sobre la tabla `orders` (verifica alcance y que las tablas estén creadas; con `provisioning-enabled` se crean al arrancar, por eso `readiness` sale en `DOWN` unos segundos al inicio). Mientras una tabla falte, las peticiones de la API reciben `503 service-unavailable` (no `500`) y cada una registra un `WARN` `DynamoDB tables are missing` con la excepción completa (`ResourceNotFoundException`, con el nombre de la tabla): es la pista de una tabla que nunca se creó, junto con `readiness` en `DOWN` (DP-038).
- `sqs`: la cola de órdenes se resuelve (`GetQueueUrl`, o `GetQueueAttributes` si se configuró `orders-queue-url`).
- Son indicadores **reactivos** (no bloquean ningún hilo), con timeout corto y resultado cacheado brevemente, para que ni un balanceador ni Prometheus golpeen AWS en cada sondeo:

| Propiedad | Por defecto | Significado |
|-----------|-------------|-------------|
| `ticketflow.observability.health.timeout` | `2s` | una dependencia más lenta que esto cuenta como `DOWN` |
| `ticketflow.observability.health.cache-ttl` | `5s` | cuánto se reutiliza el último resultado (también el `DOWN`); sondeos concurrentes comparten la misma llamada |

## Catálogo de métricas

Todas con el prefijo `ticketflow.` (en Prometheus: puntos a `_`, contadores con sufijo `_total`, temporizadores con `_seconds_*`) y la etiqueta común `application="ticketflow"`. Todas las series se registran **al arrancar a 0**, así que las reglas de alerta nunca ven «series ausentes».

### Negocio

| Métrica | Tipo | Etiquetas | Significado |
|---------|------|-----------|-------------|
| `ticketflow.orders.placed` | counter | — | Órdenes nuevas con reserva hecha (no cuenta los replays). Se llama `placed` y no `created` porque el cliente Prometheus reserva el sufijo `_created` y publicaría `ticketflow_orders_total` |
| `ticketflow.purchases.replayed` | counter | — | Peticiones que devolvieron una orden existente (misma `Idempotency-Key` y payload) |
| `ticketflow.purchases.rejected` | counter | `reason`: `insufficient_inventory`, `idempotency_key_reused`, `order_not_active`, `enqueue_failed` | Compras rechazadas, por motivo (`enqueue_failed` = no se pudo publicar y se compensó la reserva) |
| `ticketflow.orders.sold` | counter | — | Ventas completadas |
| `ticketflow.orders.released` | counter | `reason`: `expired`, `publish_failed` | Reservas devueltas a `AVAILABLE` (por expiración, ya sea por el consumer o por el barrido, o por fallo de publicación) |
| `ticketflow.complimentary.issued` | counter | — | Cortesías emitidas |
| `ticketflow.orders.processed` | counter | `outcome`: `sold`, `released_as_expired`, `already_processed`, `order_missing` | Resultado de `ProcessOrderUseCase` por mensaje |
| `ticketflow.conflicts` | counter | `type`: `inventory_insufficient`, `order_status`; `operation`: `purchase`, `complimentary`, `process_order`, `expiration_sweep` | Escrituras condicionales rechazadas. `order_status` es contención benigna (otro worker ganó). `inventory_insufficient` en `process_order` **no debería ocurrir nunca** (indicaría una violación del invariante de inventario) |

### Cola y consumer

| Métrica | Tipo | Etiquetas | Significado |
|---------|------|-----------|-------------|
| `ticketflow.consumer.messages` | counter | `outcome`: `processed`, `failed`, `poison` | Mensajes recibidos: borrados tras procesarse; no confirmados (SQS reentrega); inválidos (van a la DLQ tras `maxReceiveCount`) |
| `ticketflow.consumer.processing.duration` | timer (histograma) | — | Tiempo de proceso por mensaje (procesados y fallidos; no los *poison*) |
| `ticketflow.queue.publish` | counter | `outcome`: `ok`, `failed` | Publicaciones a SQS |
| `ticketflow.queue.publish.retries` | counter | — | Reintentos de errores transitorios al publicar |
| `ticketflow.queue.messages` | gauge | `queue`: `orders`, `dlq`; `state`: `visible`, `in_flight` | Mensajes aproximados (ver abajo). **Profundidad de la DLQ = `queue="dlq",state="visible"`** |
| `ticketflow.queue.stats.age.seconds` | gauge | — | Segundos desde la última actualización correcta de los gauges de cola |
| `ticketflow.queue.stats.refreshes` | counter | `result`: `ok`, `error` | Actualizaciones de los gauges (`GetQueueAttributes`) |

**Gauges de cola.** Los actualiza un sondeo periódico **no bloqueante** (`QueueDepthMonitor`: `GetQueueAttributes` de la cola de órdenes y de su DLQ, que se descubre por la *redrive policy* de la cola). Leer `/actuator/prometheus` solo lee memoria, no hace I/O. Si un sondeo falla (o supera el timeout), **se conserva el último valor** (nunca se pone a 0, que ocultaría la caída), se cuenta en `queue.stats.refreshes{result="error"}` y `queue.stats.age.seconds` crece. Antes del primer sondeo exitoso los valores son `NaN`. Sin *redrive policy* los de la DLQ quedan en `NaN`.

| Propiedad | Por defecto | Significado |
|-----------|-------------|-------------|
| `ticketflow.observability.queue-metrics.enabled` | `false` (docker-compose: `true`) | activa el sondeo |
| `ticketflow.observability.queue-metrics.interval` | `15s` | periodo (mínimo `1s`) |
| `ticketflow.observability.queue-metrics.timeout` | `5s` | un sondeo más lento se abandona y cuenta como error |

**Edad del mensaje más antiguo: no se expone.** `GetQueueAttributes` no la devuelve (SQS solo publica `ApproximateAgeOfOldestMessage` en CloudWatch); calcularla desde la aplicación exigiría recibir mensajes (cambia su visibilidad y su `receiveCount`), que no es barato ni seguro. En AWS se usa la métrica de CloudWatch `ApproximateAgeOfOldestMessage` (ver las [alarmas](aws.md#64-alarmas)); en local, `visible` creciendo y `consumer.messages` sin avanzar dan la misma señal.

### Expiración, límites y dependencias

El consumer y el barrido de expiración arrancan **por defecto** ([DP-037](decisions.md#dp-037-el-consumidor-sqs-y-el-job-de-expiración-arrancan-por-defecto)); un proceso solo-API los desactiva con `ticketflow.sqs.consumer.enabled=false` y `ticketflow.expiration.enabled=false`, y entonces estas métricas y los gauges del consumer no se publican en él. Si DynamoDB o SQS están inaccesibles, la aplicación no cae: el consumer registra `ReceiveMessage failed (attempt N) ... retrying with backoff` (1 s a 30 s) y cada barrido fallido registra `Reservation expiration sweep failed; will retry at the next interval` (`ticketflow.expiration.sweeps{result="error"}`), mientras `readiness` está `DOWN` y `liveness` `UP`.

| Métrica | Tipo | Etiquetas | Significado |
|---------|------|-----------|-------------|
| `ticketflow.expiration.sweep.orders` | counter | `result`: `released`, `skipped_conflict`, `failed` | Órdenes que tocó el barrido de expiración (`failed` = no se pudo liberar, se reintenta en el siguiente) |
| `ticketflow.expiration.sweeps` | counter | `result`: `ok`, `error` | Barridos terminados / fallidos por completo (falló la consulta de candidatas) |
| `ticketflow.expiration.sweep.duration` | timer | — | Duración de un barrido |
| `ticketflow.ratelimit.rejections` | counter | `limiter`: `write`, `admin_failure` | Peticiones rechazadas con `429`: presupuesto de escritura o bloqueo por intentos fallidos de `X-Admin-Key` |
| `ticketflow.dependency.unavailable` | counter | — | Respuestas `503 service-unavailable` por DynamoDB/SQS con throttling, timeout o caída, o por una tabla de DynamoDB inexistente (ventana de arranque, DP-038: sube en los primeros segundos y no debe seguir subiendo; si lo hace, las tablas no se crearon) |

### Regla de cardinalidad

**Las etiquetas solo toman valores de enums fijos** (los de las tablas de arriba). Ninguna métrica se etiqueta con `orderId`, `eventId`, `Idempotency-Key`, direcciones de clientes, rutas ni ningún dato del usuario: cada una de esas dimensiones crearía series ilimitadas (coste y riesgo de DoS por memoria en Prometheus/CloudWatch). Está garantizado por construcción (los puertos `BusinessMetrics`/`OperationalMetrics` no aceptan texto libre; las series se registran una vez en el arranque) y probado (`MicrometerMetricsTest`: claves y valores de etiqueta de todas las series pertenecen a conjuntos fijos y usar las métricas no crea series nuevas). El total ronda las 50 series. Las métricas HTTP estándar (`http.server.requests`) usan la **plantilla** de la ruta (`/orders/{id}`), no la URL real.

Dónde se cuenta cada cosa (útil al interpretar): las de negocio y los conflictos se registran en los casos de uso (puerto `BusinessMetrics`, sin dependencia de Micrometer en `usecase`/`domain`); las de infraestructura en los adaptadores (puerto `OperationalMetrics`). `ticketflow.conflicts` se cuenta en el caso de uso al ver la excepción de conflicto, no dentro del adaptador: un reintento interno del adaptador que no llega a propagarse no se cuenta.

## Alertas y SLO sugeridos

Reglas de ejemplo en PromQL (ajustar umbrales a la carga real). En AWS equivalen a alarmas de CloudWatch sobre las mismas métricas (tabla con umbrales iniciales en [`aws.md`](aws.md#64-alarmas)).

| Alerta | Expresión | Por qué |
|--------|-----------|---------|
| **DLQ con mensajes** (crítica) | `ticketflow_queue_messages{queue="dlq",state="visible"} > 0` | Hay mensajes que fallaron `maxReceiveCount` veces: requieren intervención |
| **Cola creciendo** | `ticketflow_queue_messages{queue="orders",state="visible"} > 100` sostenido 5 min, o `deriv(...[10m]) > 0` | El consumer no da abasto o está parado |
| **Datos de cola obsoletos** | `ticketflow_queue_stats_age_seconds > 120` o `increase(ticketflow_queue_stats_refreshes_total{result="error"}[5m]) > 0` | Los gauges de cola no son de fiar |
| **Sobreventa / invariante roto** (crítica, debe ser siempre 0) | `increase(ticketflow_conflicts_total{type="inventory_insufficient",operation="process_order"}[5m]) > 0` | El consumer intentó mover inventario que no existe: el contador de reservas y las órdenes no cuadran |
| **Fallos del consumer** | `rate(ticketflow_consumer_messages_total{outcome="failed"}[5m]) / rate(ticketflow_consumer_messages_total[5m]) > 0.05` | Más de un 5 % de mensajes sin confirmar |
| **Mensajes venenosos** | `increase(ticketflow_consumer_messages_total{outcome="poison"}[10m]) > 0` | Alguien publica mensajes con formato inválido |
| **Tasa de 5xx** (SLO de disponibilidad, p. ej. 99,9 %) | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) > 0.001` | Errores del servidor |
| **Latencia p99** (SLO, p. ej. `POST /orders` < 500 ms) | `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/orders"}[5m]))) > 0.5` (requiere publicar el histograma de `http.server.requests`; ver `management.metrics.distribution.percentiles-histogram.http.server.requests`) | Latencia de aceptación de compras |
| **Dependencias caídas** | `increase(ticketflow_dependency_unavailable_total[5m]) > 0` o `probe_success`/readiness en `DOWN` | DynamoDB/SQS con throttling o inalcanzables |
| **Pico de rechazos por rate limit** | `rate(ticketflow_ratelimit_rejections_total{limiter="write"}[5m]) > 10` | Abuso o un cliente defectuoso; `limiter="admin_failure"` > 0 sostenido = intento de fuerza bruta sobre `X-Admin-Key` |
| **Barrido de expiración sin avanzar** | `increase(ticketflow_expiration_sweeps_total{result="ok"}[10m]) == 0` (si el job está activo) o `increase(ticketflow_expiration_sweep_orders_total{result="failed"}[10m]) > 0` | Las reservas expiradas no se recuperan |
| **Reservas sin vender** (negocio) | `increase(ticketflow_orders_released_total{reason="expired"}[1h])` vs `increase(ticketflow_orders_sold_total[1h])` | Muchas reservas que expiran: ¿consumer lento o pagos que no llegan? |

## Logs

### Formato

- **Contenedor** (imagen y `docker-compose.yml`): `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` -> una línea = un objeto JSON en formato ECS de Spring Boot: `@timestamp`, `log.level`, `log.logger`, `message`, `process.thread.name`, `service.name`/`version`/`environment`, `ecs.version`, **`correlationId`** (MDC) y, en excepciones, `error.type`/`error.message`/`error.stack_trace`.
- **Local** (`./gradlew bootRun`, IDE): sin esa variable se usa el patrón legible de siempre, `... [correlationId] mensaje`. Para ver JSON en local: `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs ./gradlew bootRun` (también vale `logstash`). Para el patrón legible en un contenedor, quitar la variable (la imagen la fija con `ENV` y `docker-compose.yml` en `environment`).
- `service.name` sale de `spring.application.name`, `service.version` de la versión del proyecto (se sustituye al construir) y `service.environment` de `TICKETFLOW_ENVIRONMENT` (por defecto `local`).
- Las excepciones se registran **con traza en el servidor** y nunca llegan al cliente (errores `problem+json` con texto fijo, ver el «Catálogo de errores» del README). Los logs nunca incluyen secretos: `X-Admin-Key`, credenciales de AWS y cuerpos de mensajes (probado en `StructuredLoggingTest`; la clave del cliente tampoco se registra).

### Seguir una compra por `correlationId`

El `X-Correlation-Id` de la petición (o uno generado) se escribe en el MDC de **cada** línea de esa petición, viaja como atributo `correlationId` del mensaje SQS y el consumer lo **restaura** (validado igual que en el filtro web: 1-64 caracteres `[A-Za-z0-9._-]`; si falta o es inseguro se genera uno nuevo) en el contexto Reactor/MDC mientras procesa ese mensaje. Así una compra se sigue API -> cola -> consumer con un solo filtro:

```bash
curl -s -X POST http://127.0.0.1:8080/orders -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-key-0123456789' -H 'X-Correlation-Id: corr-demo-1' \
  -d '{"eventId":"EVENT_ID","quantity":3}'

docker-compose logs --no-log-prefix app | grep corr-demo-1
# o, con jq, solo lo importante:
docker-compose logs --no-log-prefix app | grep corr-demo-1 | jq -r '[.["@timestamp"], .log.logger, .message] | @tsv'
```

Salida real (recortada):

```json
{"@timestamp":"2026-10-03T04:31:28.735910265Z","log":{"level":"INFO","logger":"com.ticketflow.usecase.RequestPurchaseUseCase"},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","environment":"local"},"message":"Order b2a89ec5-329c-5a63-b095-05ff38883535 placed: 3 ticket(s) reserved until 2026-10-03T04:41:28.707962699Z","correlationId":"corr-demo-1","ecs":{"version":"8.11"}}
{"@timestamp":"2026-10-03T04:31:28.806473259Z","log":{"level":"INFO","logger":"com.ticketflow.infrastructure.messaging.SqsOrderConsumer"},"service":{"name":"ticketflow","version":"0.0.1-SNAPSHOT","environment":"local"},"message":"Order b2a89ec5-329c-5a63-b095-05ff38883535 processed as Sold; message c91259c5-9c6d-488d-b480-4b...","correlationId":"corr-demo-1","ecs":{"version":"8.11"}}
```

También se puede buscar por `orderId` (aparece en el mensaje) y, en un sistema de logs (CloudWatch Logs Insights, Loki, ELK), por el campo `correlationId` directamente. Una línea de `Request rejected: status=409 type=...` lleva el mismo `correlationId` que devolvió la API en la cabecera y en el campo del `problem+json`: es lo que el cliente debe citar al reportar un problema.

Volumen: una compra genera unas pocas líneas INFO (orden colocada, procesada, rechazos de cliente). No se registra nada por sondeo de salud ni por scrape. Sube el nivel de `com.ticketflow` solo para diagnosticar.

## Trazas

No hay dependencia de OpenTelemetry (a propósito, por tamaño de imagen y superficie de ataque). Lo que sí hay son los **ganchos** para ello: el `correlationId` entra por la cabecera HTTP, vive en el contexto Reactor, se escribe en el MDC, viaja en el atributo del mensaje SQS y se restaura en el consumer. En producción la opción es **AWS X-Ray con el agente ADOT** (AWS Distro for OpenTelemetry): da trazas distribuidas API -> SQS -> consumer -> DynamoDB sin cambiar los casos de uso, y se discute en [`aws.md`](aws.md#63-trazas) (requiere un agente de OpenTelemetry en la imagen, que hoy no existe) junto con CloudWatch y las alarmas.

## Prometheus local (opcional)

La app publica el puerto de gestión solo en `127.0.0.1:8081` del host, así que el Prometheus debe correr **en el host** (un contenedor no alcanza un puerto ligado a loopback). `prometheus.yml`:

```yaml
global:
  scrape_interval: 15s
scrape_configs:
  - job_name: ticketflow
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['127.0.0.1:8081']
```

```bash
prometheus --config.file=prometheus.yml     # UI en http://127.0.0.1:9090
```

Consultas útiles: `ticketflow_orders_sold_total`, `rate(ticketflow_orders_placed_total[1m])`, `ticketflow_queue_messages`.

## Notas de coste y operación

- **Cardinalidad**: es lo que cuesta dinero en Prometheus gestionado y CloudWatch (se factura por serie). El catálogo es fijo (~50 series + las estándar); añadir una etiqueta nueva debe salir de un enum, nunca de datos de entrada.
- **Histogramas**: `consumer.processing.duration` publica buckets (unas 70 series de bucket); `http.server.requests` no publica histograma por defecto (activarlo multiplica sus series por rutas x estados x buckets: hacerlo solo para las rutas con SLO).
- **Volumen de logs**: JSON es más grande que el patrón legible (campos fijos en cada línea). En CloudWatch Logs se factura por GB ingerido y almacenado: usar retención corta (7-30 días), no registrar a `DEBUG` en producción y no añadir logs por petición exitosa de lectura.
- **Sondeo de colas**: una llamada `GetQueueAttributes` por cola cada 15 s (unas 11.500 al día, dentro de la capa gratuita de SQS por región). Subir el intervalo si hay muchas instancias (cada réplica sondea).
- **Sondas**: con `cache-ttl=5s` DynamoDB y SQS reciben como máximo una llamada de salud cada 5 s por réplica, sin importar cuántos sondeos lleguen.
