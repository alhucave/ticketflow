# Informe de implementación — F-021 complimentary-issuance

Rama: `feature/F-021-complimentary-issuance` (commit local, sin push). Estado en `feature_list.json`: `in_progress` (no marcado `done`).

## Archivos

Nuevos (main): `usecase/IssueComplimentary{UseCase,Command,Result}`, `infrastructure/web/{ComplimentaryController,ComplimentaryRequest,ComplimentaryResponse,AdminKeyGuard,AdminKeyWebFilter}`, `infrastructure/web/error/AdminAccessDeniedException`.
Modificados (main): `OrderId` (`complimentaryFromIdempotencyKey`), `Order` (`complimentary(...)`, invariante), `OrderPlacementRepository` + `DynamoDbOrderPlacementRepository` (`issueComplimentary`, helper `putOrder`), `UseCaseConfig`, `ApiExceptionHandler` (401/403 admin), `application.yml` (`ticketflow.admin.api-key: ${ADMIN_API_KEY:}`), `docker-compose.yml` (`ADMIN_API_KEY: ${ADMIN_API_KEY:-}`, sin secreto), `.env.example`, `README.md` (Endpoints + sección Cortesías), `docs/architecture.md`.
Tests: `IssueComplimentaryUseCaseTest`, `ComplimentaryControllerTest` (WebFluxTest con filtro y handlers reales), `ComplimentaryDisabledWebTest`, `AdminKeyGuardTest`, ampliaciones en `OrderTest`, `ValueObjectsTest`, `UseCaseConfigTest`, `DynamoDbOrderPlacementRepositoryTest`; integración: `IssueComplimentaryUseCaseIT` (12, DynamoDB Local), `ComplimentaryApiEndToEndIT` (6, @SpringBootTest con DynamoDB Local + LocalStack SQS y consumer activo).
`.env` está en `.gitignore` (verificado, no hay `.env` versionado).

## Decisiones

- Un solo `TransactWriteItems` con el mismo layout que `placeReservation` ([0] inventario `available -> complimentary` con `available >= qty` y `version+1` reutilizando `moveInventory`, [1] Put de la orden `attribute_not_exists`, [2] auditoría con actor `complimentary-issuance` y `reason`); mapeo de cancelaciones reutiliza `translatePlacement` (orden existente prevalece -> replay; inventario -> `InsufficientInventory`/`EventNotFound`).
- `orderId` con namespace `ticketflow:complimentary:` (test: misma clave que una compra no colisiona).
- `reservationExpiresAt == createdAt` en la orden COMPLIMENTARY (campo no nullable). Para ello se relajó el invariante de `Order`: para `COMPLIMENTARY` basta `expiresAt >= createdAt` (los demás estados siguen exigiendo `> createdAt`). Se eligió `>=` y no `==` porque tests existentes (`GetOrderStatusUseCaseTest`, `ProcessOrderUseCaseTest`) construyen órdenes COMPLIMENTARY con expiración futura. `transitionTo(COMPLIMENTARY)` fija expiry = createdAt. La respuesta web ya ocultaba `reservationExpiresAt` para COMPLIMENTARY.
- Replay: el `reason` forma parte del payload; como la orden no lo guarda, se compara con la entrada de auditoría `-> COMPLIMENTARY` (`findAuditTrail`). Payload distinto (evento, cantidad o reason) -> `IdempotencyKeyReusedException`; orden existente que no sea COMPLIMENTARY -> también reused (defensivo).
- Replay devuelve `201` (mismo body y `Location`), coherente con que `POST /orders` repite `202`.
- Seguridad: `AdminKeyWebFilter` (precedencia HIGHEST+20, tras el correlation id) limitado a `ADMIN_ROUTES` (`/events/{id}/complimentary`, cualquier método) y `AdminKeyGuard` (SHA-256 de ambos valores + `MessageDigest.isEqual`; siempre calcula el hash aunque falte la cabecera). Sin clave (null/blank) -> 403 `admin-disabled`; ausente/incorrecta -> 401 `admin-unauthorized` + `WWW-Authenticate: ApiKey`, texto fijo. El filtro corre antes que validación y búsqueda del evento (sin fuga sobre eventos inexistentes). La excepción la renderiza `ProblemWebExceptionHandler` con la forma habitual. No se registra la clave ni el valor recibido.
- Límites: quantity 1..1000 y `reason` <= 200 sin caracteres de control (validación web) y también en el `Command`; el reason nunca se devuelve.
- Nada sale de COMPLIMENTARY: la matriz (`TicketStatus`, ya existente) y `releaseReservation` lo rechazan; test de integración recorre todos los `expected`. El GSI de expiración solo se consulta por RESERVED/PENDING_CONFIRMATION; `ProcessOrderUseCase` devuelve `AlreadyProcessed(COMPLIMENTARY)` (IT con barrido y proceso con reloj a +1 año).

