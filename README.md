# ticketflow

Plataforma reactiva de procesamiento de eventos de ticketing (Java 25, Spring Boot 4, WebFlux, DynamoDB, SQS).

## Verificación y CI

- `./init.sh` ejecuta build, tests y la barrera de cobertura (90%). Las pruebas de integración (Docker) se activan con `INCLUDE_INTEGRATION=true ./init.sh`.
- Con **Colima** (en lugar de Docker Desktop) el socket no está en `/var/run/docker.sock` y Testcontainers no encuentra Docker. Antes de correr las pruebas de integración exporta:
  ```bash
  export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
  ```
  En el CI de GitHub no hace falta.
- `.github/workflows/ci.yml` corre `./init.sh` en cada PR y push a `main` y sube los reportes como artefacto.
- `.github/workflows/release.yml` publica `ghcr.io/alhucave/ticketflow` al empujar un tag `v*`.

## Infraestructura local con Docker Compose

`docker-compose.yml` levanta tres servicios: `app` (Dockerfile multi-stage, usuario no root), `dynamodb` (DynamoDB Local) y `localstack` (SQS). Requiere Docker y Compose (`docker compose` o el binario `docker-compose`).

```bash
cp .env.example .env        # opcional: los valores por defecto ya funcionan
docker-compose up --build   # construye la imagen y arranca todo
docker-compose down -v      # detiene y elimina contenedores y volúmenes
```

| Servicio | Puerto | Notas |
|----------|--------|-------|
| app | 8080 | `GET /actuator/health` |
| dynamodb | 8000 | `amazon/dynamodb-local:3.3.1`, en memoria |
| localstack | 4566 | `localstack/localstack:4.14.0`, solo SQS |

- Al arrancar, `docker/localstack/init-queues.sh` crea la cola `orders` y su DLQ `orders-dlq` (redrive policy con `maxReceiveCount=3`). El healthcheck de LocalStack solo pasa cuando el script terminó, y `app` espera a que `dynamodb` y `localstack` estén healthy.
- Verificar colas: `docker-compose exec localstack awslocal sqs list-queues`.
- Las imágenes tienen tag fijo (sin `:latest`). LocalStack se fija en `4.14.0` porque las versiones `2026.x` exigen `LOCALSTACK_AUTH_TOKEN`.
- Solo se usan credenciales dummy (`test`/`test`). `.env` está en `.gitignore`; nunca commitees secretos.

## Configuración de DynamoDB

Propiedades `ticketflow.dynamodb.*` (variables de entorno `TICKETFLOW_DYNAMODB_*`): `endpoint` (vacío = AWS real), `region` (por defecto `us-east-1`), `access-key-id` / `secret-access-key` (opcionales y solo para local; sin ellas se usa la cadena de credenciales por defecto de AWS) y `provisioning-enabled` (por defecto `false`; `docker-compose.yml` lo activa).

Con `provisioning-enabled=true`, al arrancar (evento `ApplicationReadyEvent`, sin bloquear) se crean de forma idempotente las tablas `events`, `inventory`, `orders` (GSI `idempotencyKey-index` y `status-reservationExpiresAt-index`) y `order_audit` (PK `orderId`, SK `timestamp`). Las tablas existentes no se modifican; solo se añaden GSIs faltantes.

## Configuración de SQS (publicación de órdenes)

Propiedades `ticketflow.sqs.*` (variables de entorno `TICKETFLOW_SQS_*`): `endpoint` (vacío = AWS real), `region` (por defecto `us-east-1`), `access-key-id` / `secret-access-key` (opcionales y solo para local; sin ellas se usa la cadena de credenciales por defecto de AWS), `orders-queue-name` (por defecto `orders`, también desde `ORDERS_QUEUE_NAME`) y `orders-queue-url` (opcional; si se define se usa tal cual y no se resuelve el nombre). `docker-compose.yml` las fija para LocalStack con credenciales dummy.

