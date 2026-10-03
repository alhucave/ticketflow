# Informe de implementación — F-016 sqs-consumer

Estado: listo para review (feature_list.json queda `in_progress`; no se marca `done`).

## Archivos

Producción:
- `infrastructure/messaging/SqsOrderConsumer.java` (nuevo): consumidor reactivo `SmartLifecycle`.
- `infrastructure/messaging/SqsQueueUrlResolver.java` (nuevo): resolución de URL por nombre (cacheada, fallos no cacheados), extraída de `SqsOrderQueuePublisher` y compartida con el consumidor.
- `infrastructure/messaging/SqsOrderQueuePublisher.java`: usa el resolver; `transientRetry` pasa a package-private (sin cambio de comportamiento).
- `infrastructure/config/SqsConsumerProperties.java` (nuevo, `ticketflow.sqs.consumer.*`, validación fail-fast) y `SqsConsumerConfig.java` (nuevo, `@ConditionalOnProperty enabled=true`).
- `docker-compose.yml`: `TICKETFLOW_SQS_CONSUMER_ENABLED=true` para `app`. `README.md`: sección "Consumidor SQS" (español). `build.gradle.kts`: `org.awaitility:awaitility` explícito (versión del BOM de Spring Boot).

Tests:
- `SqsOrderConsumerTest` (28 casos, mocks + VirtualTimeScheduler), `SqsConsumerConfigTest`.
- `SqsOrderConsumerIT` (LocalStack 4.14.0), `OrderProcessingSqsEndToEndIT` (C12, DynamoDB Local 3.3.1 + LocalStack 4.14.0), helper `SqsTestQueues` (crea `X` + `X-dlq` con redrive maxReceiveCount=3, igual que `docker/localstack/init-queues.sh`).

## Decisiones de diseño

- Borrado solo tras éxito del caso de uso (cualquier `ProcessOrderResult`). Error del caso de uso (incluida excepción síncrona y `OrderStatusConflictException` final), fallo del `DeleteMessage` o mensaje poison: no se borra, se loguea WARN y el mensaje sigue su camino (reentrega tras visibility timeout, DLQ tras `maxReceiveCount`). Los errores se contienen por mensaje: nunca terminan el bucle ni bloquean al resto del lote.
- Poison (JSON inválido, no objeto, `version` ausente/no entera/distinta de 1, `orderId` ausente/no texto/en blanco): se valida el árbol JSON a mano; el WARN registra messageId, receiveCount y un motivo genérico (solo nombre de la clase de excepción), nunca el cuerpo (test con logback verifica que un secreto del payload no aparece).
- Bucle: cada iteración = un `ReceiveMessage` (long polling) + `flatMap(handle, concurrency)` sobre el lote, y solo entonces se hace el siguiente poll. Así los mensajes en vuelo son <= min(concurrency, batchSize) y ningún mensaje recibido espera invisible tras otro lote. `ReceiveMessage` (y la resolución de URL) con `Retry.backoff(Long.MAX_VALUE, min).maxBackoff(max)` con jitter por poll (el backoff se reinicia tras cada éxito) + `retryWhen` defensivo externo; nunca termina en silencio. Sin `.block()` ni `Thread.sleep` en producción.
- Apagado (`SmartLifecycle`): `stop(Runnable)` marca `running=false`, emite la señal de parada que cancela un long poll en curso (los mensajes que SQS ya hubiese entregado reaparecen tras el visibility timeout: at-least-once se mantiene), deja terminar los mensajes ya recibidos hasta `shutdown-timeout` (por defecto 25 s, por debajo de los 30 s por fase de Spring) y luego libera (`dispose`) y ejecuta el callback. Reiniciable. El `Scheduler` de backoff/timeout es inyectable (tests con tiempo virtual).
- `enabled` por defecto `false` (contextos/tests sin SQS, p. ej. `HealthEndpointTest`, no hacen polling); compose lo activa. El README lo documenta. `SqsConsumerConfig` no importa `SqsConfig` (se escanea igual que antes); declara `@EnableConfigurationProperties` de ambos records.
- Cola: se verificó que `docker/localstack/init-queues.sh` ya crea `orders` con redrive a `orders-dlq`, `maxReceiveCount=3` (reutilizado sin cambios; probado contra compose real).
- Notas de F-012/F-014 del feature_list: un mensaje de una orden ya no RESERVED (liberada, vendida) lo resuelve el caso de uso como `AlreadyProcessed`/`ReleasedAsExpired` y se borra; sin mensaje (muerte entre transacción y publish) queda para F-017.
- Dominio y casos de uso sin cambios.