## Fallos / limitaciones detectadas (sin desviar del diseño)

1. El invariante `Order.reservationExpiresAt > createdAt` impedía representar la orden sin ventana de reserva; resuelto como se describe (relajación acotada a COMPLIMENTARY).
2. Aceptar el mismo `reason` exige leer la auditoría en el replay (una lectura extra solo en ruta de replay).
3. La clave admin es un secreto compartido único, sin rotación ni identidad por usuario: el `actor` de la auditoría es el fijo `complimentary-issuance` (no hay autenticación de usuarios en el proyecto). Una feature posterior debería aportar identidad real.
4. `401` vs `403` distinguen "deshabilitado" de "clave incorrecta", lo que revela que el servidor no tiene clave configurada; se aceptó por el requisito del diseño (secure by default observable).
5. Sin rate limiting en la ruta admin (fuerza bruta de la clave): pendiente de la fase de rate limit.

## Verificación

`./init.sh` (sin Docker): `BUILD SUCCESSFUL in 22s ... ==> init.sh OK` (compuerta JaCoCo 90% superada).
`INCLUDE_INTEGRATION=true ./init.sh` (Colima: `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`): `BUILD SUCCESSFUL in 2m 7s ... ==> init.sh OK`.

### docker-compose real (`docker-compose up --build -d`; `ADMIN_API_KEY=dev-only-change-me` solo en el entorno del shell; valor redactado como `<KEY>`)

Servidor sin clave configurada (`ADMIN_API_KEY=` vacío), petición con `X-Admin-Key: <KEY>`:
```
HTTP/1.1 403 Forbidden
Content-Type: application/problem+json
{"detail":"Admin operations are not available",...,"status":403,"title":"Admin access disabled","type":"urn:ticketflow:problem:admin-disabled","correlationId":"4d4a3c57-..."}
```
Servidor con clave (reiniciado con `down -v` + `up`), evento capacity 10:
```
--- sin cabecera X-Admin-Key
HTTP/1.1 401 Unauthorized  (WWW-Authenticate: ApiKey)
{"detail":"Valid admin credentials are required",...,"status":401,"title":"Unauthorized","type":"urn:ticketflow:problem:admin-unauthorized",...}
--- X-Admin-Key: nope        -> 401 idéntico
--- X-Admin-Key: <KEY>, {"quantity":2,"reason":"VIP guests"}
HTTP/1.1 201 Created
Location: /orders/9b98e91c-ea42-5658-829a-8ac051907723
{"orderId":"9b98e91c-ea42-5658-829a-8ac051907723","eventId":"a5e1967b-...","quantity":2,"status":"COMPLIMENTARY"}
--- replay misma clave/payload
HTTP/1.1 201 Created  (mismo Location y body)
--- misma clave, quantity 3
HTTP/1.1 409 Conflict  type urn:ticketflow:problem:idempotency-key-reused
--- quantity 9 (disponible 8)
HTTP/1.1 409 Conflict  type urn:ticketflow:problem:insufficient-inventory
--- GET /events/{id}/availability
{"available":8,"reserved":0,"pendingConfirmation":0,"sold":0,"complimentary":2,"capacity":10}
--- GET /orders/9b98e91c-...
{"orderId":"9b98e91c-...","eventId":"a5e1967b-...","quantity":2,"status":"COMPLIMENTARY","createdAt":"2026-10-03T01:57:07.250802837Z"}
```
Después `docker-compose down -v` (contenedores y red eliminados).