- `RequestPurchaseUseCase` publica en la cola con el cliente SQS asíncrono del AWS SDK v2 y solo continúa cuando SQS confirma el mensaje.
- Cuerpo del mensaje: JSON versionado `{"version":1,"orderId":"..."}` (la orden se relee de DynamoDB por id). Atributos de mensaje para trazabilidad: `eventId`, `orderId`, `messageVersion` y, si el contexto de Reactor trae la clave `correlationId`, `correlationId`.
- La URL de la cola se resuelve por nombre en la primera publicación y se cachea (los fallos no se cachean). Si la cola no existe se falla con un error claro (`OrderQueueNotFoundException`) y la compra compensa la reserva.
- Solo se reintentan (con `Retry.backoff`) los errores transitorios: throttling, 5xx y errores de conexión. El resto falla de inmediato.
- Semántica **at-least-once**: un reintento o un ack perdido pueden duplicar el mensaje. Es aceptable: el consumidor (`ProcessOrderUseCase`) es idempotente por orden.

## Consumidor SQS (procesamiento de órdenes)

`SqsOrderConsumer` (`infrastructure.messaging`) hace *long polling* reactivo de la cola de órdenes e invoca `ProcessOrderUseCase` por cada mensaje. Es un `SmartLifecycle`: arranca con la aplicación y se detiene de forma ordenada. **Está desactivado por defecto**: solo arranca con `ticketflow.sqs.consumer.enabled=true` (`docker-compose.yml` lo activa para `app`), así que los contextos y tests sin SQS no hacen polling. Reutiliza el cliente y la resolución de URL de cola de `ticketflow.sqs.*`.

Propiedades `ticketflow.sqs.consumer.*` (variables `TICKETFLOW_SQS_CONSUMER_*`):

| Propiedad | Por defecto | Significado |
|-----------|-------------|-------------|
| `enabled` | `false` | Arranca el consumidor |
| `batch-size` | `10` | Mensajes por `ReceiveMessage` (1 a 10) |
| `wait-time` | `20s` | Espera del long polling (1 a 20 s) |
| `visibility-timeout` | `30s` | Tiempo que un mensaje recibido queda invisible; debe superar lo que tarda en procesarse un lote |
| `concurrency` | `4` | Máximo de mensajes procesándose en paralelo (`flatMap`) |
| `shutdown-timeout` | `25s` | Espera máxima a los mensajes en vuelo al detener la aplicación |
| `min-backoff` / `max-backoff` | `1s` / `30s` | Backoff exponencial acotado si `ReceiveMessage` falla (reintenta indefinidamente) |

Semántica **at-least-once**:

- El mensaje se **borra solo después** de que el caso de uso termine con éxito (`Sold`, `ReleasedAsExpired`, `AlreadyProcessed` y `OrderMissing` son resultados terminales).
- Si el caso de uso falla (error transitorio o `OrderStatusConflictException` final), o falla el borrado, el mensaje **no se borra**: SQS lo vuelve a entregar tras el `visibility-timeout` y, superado `maxReceiveCount`, lo mueve a la DLQ. La reentrega es segura porque el caso de uso es idempotente.
- Mensajes *poison* (JSON inválido, `version` desconocida, sin `orderId`): se registran con `WARN` (sin volcar el cuerpo), no se borran y siguen el redrive hacia la DLQ; no detienen el consumidor ni bloquean a los demás mensajes del lote.
- Colas: `docker/localstack/init-queues.sh` crea `orders` con redrive a `orders-dlq` y `maxReceiveCount=3` (configurable con `ORDERS_MAX_RECEIVE_COUNT`). En AWS real hay que crear la cola con una política de redrive equivalente.
- Apagado ordenado: se deja de hacer polling (un long poll en curso se cancela; sus mensajes reaparecen), se espera a los mensajes en vuelo hasta `shutdown-timeout` y después se libera el bucle. Spring espera por defecto 30 s por fase de apagado; mantén `shutdown-timeout` por debajo.
- Inspeccionar la DLQ en local: `docker-compose exec localstack awslocal sqs receive-message --queue-url http://localhost:4566/000000000000/orders-dlq`.

## Configuración de la disponibilidad en tiempo real

- `ticketflow.availability.poll-interval` (por defecto `1s`): cada cuánto consulta el inventario el flujo de disponibilidad. Se puede fijar con la variable de entorno `TICKETFLOW_AVAILABILITY_POLL_INTERVAL`.