## Cobertura de acceptance / reglas

- Borrado solo tras éxito: `start_validMessage_deletedOnlyAfterUseCaseSucceeds`, `start_everyTerminalResult_isAcknowledged`, IT `consumer_successfulProcessing_...`.
- Fallo -> reaparece -> DLQ: IT `consumer_alwaysFailingMessage_isRedeliveredThenLandsInDlqAndConsumerKeepsRunning` (exactamente 3 intentos, DLQ=1, el consumidor sigue y procesa un mensaje válido posterior).
- Concurrencia acotada: `start_concurrencyConfigured_neverMoreInFlightAndNextPollWaitsForBatch` (máx 3 en vuelo con batch 10), propiedades y validaciones en `SqsConsumerConfigTest`.
- Poison: test parametrizado (12 cuerpos + null), IT `consumer_poisonMessages_goToDlq...` (2 poison en DLQ, mensaje válido enviado entre medias y otro posterior procesados).
- ReceiveMessage con fallos + backoff acotado, resolución de cola con fallos, apagado (in-flight termina y se borra, long poll cancelado, timeout de apagado dispone), lifecycle idempotente/reiniciable: tests unitarios homónimos.
- C12: `OrderProcessingSqsEndToEndIT`: compra -> publisher real -> consumer real -> SOLD, `sold=qty`, `reserved=0`, cola y DLQ vacías, auditoría `AVAILABLE->RESERVED->PENDING_CONFIRMATION->SOLD`; 30 compras concurrentes de 2 sobre capacidad 40 (20 aceptadas, 10 `InsufficientInventoryException`, todas las aceptadas SOLD, sold=40, invariante); mismo mensaje duplicado manualmente (x2) -> una sola venta (sold=4, 3 entradas de auditoría). Awaitility, sin sleeps fijos.

## Salidas

`./init.sh` (sin Docker/IT): `BUILD SUCCESSFUL in 18s` ... `==> init.sh OK`. Cobertura de líneas global JaCoCo 99.5% (gate 90% verde); `SqsOrderConsumer` 106/106 líneas.

`INCLUDE_INTEGRATION=true ./init.sh` (con DOCKER_HOST/TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE de Colima exportados): `BUILD SUCCESSFUL in 1m 15s` ... `==> init.sh OK`; 427 tests en 44 clases, 0 fallos.

Compose real: `docker-compose up --build -d` -> app `healthy`, `/actuator/health` = `{"status":"UP",...}`; log `SQS order consumer started (batchSize=10, concurrency=4, waitTime=PT20S, visibilityTimeout=PT30S)`; sin errores del consumidor. Se envió un mensaje válido de orden inexistente (borrado: `OrderMissing`) y uno `garbage` (WARN `Discarding poison message ... body is not valid JSON (StreamReadException); not deleted, SQS will redrive it`, sin volcar el cuerpo; quedó en vuelo para el redrive). `docker-compose down -v` ejecutado.

## Notas para el reviewer

- Cambios fuera del código nuevo: refactor mínimo del publisher (resolver compartido) y Awaitility en build.gradle.kts.
- Los commits locales están en `feature/F-016-sqs-consumer`; sin push/PR.
