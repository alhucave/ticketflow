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
| `POST` | `/events/{id}/complimentary` | **Admin** (cabecera `X-Admin-Key`) + `Idempotency-Key`; body `{quantity (1-1000), reason?}`; `201` + `Location: /orders/{orderId}` + `{orderId, eventId, quantity, status: "COMPLIMENTARY"}` |
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

### Cortesías (`POST /events/{id}/complimentary`, solo admin)

Mueve entradas `AVAILABLE -> COMPLIMENTARY` (estado final, **nunca contado como venta**: el contador `complimentary` es independiente de `sold` en inventario, disponibilidad y `GET /events/{id}`). No puede superar el disponible (`409 insufficient-inventory`). Inventario, orden `COMPLIMENTARY` y auditoría (`AVAILABLE -> COMPLIMENTARY`, actor `complimentary-issuance`, `reason` opcional) son un único `TransactWriteItems`; no hay cola ni reserva que expire, y el barrido de expiración y el consumer nunca la tocan. `GET /orders/{orderId}` la muestra como `COMPLIMENTARY`.

- **Seguridad**: la ruta exige la cabecera `X-Admin-Key`, comparada en tiempo constante con el secreto `ticketflow.admin.api-key` (variable `ADMIN_API_KEY`). **Seguro por defecto**: sin clave configurada (o vacía) el endpoint está deshabilitado y responde `403` (`admin-disabled`) a todo; clave ausente o incorrecta -> `401` (`admin-unauthorized`) con texto fijo, sin pistas. La clave nunca se registra ni se versiona. En local, `docker-compose.yml` solo reenvía `ADMIN_API_KEY` (sin valor por defecto): defínala en su `.env` ignorado por git (genere una clave aleatoria, p. ej. con `openssl rand -hex 32`; ver `.env.example`) o en el entorno del shell. En despliegues reales use un gestor de secretos. El filtro `AdminKeyWebFilter` es reutilizable: añada el patrón de otra ruta admin a `ADMIN_ROUTES`.
- **Idempotencia**: `Idempotency-Key` obligatoria (misma validación que `POST /orders`). El `orderId` se deriva de la clave en un espacio de nombres propio (una cortesía y una compra con la misma clave nunca colisionan). Misma clave y mismo payload (`quantity`, `reason`) -> `201` con el mismo body y `Location` (se elige `201`, como `POST /orders` repite `202`); distinto payload -> `409 idempotency-key-reused`.
- `reason` (opcional, máx. 200 caracteres, sin caracteres de control) se guarda como dato en la auditoría; nunca se devuelve ni debe renderizarse como HTML.

```bash
# export ADMIN_API_KEY=$(openssl rand -hex 32)   # clave aleatoria solo para desarrollo local; no la versione
curl -i -X POST http://localhost:8080/events/EVENT_ID/complimentary -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: comp-1' -H "X-Admin-Key: $ADMIN_API_KEY" -d '{"quantity":2,"reason":"VIP guests"}'
# HTTP/1.1 201 Created
# Location: /orders/9b98e91c-ea42-5658-829a-8ac051907723
# {"orderId":"9b98e91c-...","eventId":"EVENT_ID","quantity":2,"status":"COMPLIMENTARY"}

curl -s http://localhost:8080/events/EVENT_ID/availability
# {"available":8,"reserved":0,"pendingConfirmation":0,"sold":0,"complimentary":2,"capacity":10}

# Sin cabecera o con clave incorrecta -> 401; sin clave configurada en el servidor -> 403; más que el disponible -> 409
# {"...","status":401,"title":"Unauthorized","type":"urn:ticketflow:problem:admin-unauthorized"}
# {"...","status":403,"title":"Admin access disabled","type":"urn:ticketflow:problem:admin-disabled"}
```

### Manejo de errores y correlation id

