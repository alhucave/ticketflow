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

## Liberación automática de reservas expiradas

Una reserva que no se confirma dentro de su plazo (`ticketflow.reservation.ttl`, 10 min) devuelve sus entradas al inventario disponible. `ReservationExpirationScheduler` (`infrastructure.scheduler`) ejecuta periódicamente `ReleaseExpiredReservationsUseCase`, que busca las órdenes `RESERVED`/`PENDING_CONFIRMATION` con `reservationExpiresAt <= ahora` (índice por `status` + `reservationExpiresAt`) y libera **cada una** con `OrderPlacementRepository.releaseReservation`: un único `TransactWriteItems` (orden a `AVAILABLE` condicionada al estado, auditoría con `reason = "reservation expired"` e inventario `reserved`/`pendingConfirmation` → `available`). No existen estados `EXPIRED`/`FAILED`.

**Desactivado por defecto**: solo corre con `ticketflow.expiration.enabled=true` (`docker-compose.yml` lo activa para `app`), así que los contextos y tests sin DynamoDB no lo arrancan. Es un `SmartLifecycle`, igual que el consumidor SQS.

Propiedades `ticketflow.expiration.*` (variables `TICKETFLOW_EXPIRATION_*`):

| Propiedad | Por defecto | Significado |
|-----------|-------------|-------------|
| `enabled` | `false` | Arranca el job |
| `interval` | `PT1M` | Pausa entre el fin de un barrido y el inicio del siguiente (no hay solapamiento en una instancia) |
| `initial-delay` | `PT10S` | Espera antes del primer barrido tras el arranque |
| `concurrency` | `4` | Órdenes liberadas en paralelo dentro de un barrido |
| `max-per-sweep` | `500` | Máximo de órdenes por barrido; el resto se procesa en el siguiente |
| `shutdown-timeout` | `PT20S` | Espera máxima al barrido en curso al detener la aplicación |

Garantías:

- **Varias instancias / consumidores concurrentes**: la consulta al índice es eventualmente consistente, así que solo da candidatas; la decisión la toma la escritura condicionada. Si dos barridos (o un barrido y `ProcessOrderUseCase`) compiten por la misma orden, gana exactamente uno; el perdedor recibe `OrderStatusConflictException`, que es benigna (se cuenta como `skippedConflicts`, no como error). Una orden que el barrido no ve (recién creada, índice atrasado) se libera en el siguiente.
- **Frontera**: una reserva está expirada cuando `expiresAt <= ahora` (igual en el job y en `ProcessOrderUseCase`).
- **Errores**: el fallo de una orden se registra y se cuenta (`failed`) sin abortar el barrido; el fallo de un barrido completo se registra y el job sigue en el siguiente intervalo.
- Cada barrido registra `examined`, `released`, `skippedConflicts` y `failed`.

## Configuración de la disponibilidad en tiempo real

- `ticketflow.availability.poll-interval` (por defecto `1s`): cada cuánto consulta el inventario el flujo de disponibilidad. Se puede fijar con la variable de entorno `TICKETFLOW_AVAILABILITY_POLL_INTERVAL`.

## Endpoints

API reactiva (Spring WebFlux, `Mono`/`Flux`) de eventos, compras asíncronas y disponibilidad. Con `docker-compose up --build` la app escucha en `http://localhost:8080`.

| Método | Ruta | Respuesta |
|--------|------|-----------|
| `POST` | `/events` | `201` + cabecera `Location: /events/{id}` + evento creado |
| `GET` | `/events/{id}` | `200` evento + inventario (`available`, `reserved`, `pendingConfirmation`, `sold`, `complimentary`); `404` si no existe |
| `GET` | `/events` | `200` lista de eventos **sin inventario** (para los contadores usar `GET /events/{id}`) |
| `POST` | `/orders` | Cabecera obligatoria `Idempotency-Key`; `202` inmediato + `Location: /orders/{orderId}` + `{orderId, status, reservationExpiresAt}` |
| `GET` | `/orders/{id}` | `200` estado de la orden (consultable en cualquier momento); `404` si no existe |
| `GET` | `/events/{id}/availability` | `200` instantánea `{available, reserved, pendingConfirmation, sold, complimentary, capacity}`; `404` si el evento no existe |
| `GET` | `/events/{id}/availability/stream` | `text/event-stream`: emite el valor actual de inmediato y después cada cambio; `404` JSON si el evento no existe |

Validación del body de `POST /events`: `name` y `venue` no vacíos (máx. 200), `capacity` entre 1 y 1.000.000, `startsAt` obligatorio (instante ISO-8601). La regla de negocio "fecha futura" la aplica el caso de uso (`400`). El `id` lo genera el servidor.

### Compras (`POST /orders`)

