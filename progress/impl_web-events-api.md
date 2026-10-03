# Informe de implementación: F-018 web-events-api

Rama: `feature/F-018-web-events-api` (desde main 19a5931). Estado en feature_list.json: `in_progress` (no marcada `done`).

## Archivos

Nuevos (main, `infrastructure.web`): `EventController`, `CreateEventRequest`, `EventResponse`, `EventDetailsResponse`, `InventoryResponse`, `EventMapper` (package-private, estático); (`infrastructure.web.error`): `ApiExceptionHandler`, `Violation`.
Nuevos (test): `infrastructure/web/EventControllerTest` (12 tests, slice con casos de uso simulados), `infrastructure/web/EventsApiEndToEndIT` (6 tests, C12).
Modificados: `build.gradle.kts` (+`spring-boot-starter-validation`), `README.md` (sección Endpoints), `feature_list.json` (F-018 `in_progress`), `progress/current.md`.

## Decisiones

- Starter de validación: verificado en Maven Central que `org.springframework.boot:spring-boot-starter-validation:4.1.1` existe (depende de `spring-boot-validation`).
- `GET /events` devuelve solo eventos (sin inventario) para evitar una lectura extra por evento; documentado en README y Javadoc. `GET /events/{id}` incluye el inventario; la `version` (token de bloqueo optimista) no se expone.
- `capacity` es `Integer` con `@NotNull @Min(1) @Max(1_000_000)` para que un campo ausente sea una violación y no un 0 silencioso. Las reglas de negocio (fecha futura) siguen en `CreateEventUseCase`; `InvalidEventException` -> 400.
- `ApiExceptionHandler`: un único helper `problem(status, typeSuffix, title, detail)` construye todas las respuestas (type `urn:ticketflow:problem:<x>`), de modo que F-020 añada 409/410/429/500 y correlation id en un solo punto. Maneja `WebExchangeBindException` (400 + `violations` ordenadas), `ServerWebInputException` (JSON mal formado / instante inválido, 400 sin detalles internos), `InvalidEventException` 400, `EventNotFoundException` 404, `EventAlreadyExistsException` 409. No cubre excepciones de órdenes.
- Los IDs los genera el servidor, por lo que dos POST idénticos crean dos eventos distintos (probado en el IT); el 409 se prueba en el slice (el adaptador real ya prueba el conflicto en `DynamoDbEventRepositoryIT`).
- Location relativo `/events/{id}`.
- IT: `@SpringBootTest(RANDOM_PORT)` con contexto completo y DynamoDB Local 3.3.1 real vía `@DynamicPropertySource`; provisioning activo (corre de forma asíncrona en ApplicationReady, el test espera con Awaitility a que existan las tablas), consumer y scheduler desactivados, el cliente SQS apunta a un endpoint ficticio (la cola se resuelve de forma perezosa, no hace falta SQS vivo). Es el primer test que arranca la app completa y arrancó sin cambios en la configuración existente.
- Sin `.block()`, sin Lombok, sin `@Autowired` en campos de producción.

## ./init.sh (sin Docker)

`BUILD SUCCESSFUL in 20s` ... `==> init.sh OK` (rc=0; ITs excluidos, gate JaCoCo 90% en verde).

## INCLUDE_INTEGRATION=true ./init.sh

(con `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`)
`BUILD SUCCESSFUL in 1m 33s` ... `==> init.sh OK` (rc=0). `EventsApiEndToEndIT`: 6 tests, 0 fallos.

## docker-compose up --build -d (real, standalone) y curl

Stack arrancó healthy (dynamodb, localstack, app). Salidas (cabeceras reducidas por claridad; `curl -si`):

```
$ POST /events  {"name":"Rock Night","startsAt":"2026-11-02T20:00:00Z","venue":"Arena","capacity":120}
HTTP/1.1 201 Created
Location: /events/e8e867f4-5fc8-44e0-8c48-4bcb47f63a40
Content-Type: application/json
{"id":"e8e867f4-5fc8-44e0-8c48-4bcb47f63a40","name":"Rock Night","startsAt":"2026-11-02T20:00:00Z","venue":"Arena","capacity":120}

$ GET /events/e8e867f4-5fc8-44e0-8c48-4bcb47f63a40
HTTP/1.1 200 OK
{"id":"e8e867f4-...","name":"Rock Night","startsAt":"2026-11-02T20:00:00Z","venue":"Arena","capacity":120,"inventory":{"available":120,"reserved":0,"pendingConfirmation":0,"sold":0,"complimentary":0}}

$ GET /events
HTTP/1.1 200 OK
[{"id":"e8e867f4-...","name":"Rock Night","startsAt":"2026-11-02T20:00:00Z","venue":"Arena","capacity":120}]

$ POST invalid body {"name":"","venue":"A","capacity":0}
HTTP/1.1 400 Bad Request   Content-Type: application/problem+json
{"detail":"The request body has invalid fields","instance":"/events","status":400,"title":"Validation failed","type":"urn:ticketflow:problem:validation-error","violations":[{"field":"capacity","message":"must be greater than or equal to 1"},{"field":"name","message":"must not be blank"},{"field":"startsAt","message":"must not be null"}]}

$ POST malformed json {oops
HTTP/1.1 400 Bad Request
{"detail":"The request could not be read: check the JSON syntax and field formats","instance":"/events","status":400,"title":"Malformed request","type":"urn:ticketflow:problem:malformed-request"}

$ POST past date (2001-01-01)
HTTP/1.1 400 Bad Request
{"detail":"Event date must be in the future","instance":"/events","status":400,"title":"Invalid event","type":"urn:ticketflow:problem:invalid-event"}

$ GET /events/nope
HTTP/1.1 404 Not Found
{"detail":"Event not found: nope","instance":"/events/nope","status":404,"title":"Event not found","type":"urn:ticketflow:problem:event-not-found"}
```

`docker-compose down -v` ejecutado; no quedan contenedores.

## Notas para el reviewer

- Docs: `docs/architecture.md` ya describe `infrastructure.web`; no requirió cambios.
- Pendiente para F-020: handlers 409 (orden), 410, 429, 500 y correlation id.