Todo error (de dominio, de validación, de Spring, o inesperado) usa **una sola forma** RFC 7807 (`application/problem+json`): `type` (`urn:ticketflow:problem:*`), `title`, `status`, `detail`, `instance` (`urn:ticketflow:request:<correlationId>`), `correlationId` y, en validación, `violations` (`field`, `message`). Los mensajes de error inesperados, nombres de clase y trazas **nunca** se envían al cliente: el `500` lleva un texto fijo y el error real se registra en el servidor con traza y `correlationId`. Lo gestionan `ApiExceptionHandler` (único traductor) y `ProblemWebExceptionHandler` (errores fuera de los controladores: ruta inexistente, método no permitido, filtros).

| Estado | `type` (`urn:ticketflow:problem:…`) | Cuándo |
|--------|-------------------------------------|--------|
| `400` | `validation-error` | Body con campos inválidos (añade `violations`) |
| `400` | `malformed-request` | JSON mal formado o tipos/valores ilegibles |
| `400` | `invalid-event` | Regla de negocio del evento (p. ej. fecha pasada) |
| `400` | `invalid-idempotency-key` | `Idempotency-Key` ausente o inválida |
| `400` | `bad-request` | Otro error 400 del framework |
| `404` | `event-not-found`, `order-not-found` | El evento o la orden no existe |
| `404` | `not-found` | Ruta inexistente (sin detalles ni la ruta pedida) |
| `405` | `method-not-allowed` | Método HTTP no soportado; conserva la cabecera `Allow` |
| `406` | `not-acceptable` | `Accept` no satisfacible |
| `409` | `event-already-exists` | Evento duplicado |
| `409` | `insufficient-inventory` | No hay entradas suficientes |
| `409` | `idempotency-key-reused` | Misma clave con otro payload |
| `409` | `idempotent-order-not-active` | La orden de esa clave ya fue liberada: usar clave nueva |
| `409` | `concurrent-modification` | Se perdió una carrera de bloqueo optimista del inventario; contención transitoria, **no se aplicó** nada. Lleva `Retry-After: 1` y es seguro reintentar (las compras llevan `Idempotency-Key`) |
| `409` | `invalid-state-transition`, `order-status-conflict`, `order-already-exists` | Conflictos de estado de la orden |
| `410` | `reservation-expired` | La reserva expiró y ya no se puede confirmar |
| `413` | `payload-too-large` | Body por encima del límite del servidor |
| `415` | `unsupported-media-type` | `Content-Type` no soportado |
| `429` | `rate-limit-exceeded` | Demasiadas peticiones; lleva `Retry-After` (segundos) cuando se conoce (lo lanzará el rate limiter, F-023) |
| `500` | `internal-error` | Cualquier error no previsto (texto fijo) |
| `503` | `order-enqueue-failed` | No se pudo encolar la orden: reintentar con una `Idempotency-Key` **nueva** |

Por qué `409` y no `503` para la contención de inventario: la petición chocó con un cambio concurrente y no se aplicó; `503` se reserva para "el servicio no puede aceptar trabajo" (cola caída). **Reintentos**: la capa web no reintenta nada (no puede saber si una petición es repetible); los reintentos con `Retry.backoff` viven en los adaptadores y solo para errores transitorios (p. ej. el publisher SQS), y al cliente se le indica cuándo reintentar con `Retry-After`.

**Correlation id.** Cada petición lleva un `X-Correlation-Id`: se acepta el del cliente solo si tiene 1-64 caracteres `[A-Za-z0-9._-]`; si falta o es inseguro (vacío, largo, con saltos de línea u otros caracteres) se ignora y se genera un UUID (nunca se devuelve ni se registra el valor inseguro). Se devuelve en la cabecera `X-Correlation-Id` de **todas** las respuestas, en la propiedad `correlationId` de cada error, aparece en cada línea de log de la petición (`%X{correlationId}` en el patrón de `application.yml`; JSON estructurado llegará con F-024) y viaja como atributo `correlationId` del mensaje SQS en `POST /orders`.

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
