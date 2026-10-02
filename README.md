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

## Configuración de la disponibilidad en tiempo real

- `ticketflow.availability.poll-interval` (por defecto `1s`): cada cuánto consulta el inventario el flujo de disponibilidad. Se puede fijar con la variable de entorno `TICKETFLOW_AVAILABILITY_POLL_INTERVAL`.
