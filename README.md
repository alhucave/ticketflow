# ticketflow

Plataforma reactiva de procesamiento de eventos de ticketing (Java 25, Spring Boot 4, WebFlux, DynamoDB, SQS). Permite crear eventos con un inventario de entradas, comprarlas de forma **asíncrona e idempotente** (la API responde `202` al instante y un consumidor SQS confirma la venta), consultar la disponibilidad en tiempo real (instantánea y *stream* SSE), emitir cortesías (solo administración) y recuperar automáticamente las reservas que no se confirman. El inventario nunca se sobrevende, incluso con cientos de compras concurrentes: todo cambio de inventario es una escritura condicionada de DynamoDB dentro de una transacción atómica.

## Contenido

1. [Inicio rápido](#inicio-rápido)
2. [Arquitectura](#arquitectura)
3. [Estructura del proyecto](#estructura-del-proyecto)
4. [Instalación y ejecución en detalle](#instalación-y-ejecución-en-detalle)
5. [Referencia de configuración](#referencia-de-configuración)
6. [Referencia de la API](#referencia-de-la-api)
7. [Catálogo de errores](#catálogo-de-errores)
8. [Flujo de compra paso a paso](#flujo-de-compra-paso-a-paso)
9. [Colección de peticiones y demo](#colección-de-peticiones-y-demo)
10. [Pruebas y cobertura](#pruebas-y-cobertura)
11. [Observabilidad](#observabilidad)
12. [Seguridad](#seguridad)
13. [CI/CD](#cicd)
14. [Despliegue en AWS (diseño)](#despliegue-en-aws-diseño)
15. [Decisiones de diseño](#decisiones-de-diseño)
16. [Solución de problemas](#solución-de-problemas)
17. [Limitaciones conocidas](#limitaciones-conocidas)

## Inicio rápido

Requisitos: **Docker** con Compose (en macOS puede ser Colima; vale el binario `docker-compose` o el plugin `docker compose`, los comandos de este documento usan `docker-compose`). **JDK 25 solo hace falta para compilar y probar en local** (`./gradlew`, `./init.sh`): para ejecutar la aplicación basta Docker, porque la imagen se construye dentro de Docker.

```bash
# El repositorio es privado: hace falta ser colaborador y haber iniciado sesión en GitHub (p. ej. `gh auth login`)
git clone https://github.com/alhucave/ticketflow.git && cd ticketflow

export ADMIN_API_KEY=$(openssl rand -hex 32)   # clave de administración solo para esta sesión (ver «Seguridad»)
docker-compose up --build -d --wait             # app + DynamoDB Local + LocalStack (SQS); espera a que la app esté sana

./requests/demo.sh                              # flujo completo con curl: evento, compra 202, SOLD, replay, errores, cortesías
./requests/run-newman.sh                        # la colección de Postman completa con Newman (Docker): 60+ aserciones

docker-compose down -v                          # detiene todo y borra los datos
```

- `up --build` tarda unos minutos la primera vez (compila la aplicación con Gradle dentro de Docker). Con Compose v1 (`docker-compose` de Python) `--wait` no existe: omítalo, `demo.sh` espera por sí mismo a que la app esté lista.
- Sin `ADMIN_API_KEY` (ni al arrancar la pila ni al ejecutar el demo y Newman) todo funciona salvo las cortesías, que responden `403`: el demo y Newman omiten esos pasos. Si define la clave, debe ser **la misma** al arrancar la pila y al ejecutar los scripts.
- API en `http://localhost:8080`; sondas y métricas en `http://localhost:8081`.
- Con **Colima**, para las pruebas con Testcontainers exporte antes `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` y `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` (ver [Pruebas](#pruebas-y-cobertura)). `docker-compose` no lo necesita si su contexto de Docker ya apunta a Colima.

Probar a mano, en tres peticiones (con la pila arriba):

```bash
EVENT_ID=$(curl -s -X POST localhost:8080/events -H 'Content-Type: application/json' \
  -d '{"name":"Rock Night","startsAt":"2030-01-01T20:00:00Z","venue":"Arena","capacity":120}' | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
curl -i -X POST localhost:8080/orders -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"eventId\":\"$EVENT_ID\",\"quantity\":3}"            # 202 Accepted + Location: /orders/{orderId}
curl -s localhost:8080/events/$EVENT_ID/availability         # {"available":117,...,"sold":3,...} cuando el consumer termina
```

## Arquitectura

Clean Architecture de tres capas (`infrastructure → usecase → domain`) con puertos y adaptadores: la API WebFlux, el consumidor SQS y el job de expiración son adaptadores de entrada; DynamoDB y SQS, adaptadores de salida. El inventario son **contadores por evento** que solo cambian con escrituras condicionadas en transacciones (`TransactWriteItems`).

Los diagramas (componentes, secuencia de la compra con compensación y replay, expiración y su carrera con el consumer, cortesías, máquina de estados y modelo de datos) están en [`docs/architecture.md`](docs/architecture.md) y se renderizan en GitHub:

- [Componentes y capas](docs/architecture.md#1-componentes-y-capas)
- [Flujo de compra](docs/architecture.md#2-flujo-de-compra)
- [Expiración y carrera con el consumer](docs/architecture.md#3-expiración-y-carrera-con-el-consumer)
- [Emisión de cortesías](docs/architecture.md#4-emisión-de-cortesías)
- [Estados de una entrada](docs/architecture.md#5-estados-de-una-entrada)
- [Modelo de datos (DynamoDB)](docs/architecture.md#6-modelo-de-datos-dynamodb)

## Estructura del proyecto

```
com.ticketflow
├── domain                       # núcleo puro: sin Spring ni AWS SDK
│   ├── model                    # Event, Order, Inventory, TicketStatus (máquina de estados), OrderId, IdempotencyKey...
│   ├── exception                # errores de dominio tipados
│   └── port                     # puertos de salida (repositorios, OrderQueuePublisher) con Mono/Flux
├── usecase                      # casos de uso (clases sin anotaciones de Spring): compra, proceso de orden, expiración, cortesías, lecturas
└── infrastructure
    ├── web                      # controllers WebFlux, DTOs, mappers y filtros (cabeceras de seguridad, clave de admin)
    │   ├── error                # ApiExceptionHandler (único traductor a problem+json) y correlation id
    │   └── ratelimit            # token bucket por cliente
    ├── persistence              # adaptadores DynamoDB (SDK v2 async) y creación idempotente de tablas
    ├── messaging                # publisher y consumer SQS
    ├── scheduler                # job de liberación de reservas expiradas
    ├── observability            # métricas Micrometer, health indicators, monitor de profundidad de cola
    └── config                   # @Configuration y @ConfigurationProperties

Raíz: Dockerfile · docker-compose.yml · .env.example · init.sh · build.gradle.kts + gradle.lockfile
docker/        healthcheck de la imagen y script que crea las colas en LocalStack
docs/          architecture.md · conventions.md · verification.md · security.md · observability.md
requests/      colección de Postman, entorno, run-newman.sh y demo.sh
.github/       workflows de CI, seguridad y release; dependabot
feature_list.json · progress/ · AGENTS.md · CHECKPOINTS.md   # arnés de trabajo por features
```

## Instalación y ejecución en detalle

### Docker Compose (recomendado)

`docker-compose.yml` levanta tres servicios: `app` (Dockerfile multi-etapa, runtime *distroless* solo JRE, usuario no root), `dynamodb` (DynamoDB Local) y `localstack` (SQS).

```bash
cp .env.example .env        # opcional: los valores por defecto ya funcionan. Aquí puede fijar ADMIN_API_KEY y cambiar puertos
docker-compose up --build   # en primer plano (o con -d --wait en segundo plano)
docker-compose ps           # estado y salud
docker-compose logs -f app  # logs JSON, una línea por objeto, con correlationId
docker-compose down -v      # detiene y elimina contenedores y volúmenes
```

| Servicio | Puerto en el host | Notas |
|----------|-------------------|-------|
| app | `127.0.0.1:8080` | API pública. **No** sirve `/actuator/**` (responde `404`) |
| app (gestión) | `127.0.0.1:8081` | Actuator: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info`, `/actuator/prometheus` |
| dynamodb | `127.0.0.1:8000` | `amazon/dynamodb-local:3.3.1`, en memoria: los datos se pierden al detenerlo |
| localstack | `127.0.0.1:4566` | `localstack/localstack:4.14.0`, solo SQS |

- Al arrancar, `docker/localstack/init-queues.sh` crea la cola `orders` y su DLQ `orders-dlq` (redrive con `maxReceiveCount=3`). El healthcheck de LocalStack solo pasa cuando el script terminó, y `app` espera a que `dynamodb` y `localstack` estén sanos. Con `TICKETFLOW_DYNAMODB_PROVISIONING_ENABLED=true` (lo activa compose) la app crea las tablas al arrancar.
- Verificar colas: `docker-compose exec localstack awslocal sqs list-queues`. Inspeccionar la DLQ: `docker-compose exec localstack awslocal sqs receive-message --queue-url http://localhost:4566/000000000000/orders-dlq`.
- Las imágenes tienen tag fijo (sin `:latest`). Todos los puertos se publican solo en `127.0.0.1` (DynamoDB Local y LocalStack no tienen autenticación). Detalle del endurecimiento (sistema de ficheros de solo lectura, `cap_drop: ALL`, límites de recursos): [`docs/security.md`](docs/security.md#endurecimiento-de-contenedores).
- Solo se usan credenciales ficticias (`test`/`test`). `.env` está en `.gitignore`: nunca commitee secretos.
- Compose solo reenvía a `app` las variables que lista en `environment:`. Para cambiar otras propiedades (p. ej. `TICKETFLOW_RATE_LIMIT_CAPACITY`) añádalas a ese bloque o use un `docker-compose.override.yml` propio (no se versiona).

### Imagen suelta

```bash
docker build -t ticketflow:local .
```

La imagen expone `8080` (API) y `8081` (gestión) y necesita las variables de [configuración](#referencia-de-configuración) para encontrar DynamoDB y SQS; sin ellas usa los endpoints reales de AWS. Al empujar un tag `v*` el CI publica `ghcr.io/alhucave/ticketflow` (ver [CI/CD](#cicd)).

### Compilar y probar (JDK 25)

```bash
./init.sh                              # build + tests unitarios + barrera de cobertura del 90 %
INCLUDE_INTEGRATION=true ./init.sh     # además, las pruebas de integración con Testcontainers (requieren Docker)
```

Las dependencias están **bloqueadas** (`gradle.lockfile`): al cambiar una versión ejecute `./gradlew dependencies --write-locks` y versione el lockfile (si no, el build falla).

## Referencia de configuración

Spring Boot acepta cada propiedad como variable de entorno en mayúsculas con `_` (p. ej. `ticketflow.sqs.consumer.batch-size` → `TICKETFLOW_SQS_CONSUMER_BATCH_SIZE`). La columna «Compose» indica si `docker-compose.yml` ya la fija.

### Variables de Docker Compose / `.env`

| Variable | Por defecto | Significado |
|----------|-------------|-------------|
| `APP_PORT` | `8080` | Puerto del host de la API (siempre en `127.0.0.1`) |
| `MANAGEMENT_PORT` | `8081` | Puerto del host del Actuator |
| `DYNAMODB_PORT` | `8000` | Puerto del host de DynamoDB Local |
| `LOCALSTACK_PORT` | `4566` | Puerto del host de LocalStack |
| `AWS_REGION` | `us-east-1` | Región de los clientes AWS y de LocalStack |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `test` / `test` | Credenciales **ficticias** para DynamoDB Local y LocalStack |
| `ORDERS_QUEUE_NAME` | `orders` | Cola de órdenes (la crea `init-queues.sh` y la usa la app) |
| `ORDERS_DLQ_NAME` | `orders-dlq` | Cola de mensajes fallidos |
| `ORDERS_MAX_RECEIVE_COUNT` | `3` | Recepciones antes de pasar un mensaje a la DLQ |
| `ADMIN_API_KEY` | vacío | Clave de las rutas admin (`X-Admin-Key`). **Vacía = rutas admin deshabilitadas (`403`)** |
| `TICKETFLOW_ENVIRONMENT` | `local` | Etiqueta `service.environment` de los logs |

### Propiedades de la aplicación (`ticketflow.*` y relacionadas)

| Propiedad (variable) | Por defecto | Significado |
|----------------------|-------------|-------------|
| `ticketflow.admin.api-key` (`ADMIN_API_KEY`) | vacío | Secreto de las rutas admin. Nunca se versiona ni se registra |
| `ticketflow.orders.max-quantity` | `10` | Máximo de entradas por orden (`400` por encima) |
| `ticketflow.reservation.ttl` | `PT10M` | Vigencia de una reserva antes de expirar |
| `ticketflow.availability.poll-interval` | `1s` | Cada cuánto consulta el inventario el stream de disponibilidad |
| `spring.http.codecs.max-in-memory-size` | `32KB` | Tamaño máximo del body (`413` por encima) |
| `management.server.port` (`MANAGEMENT_SERVER_PORT`) | `8081` | Puerto del Actuator |
| `management.server.address` (`MANAGEMENT_SERVER_ADDRESS`) | `127.0.0.1` | Dirección de escucha del Actuator (la imagen y compose la fijan a `0.0.0.0`) |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | sin definir (la imagen y compose fijan `ecs`) | `ecs` = logs JSON; sin definir = patrón legible `[correlationId]` |

**DynamoDB** (`ticketflow.dynamodb.*`, `TICKETFLOW_DYNAMODB_*`):

| Propiedad | Por defecto | Significado |
|-----------|-------------|-------------|
| `endpoint` | vacío (AWS real) | Endpoint (compose: `http://dynamodb:8000`) |
| `region` | `us-east-1` | Región |
| `access-key-id` / `secret-access-key` | vacío | Credenciales estáticas, **solo local**; deben estar ambas. Sin ellas se usa la cadena por defecto de AWS (rol IAM) |
| `provisioning-enabled` | `false` (compose: `true`) | Crea tablas e índices al arrancar, de forma idempotente (sin borrar nada) |
| `provisioning-max-attempts` | `30` | Intentos de espera a que una tabla esté `ACTIVE` |
| `provisioning-poll-interval` | `500ms` | Pausa entre esos intentos |

**SQS** (`ticketflow.sqs.*`, `TICKETFLOW_SQS_*`):

| Propiedad | Por defecto | Significado |
|-----------|-------------|-------------|
| `endpoint` | vacío (AWS real) | Endpoint (compose: `http://localstack:4566`) |
| `region` | `us-east-1` | Región |
| `access-key-id` / `secret-access-key` | vacío | Igual que en DynamoDB |
| `orders-queue-name` (`ORDERS_QUEUE_NAME`) | `orders` | Nombre de la cola; su URL se resuelve en el primer uso y se cachea |
| `orders-queue-url` | vacío | Si se define se usa tal cual y no se resuelve el nombre |

**Consumidor** (`ticketflow.sqs.consumer.*`, `TICKETFLOW_SQS_CONSUMER_*`):

| Propiedad | Por defecto | Significado |
|-----------|-------------|-------------|
| `enabled` | `false` (compose: `true`) | Arranca el consumidor (los contextos sin SQS no hacen polling) |
| `batch-size` | `10` | Mensajes por `ReceiveMessage` (1 a 10) |
| `wait-time` | `20s` | Espera del long polling (1 a 20 s) |
| `visibility-timeout` | `30s` | Tiempo que un mensaje recibido queda invisible; debe superar lo que tarda un lote |
| `concurrency` | `4` | Mensajes procesándose en paralelo |
| `shutdown-timeout` | `25s` | Espera a los mensajes en vuelo al detener la app |
| `min-backoff` / `max-backoff` | `1s` / `30s` | Backoff exponencial si `ReceiveMessage` falla (reintenta indefinidamente) |

**Expiración de reservas** (`ticketflow.expiration.*`, `TICKETFLOW_EXPIRATION_*`):

| Propiedad | Por defecto | Significado |
|-----------|-------------|-------------|
| `enabled` | `false` (compose: `true`) | Arranca el job |
| `interval` | `PT1M` | Pausa entre el fin de un barrido y el inicio del siguiente |
| `initial-delay` | `PT10S` | Espera antes del primer barrido |
| `concurrency` | `4` | Órdenes liberadas en paralelo en un barrido |
| `max-per-sweep` | `500` | Máximo de órdenes por barrido |
| `shutdown-timeout` | `PT20S` | Espera al barrido en curso al detener la app |

**Rate limiting** (`ticketflow.rate-limit.*`, `TICKETFLOW_RATE_LIMIT_*`, activo por defecto):

| Propiedad | Por defecto | Significado |
|-----------|-------------|-------------|
| `enabled` | `true` | Desactiva el limitador (solo pruebas) |
| `capacity` | `20` | Ráfaga de escrituras por cliente |
| `refill-per-second` | `1` | Ritmo sostenido de escrituras por cliente |
| `admin-failure-capacity` | `5` | Intentos fallidos de `X-Admin-Key` tolerados de golpe |
| `admin-failure-refill-per-second` | `0.05` | Recuperación de esos intentos (uno cada 20 s) |
| `max-clients` | `10000` | Clientes seguidos (memoria acotada) |
| `idle-ttl` | `15m` | Un cliente inactivo se olvida |
| `trust-forwarded-for` | `false` | Identificar al cliente por la **última** entrada de `X-Forwarded-For` (solo detrás de exactamente un proxy de confianza) |

**Observabilidad** (`ticketflow.observability.*`, `TICKETFLOW_OBSERVABILITY_*`):

| Propiedad | Por defecto | Significado |
|-----------|-------------|-------------|
| `queue-metrics.enabled` | `false` (compose: `true`) | Sondeo de la profundidad de la cola y la DLQ para los gauges |
| `queue-metrics.interval` | `15s` | Periodo del sondeo (mínimo `1s`) |
| `queue-metrics.timeout` | `5s` | Un sondeo más lento cuenta como error |
| `health.timeout` | `2s` | Una dependencia más lenta cuenta como `DOWN` en *readiness* |
| `health.cache-ttl` | `5s` | Cuánto se reutiliza el último resultado de salud |

## Referencia de la API

API reactiva sobre `http://localhost:8080`. Los cuerpos de petición y respuesta son JSON; **todo error** es `application/problem+json` (RFC 7807, ver [catálogo](#catálogo-de-errores)). Las variables `$EVENT_ID`, `$ORDER_ID` y `$KEY` de los ejemplos se obtienen de las respuestas anteriores.

**Reglas comunes**

- **`X-Correlation-Id`** (opcional): si cumple `[A-Za-z0-9._-]{1,64}` se acepta; si no, se genera un UUID. Se devuelve en **todas** las respuestas, en la propiedad `correlationId` de cada error, en cada línea de log y como atributo del mensaje SQS.
- **`Idempotency-Key`** (obligatoria en `POST /orders` y `POST /events/{id}/complimentary`): 16 a 128 caracteres `[A-Za-z0-9._:-]` (un UUID sirve). Misma clave y mismo payload → la **misma orden** (mismo `orderId`, nada se reserva dos veces; el `status` puede haber avanzado). Misma clave con otro payload → `409 idempotency-key-reused`. Si la orden de esa clave ya fue liberada (publicación fallida o reserva expirada) → `409 idempotent-order-not-active`: use una clave **nueva**. El mínimo de 16 existe porque el `orderId` se deriva solo de la clave (SHA-256): una clave corta y adivinable permitiría chocar con la orden de otro cliente.
- **Rate limit** (por cliente, token bucket): `POST /orders`, `POST /events` y `POST /events/{id}/complimentary` comparten un presupuesto de **20 de ráfaga y 1 por segundo**; agotado → `429 rate-limit-exceeded` con `Retry-After` (segundos) sin leer el body ni tocar DynamoDB/SQS. Las lecturas no se limitan. Además, los intentos **fallidos** de `X-Admin-Key` tienen un presupuesto de 5 (1 cada 20 s): agotado, `429` sin comparar la clave.
- **Admin**: las rutas de administración exigen `X-Admin-Key` (comparación en tiempo constante con `ADMIN_API_KEY`). Sin clave configurada en el servidor → `403 admin-disabled`; clave ausente o incorrecta → `401 admin-unauthorized`. No hay autenticación de usuarios.
- Límites: body máximo 32 KB (`413`), máximo 10 entradas por orden, ids de ruta `[A-Za-z0-9._-]{1,64}` (otro formato → `404` genérico).
- Todas las respuestas llevan cabeceras de seguridad (`nosniff`, `no-store`, `X-Frame-Options: DENY`, CSP restrictiva...).

### Resumen

| Método | Ruta | Éxito | Errores principales |
|--------|------|-------|---------------------|
| `POST` | `/events` | `201` + `Location: /events/{id}` | `400`, `413`, `415`, `429`, `503` |
| `GET` | `/events/{id}` | `200` evento + inventario | `404` |
| `GET` | `/events` | `200` lista (sin inventario) | — |
| `POST` | `/orders` | `202` + `Location: /orders/{orderId}` | `400`, `404`, `409`, `413`, `415`, `429`, `503` |
| `GET` | `/orders/{id}` | `200` estado de la orden | `404` |
| `POST` | `/events/{id}/complimentary` | `201` + `Location: /orders/{orderId}` | `400`, `401`, `403`, `404`, `409`, `429` |
| `GET` | `/events/{id}/availability` | `200` instantánea | `404` |
| `GET` | `/events/{id}/availability/stream` | `200` `text/event-stream` | `404` (JSON, antes de abrir el stream) |

### `POST /events` — crear un evento

Cabecera `Content-Type: application/json`. Body: `name` y `venue` no vacíos (máx. 200), `capacity` entre 1 y 1.000.000, `startsAt` instante ISO-8601 **futuro** (la regla «fecha futura» da `400 invalid-event`). El `id` lo genera el servidor.

```bash
curl -i -X POST http://localhost:8080/events -H 'Content-Type: application/json' \
  -d '{"name":"Rock Night","startsAt":"2030-01-01T20:00:00Z","venue":"Arena","capacity":120}'
# HTTP/1.1 201 Created
# Location: /events/e8e867f4-5fc8-44e0-8c48-4bcb47f63a40
# {"id":"e8e867f4-5fc8-44e0-8c48-4bcb47f63a40","name":"Rock Night","startsAt":"2030-01-01T20:00:00Z","venue":"Arena","capacity":120}
```

Errores: `400 validation-error` (con `violations`: `field` y `message`), `400 malformed-request` (JSON roto), `400 invalid-event` (fecha pasada).

### `GET /events/{id}` y `GET /events`

```bash
curl -s http://localhost:8080/events/$EVENT_ID
# {"id":"e8e867f4-...","name":"Rock Night","startsAt":"2030-01-01T20:00:00Z","venue":"Arena","capacity":120,
#  "inventory":{"available":120,"reserved":0,"pendingConfirmation":0,"sold":0,"complimentary":0}}
curl -s http://localhost:8080/events        # array de eventos SIN inventario (para los contadores use GET /events/{id})
```

`404 event-not-found` si no existe.

### `POST /orders` — comprar (asíncrono)

Cabeceras: `Content-Type: application/json`, `Idempotency-Key` (obligatoria). Body: `{"eventId": "...", "quantity": 1..10}` (el máximo es `ticketflow.orders.max-quantity`; por encima, `400 validation-error` con la violación de `quantity`).

Responde `202` **sin esperar** al procesamiento: reserva las entradas (10 min), encola la orden y devuelve `orderId`, `status` y `reservationExpiresAt` (solo mientras la reserva sigue viva). La reserva y la publicación **no se cancelan si el cliente se desconecta**.

```bash
KEY=$(uuidgen)
curl -i -X POST http://localhost:8080/orders -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d "{\"eventId\":\"$EVENT_ID\",\"quantity\":3}"
# HTTP/1.1 202 Accepted
# Location: /orders/bcb8a659-aedf-562d-a829-8a0d1030a974
# {"orderId":"bcb8a659-aedf-562d-a829-8a0d1030a974","status":"RESERVED","reservationExpiresAt":"2026-10-03T01:13:26.726661177Z"}
```

Errores: `400` (`invalid-idempotency-key`, `validation-error`, `malformed-request`), `404 event-not-found`, `409` (`insufficient-inventory`, `idempotency-key-reused`, `idempotent-order-not-active`, `concurrent-modification` con `Retry-After: 1`), `503 order-enqueue-failed` (no se pudo encolar: la reserva se compensó; reintente con clave **nueva**) o `503 service-unavailable` (dependencia caída: reintente tras `Retry-After`).

### `GET /orders/{id}` — estado de la orden

```bash
curl -s http://localhost:8080/orders/$ORDER_ID
# {"orderId":"bcb8a659-...","eventId":"e8e867f4-...","quantity":3,"status":"SOLD","createdAt":"2026-10-03T01:03:26.726661177Z"}
```

`status` es uno de `RESERVED`, `PENDING_CONFIRMATION`, `SOLD`, `COMPLIMENTARY` o `AVAILABLE` (reserva liberada por expiración o fallo de publicación; **no hay** estados `EXPIRED`/`FAILED`). `reservationExpiresAt` solo aparece mientras la reserva está viva (`RESERVED`/`PENDING_CONFIRMATION`). `404 order-not-found` si no existe.

### `POST /events/{id}/complimentary` — cortesías (solo admin)

Cabeceras: `X-Admin-Key`, `Idempotency-Key`, `Content-Type: application/json`. Body: `{"quantity": 1..1000, "reason": "opcional, máx. 200, sin caracteres de control"}`. Mueve entradas `AVAILABLE → COMPLIMENTARY` (final, **nunca contada como venta**) en una sola transacción; no hay cola ni reserva que expire. Misma clave y mismo payload → `201` con el mismo body.

```bash
curl -i -X POST http://localhost:8080/events/$EVENT_ID/complimentary -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -H "X-Admin-Key: $ADMIN_API_KEY" -d '{"quantity":2,"reason":"VIP guests"}'
# HTTP/1.1 201 Created
# Location: /orders/9b98e91c-ea42-5658-829a-8ac051907723
# {"orderId":"9b98e91c-...","eventId":"e8e867f4-...","quantity":2,"status":"COMPLIMENTARY"}
```

Errores: `403 admin-disabled` (el servidor no tiene `ADMIN_API_KEY`), `401 admin-unauthorized` (clave ausente o incorrecta; lleva `WWW-Authenticate: ApiKey`), `409 insufficient-inventory` (más que el disponible), `409 idempotency-key-reused`, `400`, `404`, `429`. El `reason` se guarda como dato de auditoría: nunca se devuelve ni debe renderizarse como HTML.

### `GET /events/{id}/availability` — disponibilidad

```bash
curl -s http://localhost:8080/events/$EVENT_ID/availability
# {"available":117,"reserved":0,"pendingConfirmation":0,"sold":3,"complimentary":0,"capacity":120}
```

`available` descuenta lo vendido **y** lo retenido (`reserved`, `pendingConfirmation`); solo `sold` cuenta como venta. `404 event-not-found` si no existe.

### `GET /events/{id}/availability/stream` — disponibilidad en tiempo real (SSE)

Emite el valor actual de inmediato y después **solo cuando cambia** (consulta el inventario cada `ticketflow.availability.poll-interval`). Si DynamoDB falla de forma transitoria, el servidor se resuscribe con backoff exponencial (1 s a 30 s) sin cerrar la conexión ni enviar detalles del error.

```bash
curl -N -H 'Accept: text/event-stream' http://localhost:8080/events/$EVENT_ID/availability/stream
# data:{"available":117,"reserved":0,"pendingConfirmation":0,"sold":3,"complimentary":0,"capacity":120}
#
# data:{"available":115,"reserved":0,"pendingConfirmation":0,"sold":3,"complimentary":2,"capacity":120}
```

### Puerto de gestión (`8081`)

| Ruta | Respuesta |
|------|-----------|
| `GET /actuator/health/liveness` | `200 {"status":"UP"}`; nunca depende de sistemas externos |
| `GET /actuator/health/readiness` | `200 UP` solo si DynamoDB y la cola responden; `503 DOWN` si no (unos segundos tras arrancar mientras se crean las tablas) |
| `GET /actuator/health` | Estado agregado, sin detalles |
| `GET /actuator/info` | Información de la app |
| `GET /actuator/prometheus` | Métricas `ticketflow_*` y de JVM/HTTP en formato Prometheus |

## Catálogo de errores

Todo error (de dominio, de validación, de Spring o inesperado) usa **una sola forma** RFC 7807 (`application/problem+json`): `type` (`urn:ticketflow:problem:*`), `title`, `status`, `detail`, `instance` (`urn:ticketflow:request:<correlationId>`), `correlationId` y, en validación, `violations`. Los mensajes de error inesperados, nombres de clase y trazas **nunca** se envían al cliente: el `500` lleva un texto fijo y el error real se registra en el servidor con traza y `correlationId`. Lo traduce `ApiExceptionHandler` (único traductor) y `ProblemWebExceptionHandler` (errores fuera de los controladores: ruta inexistente, método no permitido, filtros).

| Estado | `type` (`urn:ticketflow:problem:…`) | Cuándo |
|--------|-------------------------------------|--------|
| `400` | `validation-error` | Body con campos inválidos, incluida una `quantity` por encima del máximo (añade `violations`) |
| `400` | `malformed-request` | JSON mal formado o tipos/valores ilegibles |
| `400` | `invalid-event` | Regla de negocio del evento (p. ej. fecha pasada) |
| `400` | `invalid-idempotency-key` | `Idempotency-Key` ausente o inválida |
| `400` | `bad-request` | Otro error 400 del framework |
| `401` | `admin-unauthorized` | `X-Admin-Key` ausente o incorrecta (con clave configurada); lleva `WWW-Authenticate: ApiKey` |
| `403` | `admin-disabled` | El servidor no tiene `ADMIN_API_KEY`: las rutas admin están cerradas |
| `404` | `event-not-found`, `order-not-found` | El evento o la orden no existe |
| `404` | `not-found` | Ruta inexistente, o un id de ruta con formato imposible: texto fijo, **nunca** repite lo recibido |
| `405` | `method-not-allowed` | Método HTTP no soportado; conserva la cabecera `Allow` |
| `406` | `not-acceptable` | `Accept` no satisfacible |
| `409` | `event-already-exists` | Evento duplicado |
| `409` | `insufficient-inventory` | No hay entradas suficientes |
| `409` | `idempotency-key-reused` | Misma clave con otro payload |
| `409` | `idempotent-order-not-active` | La orden de esa clave ya fue liberada: usar clave nueva |
| `409` | `concurrent-modification` | Se perdió una carrera de bloqueo optimista del inventario; **no se aplicó** nada. Lleva `Retry-After: 1` y es seguro reintentar |
| `409` | `invalid-state-transition`, `order-status-conflict`, `order-already-exists` | Conflictos de estado de la orden |
| `410` | `reservation-expired` | La reserva expiró y ya no se puede confirmar |
| `413` | `payload-too-large` | Body por encima de 32 KB |
| `415` | `unsupported-media-type` | `Content-Type` no soportado |
| `4xx` | `client-error` | Cualquier otro error 4xx del framework (texto fijo) |
| `429` | `rate-limit-exceeded` | Demasiadas escrituras del cliente, o demasiados intentos **fallidos** de `X-Admin-Key`; lleva `Retry-After` (segundos) |
| `500` | `internal-error` | Cualquier error no previsto (texto fijo) |
| `503` | `order-enqueue-failed` | No se pudo encolar la orden: reintentar con una `Idempotency-Key` **nueva** |
| `503` | `service-unavailable` | Una dependencia (DynamoDB, SQS) limita, expira o no responde tras los reintentos del adaptador. Lleva `Retry-After: 5` |

Por qué `409` y no `503` para la contención de inventario: la petición chocó con un cambio concurrente y no se aplicó; `503` se reserva para «el servicio no puede aceptar trabajo». La capa web no reintenta nada (no puede saber si una petición es repetible): los reintentos con `Retry.backoff` viven en los adaptadores y solo para errores transitorios; al cliente se le indica cuándo reintentar con `Retry-After`.

**Errores que Netty rechaza antes de llegar a la aplicación** (URL mal formada como `/%zz`, caracteres ilegales en cabeceras, línea de petición inválida) devuelven un `400` **vacío, sin `X-Correlation-Id` ni cabeceras de seguridad**: ningún filtro de la aplicación llega a ejecutarse. Es esperado; si hace falta uniformarlo, se hace en el borde.

## Flujo de compra paso a paso

Diagrama de secuencia completo (con compensación y replay) en [`docs/architecture.md`](docs/architecture.md#2-flujo-de-compra).

1. **`POST /orders`** con `Idempotency-Key`. El servidor valida cabecera y body y deriva el `orderId` de la clave (SHA-256).
2. **Reserva atómica**: una sola transacción de DynamoDB mueve `available → reserved` en el inventario (condicionada a `available >= quantity`), crea la orden `RESERVED` con `attribute_not_exists(orderId)` y escribe la primera entrada de auditoría. Si ya existe la orden de esa clave es un replay (ver reglas de `Idempotency-Key`); si no hay entradas, `409 insufficient-inventory`.
3. **Publicación** del mensaje `{"version":1,"orderId":"..."}` en SQS. Si falla tras los reintentos, otra transacción libera la reserva y el cliente recibe `503 order-enqueue-failed`.
4. **`202 Accepted`** con `Location: /orders/{orderId}` y `reservationExpiresAt` (10 minutos).
5. **Procesamiento asíncrono**: el consumidor recibe el mensaje y ejecuta `ProcessOrderUseCase`: `RESERVED → PENDING_CONFIRMATION → SOLD`, cada paso una transacción idempotente (estado de la orden + auditoría + contador de inventario). Solo tras terminar con éxito se borra el mensaje; si falla, SQS lo reentrega y tras 3 recepciones va a la DLQ.
6. **Consulta del estado**: el cliente hace *polling* de `GET /orders/{orderId}` hasta ver `SOLD` (normalmente en menos de un segundo). Si la reserva expira antes de confirmarse, el consumidor o el job de expiración la liberan: la orden queda `AVAILABLE` y las entradas vuelven al inventario.

```bash
curl -s -X POST localhost:8080/orders -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d "{\"eventId\":\"$EVENT_ID\",\"quantity\":3}"                  # 202 {"orderId":"…","status":"RESERVED",…}
until curl -s localhost:8080/orders/$ORDER_ID | grep -q '"status":"SOLD"'; do sleep 1; done   # polling
```

## Colección de peticiones y demo

La carpeta [`requests/`](requests/) contiene:

| Fichero | Para qué |
|---------|----------|
| `ticketflow.postman_collection.json` | Colección de Postman v2.1 (31 peticiones, 63 aserciones): crear/consultar/listar eventos, disponibilidad (instantánea y stream), compra, *polling* hasta `SOLD`, replay, clave reutilizada (`409`), inventario insuficiente (`409`), errores de validación (`400`) e ids inexistentes (`404`), cortesías con y sin clave de admin, sondas y Prometheus en el puerto de gestión |
| `ticketflow.local.postman_environment.json` | Entorno de Postman: `baseUrl`, `managementUrl`, `adminKey` (rellénela con su `ADMIN_API_KEY`; es de tipo `secret`, no la suba a git) |
| `run-newman.sh` | Ejecuta la colección con Newman en Docker contra la pila de compose |
| `demo.sh` | Flujo principal de extremo a extremo con `curl`, con salida legible |

**Postman**: importe la colección y el entorno, elija el entorno «ticketflow local», rellene `adminKey` y ejecute la colección (Runner) en orden. Las variables `eventId`, `orderId` e `idempotencyKey` las rellenan los scripts (la `Idempotency-Key` se genera en cada compra: `pm-` + GUID, 39 caracteres). La petición del *stream* SSE solo funciona en la app (no termina nunca), por eso Newman la omite.

**Newman** (`./requests/run-newman.sh`, imagen `postman/newman:6.1.3-alpine`): el contenedor de Newman se conecta a la **red de compose** y llega a la app como `http://app:8080` y `http://app:8081`, porque los puertos publicados escuchan solo en `127.0.0.1` del anfitrión y `localhost` dentro de un contenedor es el propio contenedor (en Colima y Docker Desktop no alcanza el anfitrión). Funciona igual en Linux. Pasa `ADMIN_API_KEY` desde el entorno (la misma con la que arrancó la pila) y añade una pausa de 400 ms entre peticiones (`NEWMAN_DELAY_MS`) para respetar el rate limit de escrituras (20 de ráfaga, 1/s) sin debilitarlo; una ejecución completa hace unas 15 escrituras. Argumentos extra se pasan a Newman (`./requests/run-newman.sh --reporters cli,json`).

**`demo.sh`**: `ADMIN_API_KEY=… ./requests/demo.sh` (usa `BASE_URL`/`MANAGEMENT_URL` si no son los de por defecto). Espera a la readiness, crea un evento, compra 3 entradas, sigue la orden hasta `SOLD`, repite la compra con la misma clave, provoca un `409` y un `400`, emite cortesías (si hay `ADMIN_API_KEY`), muestra el stream SSE 3 segundos y las métricas de negocio. Termina con código distinto de 0 si algo no es lo esperado. Usa `jq` para formatear el JSON si está instalado.

## Pruebas y cobertura

`./init.sh` valida `feature_list.json` y ejecuta `./gradlew clean build jacocoTestReport jacocoTestCoverageVerification`: compila, pasa todas las pruebas y **falla si la cobertura de líneas global baja del 90 %** (se excluye solo `*Application`).

| Nivel | Herramientas | Qué cubre |
|-------|--------------|-----------|
| Unitario de dominio | JUnit 5 | Matriz 5x5 de transiciones de estado, value objects, invariantes; reglas de arquitectura con ArchUnit |
| Unitario de casos de uso | JUnit 5 + Mockito + `StepVerifier` | Lógica con repositorios simulados |
| Web | `WebTestClient` | Contratos HTTP, errores, filtros, rate limit, cabeceras |
| Integración (`@Tag("integration")`) | Testcontainers: DynamoDB Local y LocalStack reales | Adaptadores DynamoDB y SQS, casos de uso extremo a extremo |
| Concurrencia (`com.ticketflow.concurrency`) | Contexto completo con HTTP real, cientos de peticiones en vuelo | Alta contención (300 compras sobre capacidad 100), replays, mensajes duplicados y venenosos, fallos inyectados, expiración bajo carga y clientes que cancelan; cada escenario termina con una reconciliación entre `inventory`, `orders` y `order_audit` |

```bash
./init.sh                              # sin integración (excluida por etiqueta): no necesita Docker para los tests
INCLUDE_INTEGRATION=true ./init.sh     # con integración y concurrencia (siempre en CI)

# Con Colima, antes de las pruebas de integración:
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

**Cifras actuales** (`INCLUDE_INTEGRATION=true ./init.sh` sobre este commit): **929 pruebas, 0 fallos**; cobertura de líneas **99,54 %** (2.142 de 2.152; ramas 95,89 %), con el mínimo exigido en 90 %. Sin integración (`./init.sh`, sin Docker): **768 pruebas, 0 fallos**, cobertura de líneas **99,40 %** (2.139 de 2.152).

El informe de cobertura HTML queda en `build/reports/jacoco/test/html/index.html` (y el XML en `build/reports/jacoco/test/jacocoTestReport.xml`); el de pruebas, en `build/reports/tests/test/index.html`. En CI se suben como artefacto `reports`. Detalle de la suite de concurrencia, la reconciliación y cómo ejecutarla sola: [`docs/verification.md`](docs/verification.md).

## Observabilidad

Métricas de negocio y de infraestructura (Micrometer, prefijo `ticketflow.`, etiquetas solo de enums fijos), logs JSON con `correlationId` en cada línea y sondas de *liveness*/*readiness*. Resumen:

- **Actuator en su propio puerto** (`8081`, solo `127.0.0.1` fuera del contenedor): expone únicamente `health`, `info` y `prometheus`. El puerto público no sirve `/actuator/**`.
- **Liveness** nunca depende de sistemas externos (el `HEALTHCHECK` de la imagen lo usa); **readiness** incluye DynamoDB y SQS con timeout corto y caché breve.
- **Logs**: `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` (la imagen y compose) → JSON ECS. Una compra se sigue API → cola → consumer filtrando por `correlationId`: `docker-compose logs --no-log-prefix app | grep <correlationId>`.
- Métricas útiles: `curl -s localhost:8081/actuator/prometheus | grep '^ticketflow_'` (órdenes colocadas/vendidas/liberadas, replays, rechazos, conflictos, profundidad de la cola y de la DLQ, barridos de expiración, rate limit).

Catálogo completo de métricas, alertas PromQL sugeridas y cómo seguir una compra en los logs: [`docs/observability.md`](docs/observability.md).

## Seguridad

Sin autenticación de usuarios: la única credencial es la clave de administración. Controles implementados: `Idempotency-Key` obligatoria y derivación SHA-256 del `orderId`, rate limit por cliente con memoria acotada y protección de fuerza bruta de `X-Admin-Key` (comparación en tiempo constante), límites de entrada (32 KB, 10 entradas por orden), cabeceras de seguridad en todas las respuestas, errores sin fugas de información, secretos enmascarados, contenedores endurecidos (usuario no root, `read_only`, `cap_drop: ALL`, puertos solo en loopback), dependencias bloqueadas (`gradle.lockfile`) y escaneos de CVE y secretos en CI.

Modelo de amenazas, escaneos, endurecimiento de contenedores y limitaciones conocidas: [`docs/security.md`](docs/security.md). Reportar una vulnerabilidad: [`SECURITY.md`](SECURITY.md).

## CI/CD

| Workflow | Cuándo | Qué hace |
|----------|--------|----------|
| `.github/workflows/ci.yml` (job `verify`) | `pull_request` y push a `main` | Java 25 (Temurin) con `INCLUDE_INTEGRATION=true ./init.sh` (build, tests con Testcontainers y barrera del 90 %); sube `build/reports/jacoco`, `build/reports/tests` y `build/test-results` como artefacto `reports` |
| `.github/workflows/security.yml` | `pull_request`, push a `main` y semanal (lunes 05:17 UTC) | `dependencies`: Trivy sobre `gradle.lockfile`; `secrets`: gitleaks sobre todo el historial y el árbol; `image`: construye la imagen y la escanea con Trivy. Falla con HIGH/CRITICAL con corrección disponible. Independiente de `verify` |
| `.github/workflows/release.yml` | push de un tag `v*` | Construye la imagen y la publica en `ghcr.io/alhucave/ticketflow` (tags semver `x.y.z`, `x.y` y `latest`) con `GITHUB_TOKEN` (`packages: write` solo en ese job) |

- Los workflows usan permisos mínimos (`contents: read`) y acciones fijadas por SHA de commit.
- **Protección de la rama `main`**: se configuró con el check `verify` obligatorio, la rama al día antes de fusionar y sin force-push ni borrado, cuando el repositorio era público. **Desde que el repositorio es privado en un plan GitHub Free esa protección ya no se puede aplicar** (GitHub exige el plan Pro/Team o un repositorio público): `verify` y los escaneos se siguen ejecutando en cada PR, pero **no bloquean la fusión**. «Todo cambio por PR (`Closes #<issue>`) con CI en verde» es hoy una **convención**, no una regla impuesta. Para recuperarla: plan Pro/Team o volver a hacer público el repositorio, y reactivar la regla (check `verify` obligatorio, rama al día, sin force-push ni borrado).
- **Dependabot** (`.github/dependabot.yml`): actualizaciones semanales agrupadas de Gradle (con su lockfile), GitHub Actions e imagen base del `Dockerfile` (fijada también por digest); cada PR pasa `verify` y los escaneos.
- Detalle de los escaneos y cómo ejecutarlos en local: [`docs/security.md`](docs/security.md#cadena-de-suministro).

## Despliegue en AWS (diseño)

**Nada está desplegado en AWS**: ticketflow solo se ejecuta en local (DynamoDB Local y LocalStack). [`docs/aws.md`](docs/aws.md) es **diseño y estimación**, sin cuenta ni recursos reales: toda cifra de capacidad o coste es una estimación y nada se ha probado con carga en AWS. Contiene la arquitectura objetivo (ECS Fargate con un servicio `api` y otro `worker`, DynamoDB, SQS con DLQ, ALB con WAF, endpoints de VPC; diagramas Mermaid), los límites de escalabilidad (el inventario de un evento es un único ítem de DynamoDB) y su camino de evolución, seguridad en la nube (IAM de mínimo privilegio, secretos, OIDC), observabilidad (alarmas con umbrales), gobierno, una estimación de costes con precios reales de la lista pública de AWS, y la lista de lo que falta para producción.

- [Alcance y estado](docs/aws.md#1-alcance-y-estado) · [Arquitectura](docs/aws.md#2-arquitectura-objetivo) · [Cómputo](docs/aws.md#3-cómputo-ecs-fargate) · [Datos y límites de escalabilidad](docs/aws.md#42-límites-de-escalabilidad-y-camino-de-evolución)
- [Seguridad](docs/aws.md#5-seguridad-en-la-nube) · [Observabilidad y alarmas](docs/aws.md#6-observabilidad-en-aws) · [Costes](docs/aws.md#8-costes) · [Qué falta para producción](docs/aws.md#92-qué-falta-para-producción)

## Decisiones de diseño

Detalle en [`docs/architecture.md`](docs/architecture.md#decisiones-clave).

- **Contadores en DynamoDB + escrituras condicionadas** en vez de una fila por asiento o locks distribuidos: `available >= :qty` en la propia escritura y `version + 1`; sin lectura-modificación-escritura, sin coordinación, escala horizontalmente. Invariante: `available + reserved + pendingConfirmation + sold + complimentary = capacity`.
- **`OrderId` determinista derivado del `Idempotency-Key`** (UUID de nombre con SHA-256, espacio de nombres propio para compras y cortesías): la unicidad de la clave primaria garantiza «una orden por clave» sin consulta previa y sin una tabla de claves.
- **`TransactWriteItems` atómico** para reservar, confirmar y liberar: inventario + orden + auditoría cambian juntos o no cambian; no existe un estado intermedio con entradas retenidas sin orden.
- **SQS at-least-once + consumidor idempotente + DLQ**: el mensaje solo se borra tras terminar con éxito; los duplicados son inocuos porque cada paso está condicionado al estado esperado; los mensajes que fallan 3 veces (o venenosos) van a la DLQ en lugar de bloquear la cola.
- **Job de expiración y TTL de 10 minutos**: una reserva que no se confirma vuelve al inventario. La consulta del índice es eventualmente consistente y solo propone candidatas; decide la escritura condicionada, así que consumer y barridos pueden competir sin coordinación (gana exactamente uno).
- **Sin estados `EXPIRED`/`FAILED`**: una reserva liberada vuelve a `AVAILABLE` y el motivo queda en la auditoría; la máquina de estados tiene solo cinco estados y dos finales (`SOLD`, `COMPLIMENTARY`).
- **WebFlux (reactivo de extremo a extremo)**: la compra responde `202` sin bloquear hilos mientras espera a DynamoDB o SQS; el cliente AWS es asíncrono y no hay `.block()` ni `Thread.sleep` en producción.
- **Java 25**: `record` para DTOs, mensajes y value objects; `switch` con *pattern matching* sobre estados y excepciones; `sealed interface` para los resultados del procesamiento. No se usan virtual threads: el camino es reactivo.
- **Puerto de métricas** (`BusinessMetrics`, `OperationalMetrics`): interfaces sin framework con implementación `NOOP`; `domain` y `usecase` no dependen de Micrometer y las etiquetas solo admiten enums fijos (baja cardinalidad).
- **Puerto de gestión separado (`8081`)**: Actuator fuera de la API pública y en loopback; el endpoint de Prometheus no tiene autenticación y no debe exponerse.
- **Reserva sin mensaje recuperable**: el controlador desacopla «reservar + publicar + compensar» de la suscripción de la petición; un replay de una orden aún `RESERVED` republica su mensaje y, si nadie reintenta, la expiración la libera.

## Solución de problemas

| Síntoma | Causa y solución |
|---------|------------------|
| `Cannot connect to the Docker daemon` o Testcontainers no encuentra Docker (Colima) | El socket no está en `/var/run/docker.sock`. Exporte `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` y `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` y verifique con `docker ps`. En el CI de GitHub no hace falta |
| `docker: unknown command: docker compose` | Su instalación tiene el binario independiente: use `docker-compose` (los comandos de este documento ya lo hacen) |
| `port is already allocated` / `address already in use` | Otro proceso usa 8080, 8081, 8000 o 4566. Cambie `APP_PORT`, `MANAGEMENT_PORT`, `DYNAMODB_PORT` o `LOCALSTACK_PORT` en `.env` (o en el entorno) y relance. Si el conflicto es una pila anterior: `docker-compose down -v` |
| LocalStack no arranca y pide `LOCALSTACK_AUTH_TOKEN` | Las versiones `2026.x` de LocalStack exigen un token; la pila fija `localstack/localstack:4.14.0`, la última que funciona sin él. No suba el tag de la imagen sin un token |
| `POST /events/{id}/complimentary` responde `403 admin-disabled` | `ADMIN_API_KEY` no estaba definida al arrancar. Expórtela (o póngala en `.env`) y recree la app: `docker-compose up -d`. Con la clave definida, una ausente o incorrecta da `401` |
| `429 rate-limit-exceeded` al ejecutar muchas escrituras seguidas | Es el límite por cliente (20 de ráfaga, 1/s). Espere `Retry-After` segundos o espacie las peticiones (`NEWMAN_DELAY_MS` en `run-newman.sh`) |
| La primera compra tras recrear solo la app tarda hasta ~30 s en pasar a `SOLD` | Observado: un long poll del consumidor anterior puede quedar vivo en LocalStack y recibir el mensaje; reaparece al vencer el `visibility-timeout` (30 s). Es el comportamiento at-least-once, no se pierde nada; ocurre en local al reiniciar la app sin reiniciar LocalStack (no siempre). `demo.sh` espera hasta 60 s y la colección de Newman hasta 100 reintentos de *polling*, así que lo toleran |
| `readiness` responde `503` justo tras arrancar | Normal durante unos segundos: la app crea las tablas de DynamoDB. `docker-compose up --wait` o `demo.sh` esperan a que esté lista |
| Los datos desaparecen al reiniciar | DynamoDB Local corre en memoria (`-inMemory`) y LocalStack no persiste: es deliberado para desarrollo |
| Newman no llega a la app | Ejecútelo con `./requests/run-newman.sh` (usa la red de compose). `localhost` dentro de un contenedor no es el anfitrión |

## Limitaciones conocidas

- **Nada desplegado en AWS**: ticketflow se ejecuta solo en local (DynamoDB Local y LocalStack). El diseño del despliegue en AWS (solo documentación) está en [`docs/aws.md`](docs/aws.md#1-alcance-y-estado).
- **Sin autenticación de usuarios**: la `Idempotency-Key` no está ligada a un principal; las cortesías se auditan con un actor fijo; la clave de administración es un secreto compartido.
- **El «pago» no existe**: la confirmación de la venta es automática (el consumidor lleva la orden de `RESERVED` a `SOLD`); `PENDING_CONFIRMATION` es el punto donde se integraría un cobro.
- **Rate limiter por instancia** y basado en la dirección del socket (con IPv6 un cliente puede rotar direcciones): la protección real contra abuso necesita una capa de borde (ver el [diseño en AWS](docs/aws.md#54-borde-waf-rate-limit-y-ddos)).
- `GET /events` recorre toda la tabla `events` (*scan*) y devuelve todos los eventos sin paginar; el stream SSE consulta DynamoDB una vez por intervalo y por cliente conectado.
- No hay endpoints para modificar o cancelar eventos ni órdenes; una reserva solo se libera por expiración o fallo de publicación.
- DynamoDB Local y LocalStack son imágenes de desarrollo sin escaneo de CVE en CI; LocalStack queda en `4.14.0`.
- El endpoint de Prometheus no tiene autenticación (la protección es de red) y los rechazos a nivel Netty (URL malformada) devuelven un `400` vacío sin `X-Correlation-Id`.

La lista completa y su justificación está en [`docs/security.md`](docs/security.md#limitaciones-conocidas).