- **Procesamiento asíncrono**: la petición reserva las entradas (10 min), encola la orden en SQS y responde `202` sin esperar; el consumer la procesa después (`RESERVED -> PENDING_CONFIRMATION -> SOLD`). El progreso se consulta con `GET /orders/{id}`.
- **`Idempotency-Key`** (obligatoria): no vacía, máximo 128 caracteres, solo `[A-Za-z0-9._:-]`; si no, `400`. Reintentar con la misma clave y el mismo body devuelve la **misma orden** (mismo `orderId`, nada se reserva dos veces); el `status` puede haber avanzado desde la primera respuesta. La misma clave con otro body es `409`.
- **Body**: `{"eventId": "...", "quantity": 1..10}`. **Límite: máximo 10 entradas por orden** (acota cuánto inventario puede retener una sola petición); `eventId` no vacío.
- `reservationExpiresAt` solo aparece mientras la reserva sigue viva (`RESERVED`/`PENDING_CONFIRMATION`); se omite en `SOLD`, `COMPLIMENTARY` y `AVAILABLE`.
- **Disponibilidad**: `available` descuenta lo vendido **y** lo reservado temporalmente (`reserved`, `pendingConfirmation`); solo `sold` cuenta como venta.
- **Stream SSE**: si el inventario falla de forma transitoria, el servidor se vuelve a suscribir con backoff exponencial (1 s a 30 s) sin cerrar la conexión del cliente ni enviarle detalles del error.

Errores de esta API: `400` (cabecera `Idempotency-Key` ausente o inválida, body inválido), `404` (orden o evento inexistente), `409` (inventario insuficiente, clave reutilizada con otro payload, orden de esa clave ya liberada: usar una clave nueva), `503` (no se pudo encolar la orden: se puede reintentar con una `Idempotency-Key` **nueva**).

```bash
# Comprar 3 entradas -> 202 + Location (EVENT_ID = id devuelto al crear el evento)
curl -i -X POST http://localhost:8080/orders -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-key-1' -d '{"eventId":"EVENT_ID","quantity":3}'
# HTTP/1.1 202 Accepted
# Location: /orders/bcb8a659-aedf-562d-a829-8a0d1030a974
# {"orderId":"bcb8a659-aedf-562d-a829-8a0d1030a974","status":"RESERVED","reservationExpiresAt":"2026-10-03T01:13:26.726661177Z"}

# Consultar la orden hasta que llegue a SOLD
curl -s http://localhost:8080/orders/bcb8a659-aedf-562d-a829-8a0d1030a974
# {"orderId":"bcb8a659-...","eventId":"EVENT_ID","quantity":3,"status":"SOLD","createdAt":"2026-10-03T01:03:26.726661177Z"}

# Disponibilidad
curl -s http://localhost:8080/events/EVENT_ID/availability
# {"available":17,"reserved":0,"pendingConfirmation":0,"sold":3,"complimentary":0,"capacity":20}

# Reintento con la misma clave y body -> 202 con la misma orden (el status puede haber avanzado)
# {"orderId":"bcb8a659-aedf-562d-a829-8a0d1030a974","status":"SOLD"}

# Misma clave con otro body -> 409; sin cabecera -> 400; quantity 11 -> 400
# {"...","status":409,"title":"Idempotency-Key reused","type":"urn:ticketflow:problem:idempotency-key-reused"}
# {"...","status":400,"title":"Invalid Idempotency-Key","type":"urn:ticketflow:problem:invalid-idempotency-key"}

# Disponibilidad en tiempo real (SSE): valor actual y luego cada cambio
curl -N -H 'Accept: text/event-stream' http://localhost:8080/events/EVENT_ID/availability/stream
# data:{"available":20,"reserved":0,"pendingConfirmation":0,"sold":0,"complimentary":0,"capacity":20}
#
# data:{"available":18,"reserved":0,"pendingConfirmation":0,"sold":2,"complimentary":0,"capacity":20}
```

Los errores siguen RFC 7807 (`application/problem+json`) con `type`, `title`, `status`, `detail`; los fallos de validación añaden `violations` (`field`, `message`). Cubiertos: `400` (validación, JSON mal formado, evento inválido), `404` (evento u orden inexistente), `409` (evento duplicado, conflictos de compras) y `503` (orden no encolada). Nunca se exponen trazas ni mensajes internos.

```bash
# Crear un evento (fecha futura) -> 201 + Location
curl -i -X POST http://localhost:8080/events -H 'Content-Type: application/json' \
  -d '{"name":"Rock Night","startsAt":"2030-01-01T20:00:00Z","venue":"Arena","capacity":120}'
# HTTP/1.1 201 Created
# Location: /events/e8e867f4-5fc8-44e0-8c48-4bcb47f63a40
# {"id":"e8e867f4-...","name":"Rock Night","startsAt":"2030-01-01T20:00:00Z","venue":"Arena","capacity":120}

# Consultar el evento con su inventario
curl -i http://localhost:8080/events/e8e867f4-5fc8-44e0-8c48-4bcb47f63a40
# {"id":"e8e867f4-...","name":"Rock Night",...,"capacity":120,
#  "inventory":{"available":120,"reserved":0,"pendingConfirmation":0,"sold":0,"complimentary":0}}

# Listar eventos
curl -i http://localhost:8080/events

# Body inválido -> 400 con violations
curl -i -X POST http://localhost:8080/events -H 'Content-Type: application/json' \
  -d '{"name":"","venue":"A","capacity":0}'
# {"type":"urn:ticketflow:problem:validation-error","title":"Validation failed","status":400,
#  "detail":"The request body has invalid fields","violations":[{"field":"capacity","message":"must be greater than or equal to 1"},
#  {"field":"name","message":"must not be blank"},{"field":"startsAt","message":"must not be null"}]}

# Id inexistente -> 404
curl -i http://localhost:8080/events/nope
# {"type":"urn:ticketflow:problem:event-not-found","title":"Event not found","status":404,"detail":"Event not found: nope"}
```
