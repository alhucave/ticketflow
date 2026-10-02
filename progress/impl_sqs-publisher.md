# Informe de implementacion: F-015 sqs-publisher

Rama `feature/F-015-sqs-publisher`. Commits locales (sin push, sin PR, feature NO marcada done; en `feature_list.json` queda `in_progress`).

## Archivos

Creados (main):
- `infrastructure/messaging/SqsOrderQueuePublisher.java`: implementa el puerto `OrderQueuePublisher`.
- `infrastructure/messaging/OrderQueueMessage.java`: record versionado `{"version":1,"orderId":"..."}`.
- `infrastructure/messaging/OrderQueueNotFoundException.java`: error claro si la cola no existe.
- `infrastructure/config/SqsProperties.java`, `SqsConfig.java`: propiedades `ticketflow.sqs.*`, cliente `SqsAsyncClient` y bean del publisher (mismo estilo que `DynamoDbProperties`/`DynamoDbConfig`).

Modificados:
- `UseCaseConfig`: eliminado el fallback siempre-error; `requestPurchaseUseCase` recibe `OrderQueuePublisher` directamente (sin `ObjectProvider`).
- `application.yml`: `ticketflow.sqs.orders-queue-name: ${ORDERS_QUEUE_NAME:orders}`.
- `docker-compose.yml` (servicio app): `TICKETFLOW_SQS_ENDPOINT/ACCESS_KEY_ID/SECRET_ACCESS_KEY/REGION/ORDERS_QUEUE_NAME` (credenciales dummy `test`/`test`).
- `README.md`: seccion "Configuracion de SQS" (en espanol, incluye at-least-once).
- `UseCaseConfigTest`: quitado el test del fallback.

Tests nuevos:
- `SqsOrderQueuePublisherTest` (unitario, mocks + StepVerifier): exito (cuerpo y atributos), completa solo tras el ack, correlationId desde contexto Reactor, URL cacheada, URL configurada sin resolver, transitorio (503/throttling) reintentado y luego ok, error de conexion reintentado, transitorio persistente falla tras reintentos acotados con el error original, no transitorio (403) sin reintento, cola inexistente (sin reintento ni envio), el fallo de resolucion no se cachea, resolucion transitoria reintentada, clasificacion `isTransient`.
- `SqsConfigTest`: credenciales estaticas/cadena por defecto, endpoint/region/cola desde propiedades.
- `SqsOrderQueuePublisherIT` (LocalStack 4.14.0, `testcontainers-localstack:2.0.5`, paquete `org.testcontainers.localstack`): mensaje llega con cuerpo y atributos (eventId, orderId, messageVersion, correlationId); endpoint/cola desde propiedades; URL de cola por configuracion; URL con host no resoluble sigue yendo al endpoint configurado; cola inexistente falla claro y no se crea; cola creada despues se detecta sin reiniciar.
- `RequestPurchaseSqsEndToEndIT` (C12): `RequestPurchaseUseCase` con adaptadores DynamoDB reales (DynamoDB Local) y publisher SQS real (LocalStack): compra -> mensaje SQS con orderId y reserva intacta; cola inexistente y SQS inalcanzable (conexion rechazada) -> compensacion atomica (inventario liberado, version 2, orden AVAILABLE, auditoria RESERVED->AVAILABLE con razon `enqueue failed`, invariante de contadores).

## Decisiones de diseno
- JSON: `tools.jackson.databind.json.JsonMapper` (Jackson 3.1.5, que ya viene gestionado por Spring Boot 4.1.1 via `spring-boot-starter-jackson` transitivo de webflux); sin librerias no gestionadas. Se usa un `JsonMapper` estatico propio del publisher (serializa un record trivial), sin depender de beans del contexto.
- Cuerpo minimo (`version`, `orderId`); atributos de mensaje: `eventId`, `orderId`, `messageVersion` (Number) y `correlationId` opcional leido de la clave `correlationId` del contexto de Reactor (no hay otra fuente de trace id aun; la capa web de F posteriores puede escribirlo).
- Retry: `Retry.backoff(3, 100ms)` con filtro `isTransient`: `AwsServiceException` con throttling o status >= 500, `SdkClientException` retryable o con causa `IOException`. Errores 4xx (acceso denegado, parametros invalidos, cola inexistente) no se reintentan. Agotados los reintentos se propaga el error original. Duplicados posibles por reintento tras ack perdido: documentado (at-least-once; consumidor idempotente).
- URL de cola: resuelta por nombre con `GetQueueUrl`, cacheada con `Mono.cache(ttl, errorTtl=0, emptyTtl=0)`: los fallos no se cachean. `QueueDoesNotExistException` -> `OrderQueueNotFoundException`. Alternativa: `ticketflow.sqs.orders-queue-url`.
- Hallazgo: LocalStack devuelve URLs `sqs.<region>.localhost.localstack.cloud:4566`, que no resuelven dentro de la red de compose; el SDK v2 usa el endpoint configurado y no el host de la URL, lo cual queda cubierto por un IT.
- Dominio sin cambios (puro). Sin `.block()` en codigo de produccion (solo en tests, como en los IT existentes). Sin secretos: solo `test`/`test` en compose.
- No se toca el `init-queues.sh` (ya crea `orders` y DLQ).

## Salida de ./init.sh
Sin Docker (`./init.sh`): `BUILD SUCCESSFUL in 19s` ... `==> init.sh OK` (gate JaCoCo 90% en verde).
Con integracion (`DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock INCLUDE_INTEGRATION=true ./init.sh`): `BUILD SUCCESSFUL in 1m 3s` ... `==> init.sh OK`; cobertura de lineas global 99.5%.

## Verificacion con docker-compose
`docker-compose up --build -d`: los tres servicios healthy (app "Up (healthy)"), `GET :8080/actuator/health` -> `{"status":"UP",...}`, `awslocal sqs list-queues` -> `orders` y `orders-dlq`; sin errores de SQS en los logs de app. `docker-compose down -v` ejecutado (contenedores, red y volumenes eliminados). No hay endpoint HTTP de compra todavia (capa web pendiente), por eso el flujo de publicacion se valida en los IT.

## Notas para el reviewer
- `build.gradle.kts` ya tenia `sqs` y `testcontainers-localstack`; no se modifico.
- C12: los IT usan adaptadores reales; el reloj del E2E avanza un segundo por llamada porque la SK de auditoria es el timestamp (igual que en `RequestPurchaseUseCaseIT`).
