# Informe: F-031 enable-runtime-components-by-default

Rama `feature/F-031-enable-runtime-components-by-default`. Origin `own`; decisiones DP-037 (nueva, Extra propio), DP-006 y DP-012 (actualizadas). La feature NO se marca `done` (pendiente de reviewer).

## Archivos
Codigo: `SqsConsumerProperties`, `ExpirationProperties` (`@DefaultValue("true")` + javadoc), `SqsConsumerConfig`, `ExpirationSchedulerConfig` (`@ConditionalOnProperty(..., havingValue="true", matchIfMissing=true)`).
Compose: `docker-compose.yml` (quitadas las dos lineas `*_ENABLED: "true"`, quedan comentarios que apuntan a DP-037).
Tests: `src/test/resources/application.properties` (mecanismo unico: ambos flags `false` para la suite), `RuntimeComponentsDefaultsTest` (nuevo, 3 tests), `SqsConsumerConfigTest` y `ExpirationConfigTest` (la propiedad ausente ahora significa presente; `=false` ausente).
Docs: `docs/decisions.md` (DP-037 + fila de indice; DP-006 y DP-012 actualizadas, incl. su campo Feature), `docs/requirements.md` (RF-3 y RF-6: DP, texto y evidencia), README (tabla de config), `docs/aws.md` (api fija ambos `false` explicito, worker usa el valor por defecto; troubleshooting), `docs/observability.md`, `docs/architecture.md`.

## Decisiones de diseno
- Fuente unica de verdad: el `@DefaultValue("true")` del record y `matchIfMissing=true` del guard (trampa 1: sin `matchIfMissing` una propiedad ausente seria `false`). No se duplica en `application.yml` (los flags no estaban alli; duplicarlos crearia una segunda fuente; la prueba `RuntimeComponentsDefaultsTest` carga solo el yml principal y demuestra que la ausencia = activo).
- Mecanismo de suite: `src/test/resources/application.properties` (no application.yml, que taparia al principal). Los tests que necesitan los componentes ya los activan con `true` explicito via `@DynamicPropertySource` (MessageRedeliveryIT, FailureInjectionIT, PurchaseConcurrencyIT, OrdersApiEndToEndIT, ComplimentaryApiEndToEndIT, ObservabilityEndToEndIT); verificado por grep y por las 3 ejecuciones de integracion. Ninguna asercion se debilito ni se borro; los `false` explicitos existentes se dejaron. `RuntimeComponentsDefaultsTest` cierra sus contextos (try-with-resources) y despega los appenders: no hay fugas entre contextos cacheados (usa `SpringApplicationBuilder`, fuera de la cache de Spring Test).
- Trampa 3 (infra inalcanzable): el comportamiento YA era gracioso, no hizo falta cambiar codigo. Consumidor: `ReceiveMessage` con `Retry.backoff(Long.MAX_VALUE, 1s..30s)`; job: cada barrido fallido se registra y se reintenta; readiness DOWN, liveness UP. Probado en `RuntimeComponentsDefaultsTest.propertiesAbsent_infrastructureUnreachable_...` (aplicacion real, solo `application.yml` principal, puerto cerrado; ~14 s porque el SDK hace 4 intentos por llamada): consumidor y job presentes y en marcha, >=2 reintentos con backoff, >=2 barridos fallidos reintentados, readiness `503 {"status":"DOWN"}`, liveness 200, contexto sigue vivo. No hay propiedades de timeout de cliente en el codigo; con "connection refused" no hacen falta.
- Nota: al unico caso de log del arranque, Spring Boot reinicia logback durante el arranque, por eso el test engancha el appender tras `run`.

## Verificacion
- `./init.sh` plano: verde, 1m15s (JaCoCo 90% OK). `./init.sh --check-registry`: OK (31 features, 37 decisiones, 69 filas).
- `INCLUDE_INTEGRATION=true ./init.sh` (colima), 3 veces seguidas, verde:
  - run 1: 328 s, 948 tests, 0 skipped, 0 failures, 0 errors
  - run 2: 334 s, 948 tests, 0 failures, 0 errors
  - run 3: 333 s, 948 tests, 0 failures, 0 errors
  (baseline 944 + 4 nuevos: 3 de RuntimeComponentsDefaultsTest, 1 de ExpirationConfigTest.)

## End to end contra el stack real (todo con `docker-compose down -v` al final)
Archivos de override FUERA del repo, en el scratchpad (`override-expiry.yml`, `override-apionly.yml`) y script `buy.sh`.
1. `docker-compose up --build -d --wait` sin modificar (86 s): compra 2 entradas -> `RESERVED`, 1 s despues `PENDING_CONFIRMATION`, 2 s despues `SOLD`; disponibilidad `sold=2, available=8`. Logs de `app` sin variable de activacion: `Reservation expiration scheduler started (interval=PT1M, initialDelay=PT10S, ...)` y `SQS order consumer started (batchSize=10, concurrency=4, ...)`.
2. Override `TICKETFLOW_RESERVATION_TTL=PT30S`, `TICKETFLOW_SQS_CONSUMER_ENABLED=false`, `TICKETFLOW_EXPIRATION_INTERVAL=PT5S` (sin tocar `EXPIRATION_ENABLED`): el log solo muestra el scheduler arrancado (`interval=PT5S`), no el consumidor. Reserva creada 00:34:13 (expira 00:34:43): estado `RESERVED` hasta 19:34:47 y `AVAILABLE` a las 19:34:52; log: `Expiration sweep at 2026-10-05T00:34:47Z: examined=1, released=1, skippedConflicts=0, failed=0`; disponibilidad vuelve a `available=10, reserved=0`.
3. Override con `TICKETFLOW_SQS_CONSUMER_ENABLED=false` y `TICKETFLOW_EXPIRATION_ENABLED=false` (solo-API): 20 s despues la orden sigue `RESERVED`, `reserved=2`; 0 lineas de arranque del consumidor/scheduler en los logs.
4. Solo el contenedor `app` (`docker-compose up -d --no-deps app`, dynamodb/localstack ausentes, hosts sin resolver), 45 s: contenedor `Up (healthy)` (liveness), `RestartCount=0`, un solo arranque; `GET :8081/actuator/health/readiness` -> `503 {"status":"DOWN"}`, liveness `200 {"status":"UP"}`. Logs: consumidor y scheduler arrancados; `ReceiveMessage failed (attempt N) ... retrying with backoff` en 00:38:59.7, 00:39:03.5, 00:39:09.5, 00:39:17.7, 00:39:29.4 (separaciones crecientes: backoff); `Reservation expiration sweep failed; will retry at the next interval`.

## Observacion (honesta)
En la primera ejecucion del paso 2 (stack recien levantado tras `down -v`) el primer `POST /orders` devolvio 500 (internal-error) y el stack ya estaba bajado cuando lo vi, por lo que no tengo el log. No se reprodujo en 7 arranques frescos posteriores (con y sin override, 5 compras inmediatas tras `--wait`), con el consumidor desactivado en esa ejecucion (no relacionado con el cambio de defaults: el 500 ocurre en el publicador/API). No es un fallo atribuible a F-031, pero queda anotado por si el reviewer quiere investigarlo (posible carrera de LocalStack/cola en el primer uso).

## Salida literal de init.sh
Ultimas lineas de la ejecucion plana: `BUILD SUCCESSFUL in 1m 15s` / `==> init.sh OK`. Logs completos en el scratchpad (`plain1.log`, `it1.log`..`it3.log`, `it_summary.log`).
