# F-034 — fix-startup-race-missing-tables: informe del implementer

Rama `feature/F-034-fix-startup-race`, issue #67, DP-038 (Extra propio). Estado en `feature_list.json`: `in_progress` (no marcada `done`).

## Archivos tocados
- `src/main/java/com/ticketflow/infrastructure/web/error/TransientFailures.java`: `ResourceNotFoundException` de DynamoDB cuenta como transitoria (rama antes de `AwsServiceException`).
- `src/main/java/com/ticketflow/infrastructure/web/error/ApiExceptionHandler.java`: `unavailable(...)` registra la excepcion completa (WARN `DynamoDB tables are missing ...`) cuando es `ResourceNotFoundException`; el resto sigue registrando solo la clase. Mismo 503 + `Retry-After: 5` + metrica.
- `src/test/java/com/ticketflow/infrastructure/web/StartupWindowEndToEndIT.java` (nuevo, 12 tests, `@Tag("integration")`).
- `src/test/java/com/ticketflow/infrastructure/web/error/MissingTableMappingTest.java` (nuevo, 4 tests unitarios).
- `README.md` (seccion de arranque con compose, catalogo de errores 503, troubleshooting), `docs/observability.md` (metrica y readiness), `docs/decisions.md` (DP-038 + fila del indice), `progress/current.md`.
- `docs/requirements.md` NO se toca: ninguna fila del enunciado cambia de estado (ET-2 ya cubre "errores con Retry-After"; esto es un extra propio).
- No se cambia ninguna propiedad, ningun adaptador (`DynamoDbOrderRepository.isTransient` intacto: no se reintenta contra una tabla inexistente), ni logica de negocio, ni mapeos/tests existentes.

## Decision y trade-offs
Elegida (a): clasificar `ResourceNotFoundException` como 503 + Retry-After por el mecanismo existente (una rama de clasificacion, vale para TODAS las rutas, sin estado compartido).
- (b) aprovisionar antes de aceptar trafico: exige un SmartLifecycle de fase temprana con plazo propio que no cuelgue ni tumbe el arranque si DynamoDB no responde (F-031) y reintentos del provisionador; mas codigo/riesgo, y no cubre una tabla borrada despues del arranque. Descartada.
- (c) WebFilter 503 hasta que termine el aprovisionamiento: necesita estado compartido y definir "termino" cuando el aprovisionamiento esta desactivado o fallo; puede devolver 503 con la app sana (cache de readiness, rutas de gestion). Descartada.
- Riesgo de (a): puede enmascarar una tabla que de verdad falta. No se oculta: la readiness (`DescribeTable` sobre `orders`) sigue DOWN, `ticketflow.dependency.unavailable` no deja de subir y cada respuesta deja un WARN con la excepcion completa (nombre de tabla y pila). Documentado en DP-038, README y observability.md.
- `ResourceNotFoundException` solo puede significar tabla inexistente: los adaptadores leen un item ausente como resultado vacio (404 de negocio por `EventNotFoundException`/`OrderNotFoundException`), un GSI inexistente da otro error.
- `Retry-After: 5` (constante existente) aunque la ventana real sea ~1-2 s: se reutiliza el valor del resto de 503 para no abrir otra constante.

## Inventario de excepciones en la ventana de arranque
Ejecutado con DynamoDB Local sin tablas (antes de la correccion, todas con 500 `internal-error`; el log del servidor mostro siempre la misma clase):
| Ruta | Excepcion observada | Antes | Despues |
|---|---|---|---|
| POST /events | ResourceNotFoundException | 500 | 503 |
| GET /events | ResourceNotFoundException | 500 | 503 |
| GET /events/{id} | ResourceNotFoundException | 500 | 503 |
| GET /events/{id}/availability | ResourceNotFoundException | 500 | 503 |
| GET /events/{id}/availability/stream (antes de empezar el flujo) | ResourceNotFoundException | 500 | 503 |
| POST /orders | ResourceNotFoundException | 500 | 503 |
| GET /orders/{id} | ResourceNotFoundException | 500 | 503 |
| POST /events/{id}/complimentary | ResourceNotFoundException | 500 | 503 |
| POST /events con solo la tabla `events` creada (aprovisionamiento a medias, la transaccion toca `inventory`) | ResourceNotFoundException (no TransactionCanceledException) | 500 | 503 |
No aparecio ninguna otra excepcion (ni `TransactionCanceledException`) en la ventana. SQS: una cola inexistente ya se contesta `503 order-enqueue-failed` en la publicacion (`QueueDoesNotExistException` no es `ResourceNotFoundException`) y el consumidor reintenta con backoff (DP-037); no se modifica.

## Prueba de regresion (falla hoy, pasa con la correccion)
`StartupWindowEndToEndIT`: contexto completo, DynamoDB Local propio (no el compartido de `E2eContainers`, que otras clases ya llenaron de tablas) sin tablas, `ticketflow.dynamodb.provisioning-enabled=false`, plazos de `TestTimeouts`/propiedad de WebTestClient (DP-035). Tests ordenados (`@Order`): ventana (503 + `Retry-After: 5` + `service-unavailable` + problem+json + sin `non-existent`/nombres de tabla/clases en el cuerpo + metrica +1 por respuesta) para cada ruta; readiness DOWN + WARN con `ResourceNotFoundException`; solo tabla `events`; despues se crean las tablas con `DynamoDbTableProvisioner` y las mismas rutas funcionan (201/200/202), 404 `event-not-found`/`order-not-found` y 409 `insufficient-inventory` sin cambios y sin contar como caida, readiness vuelve a 200.

Antes (main sin la correccion, `./gradlew -PincludeIntegration test --tests '*StartupWindowEndToEndIT'`): `12 tests completed, 10 failed`; cada test de ventana: `java.lang.AssertionError: Status expected:<503 SERVICE_UNAVAILABLE> but was:<500 INTERNAL_SERVER_ERROR>` (los 8 de ruta, `window_keepsReadinessDownAndLogsTheFullCause` y `window_onlyEventsTableCreated...`); pasan `tablesDoNotExistYet` y `afterTablesExist_sameRoutesWorkAndBusinessErrorsAreUnchanged`. `BUILD FAILED`.
Despues: `BUILD SUCCESSFUL`, 12/12 + 4/4 (`MissingTableMappingTest`: ResourceNotFound transitoria y otros 4xx de DynamoDB, p. ej. `ResourceInUseException`, siguen en 500 sin contarse; 503 + Retry-After 5 sin detalle interno; log con la excepcion completa).

## Experimento real: `docker-compose down -v; docker-compose up --build -d` (sin --wait)
Script `poll.py` y override FUERA del repo (scratchpad de la sesion): lanza `docker-compose up --build -d` y, desde el primer instante y en paralelo, sondea `POST /events` (cuerpo valido, `startsAt` a 30 dias) cada ~120 ms junto con la readiness del puerto 8081; imprime una fila por cada cambio de (codigo, readiness, tipo). El rate limiter por defecto (20 de rafaga, 1/s) responderia 429 a ese ritmo, asi que un fichero override fuera del repo fija `TICKETFLOW_RATE_LIMIT_CAPACITY=1000000` y `TICKETFLOW_RATE_LIMIT_REFILL_PER_SECOND=100000` (no se toca `docker-compose.yml`; no hubo ningun 429 en ningun ciclo). No se conserva el script en el repo: es un experimento puntual, ya cubierto de forma determinista por `StartupWindowEndToEndIT`. Imagen reconstruida con `--build` (cache de capas) para cada variante; `docker-compose down -v` al final de cada ciclo.
- ANTES (main sin la correccion): 5 ciclos, 5 con `500 internal-error` (1-2 sondeos), readiness 503 a la vez.
- DESPUES: 10 ciclos normales + 2 con carga de CPU (20 procesos `yes > /dev/null` en un host de 10 nucleos, terminados despues; comprobado 0 restantes): 12 de 12 sin un solo 500; secuencia: errores de conexion (`URLError`, `RemoteDisconnected`) -> `503 service-unavailable` con `Retry-After: 5` (1-2 sondeos, readiness 503) -> `201` (readiness aun 503 ~5 s) -> readiness 200. La ventana dura ~1,4-2 s.

## cycle before-1: 118 polls, status counts {'conn-err': 86, 500: 2, 201: 30}; first201=12.8s readiness200=17.4s
| t (s) | POST /events | readiness | problem type | Retry-After |
|---|---|---|---|---|
| 0.0 | conn-err | conn-err | URLError |  |
| 6.3 | conn-err | conn-err | RemoteDisconnected |  |
| 11.0 | 500 | 503 | internal-error |  |
| 12.8 | 201 | 503 |  |  |
| 17.4 | 201 | 200 |  |  |
RESULT before-1: saw500=True

## cycle before-2: 117 polls, status counts {'conn-err': 85, 500: 1, 201: 31}; first201=12.3s readiness200=17.2s
| t (s) | POST /events | readiness | problem type | Retry-After |
|---|---|---|---|---|
| 0.0 | conn-err | conn-err | URLError |  |
| 6.2 | conn-err | conn-err | RemoteDisconnected |  |
| 10.9 | 500 | 503 | internal-error |  |
| 12.3 | 201 | 503 |  |  |
| 17.2 | 201 | 200 |  |  |
RESULT before-2: saw500=True

## cycle before-3: 118 polls, status counts {'conn-err': 86, 500: 1, 201: 31}; first201=12.3s readiness200=17.1s
| t (s) | POST /events | readiness | problem type | Retry-After |
|---|---|---|---|---|
| 0.0 | conn-err | conn-err | URLError |  |
| 6.2 | conn-err | conn-err | RemoteDisconnected |  |
| 11.0 | 500 | 503 | internal-error |  |
| 12.3 | 201 | 503 |  |  |
| 17.1 | 201 | 200 |  |  |
RESULT before-3: saw500=True

## cycle before-4: 118 polls, status counts {'conn-err': 85, 500: 1, 201: 32}; first201=12.5s readiness200=17.4s
| t (s) | POST /events | readiness | problem type | Retry-After |
|---|---|---|---|---|
| 0.0 | conn-err | conn-err | URLError |  |
| 6.2 | conn-err | conn-err | RemoteDisconnected |  |
| 10.9 | 500 | 503 | internal-error |  |
| 12.5 | 201 | 503 |  |  |
| 17.4 | 201 | 200 |  |  |
RESULT before-4: saw500=True

## cycle before-5: 118 polls, status counts {'conn-err': 85, 500: 1, 201: 32}; first201=12.3s readiness200=17.2s
| t (s) | POST /events | readiness | problem type | Retry-After |
|---|---|---|---|---|
| 0.0 | conn-err | conn-err | URLError |  |
| 6.3 | conn-err | conn-err | RemoteDisconnected |  |
| 10.9 | 500 | 503 | internal-error |  |
| 12.3 | 201 | 503 |  |  |
| 17.2 | 201 | 200 |  |  |
RESULT before-5: saw500=True

## cycle after-1: 121 polls, status counts {'conn-err': 88, 503: 1, 201: 32}; first201=13.3s readiness200=18.3s
| t (s) | POST /events | readiness | problem type | Retry-After |
|---|---|---|---|---|
| 0.0 | conn-err | conn-err | URLError |  |
| 6.4 | conn-err | conn-err | RemoteDisconnected |  |
| 11.2 | 503 | 503 | service-unavailable | 5 |
| 13.3 | 201 | 503 |  |  |
| 18.3 | 201 | 200 |  |  |
RESULT after-1: saw500=False

## cycle after-2: 120 polls, status counts {'conn-err': 87, 503: 1, 201: 32}; first201=12.7s readiness200=17.7s
| t (s) | POST /events | readiness | problem type | Retry-After |
|---|---|---|---|---|
| 0.0 | conn-err | conn-err | URLError |  |
| 6.3 | conn-err | conn-err | RemoteDisconnected |  |
| 11.1 | 503 | 503 | service-unavailable | 5 |
| 12.7 | 201 | 503 |  |  |
| 17.7 | 201 | 200 |  |  |
RESULT after-2: saw500=False

## cycle after-3: 119 polls, status counts {'conn-err': 86, 503: 1, 201: 32}; first201=12.7s readiness200=17.6s
| t (s) | POST /events | readiness | problem type | Retry-After |
|---|---|---|---|---|
| 0.0 | conn-err | conn-err | URLError |  |
| 6.3 | conn-err | conn-err | RemoteDisconnected |  |
| 11.0 | 503 | 503 | service-unavailable | 5 |
| 12.7 | 201 | 503 |  |  |
| 17.6 | 201 | 200 |  |  |
RESULT after-3: saw500=False

## cycle after-4: 121 polls, status counts {'conn-err': 88, 503: 2, 201: 31}; first201=12.9s readiness200=17.6s
| t (s) | POST /events | readiness | problem type | Retry-After |
|---|---|---|---|---|
| 0.0 | conn-err | conn-err | URLError |  |
| 6.3 | conn-err | conn-err | RemoteDisconnected |  |
| 11.3 | 503 | 503 | service-unavailable | 5 |
| 12.9 | 201 | 503 |  |  |
| 17.6 | 201 | 200 |  |  |
RESULT after-4: saw500=False

## cycle after-5: 121 polls, status counts {'conn-err': 89, 503: 1, 201: 31}; first201=13.0s readiness200=17.8s
| t (s) | POST /events | readiness | problem type | Retry-After |
|---|---|---|---|---|
| 0.0 | conn-err | conn-err | URLError |  |
| 6.4 | conn-err | conn-err | RemoteDisconnected |  |
| 11.4 | 503 | 503 | service-unavailable | 5 |
| 13.0 | 201 | 503 |  |  |
| 17.8 | 201 | 200 |  |  |
RESULT after-5: saw500=False

## cycle after-6: 125 polls, status counts {'conn-err': 92, 503: 1, 201: 32}; first201=13.2s readiness200=18.1s
| t (s) | POST /events | readiness | problem type | Retry-After |
|---|---|---|---|---|
| 0.0 | conn-err | conn-err | URLError |  |
| 6.3 | conn-err | conn-err | RemoteDisconnected |  |
| 11.8 | 503 | 503 | service-unavailable | 5 |
| 13.2 | 201 | 503 |  |  |
| 18.1 | 201 | 200 |  |  |
RESULT after-6: saw500=False

## cycle after-7: 120 polls, status counts {'conn-err': 87, 503: 1, 201: 32}; first201=12.6s readiness200=17.5s
| t (s) | POST /events | readiness | problem type | Retry-After |
|---|---|---|---|---|
| 0.0 | conn-err | conn-err | URLError |  |
| 6.3 | conn-err | conn-err | RemoteDisconnected |  |
| 11.1 | 503 | 503 | service-unavailable | 5 |
| 12.6 | 201 | 503 |  |  |
| 17.5 | 201 | 200 |  |  |
RESULT after-7: saw500=False

## cycle after-8: 120 polls, status counts {'conn-err': 87, 503: 1, 201: 32}; first201=12.5s readiness200=17.4s
| t (s) | POST /events | readiness | problem type | Retry-After |
|---|---|---|---|---|
| 0.0 | conn-err | conn-err | URLError |  |
| 6.2 | conn-err | conn-err | RemoteDisconnected |  |
| 11.1 | 503 | 503 | service-unavailable | 5 |
| 12.5 | 201 | 503 |  |  |
| 17.4 | 201 | 200 |  |  |
RESULT after-8: saw500=False

## cycle after-9: 121 polls, status counts {'conn-err': 88, 503: 1, 201: 32}; first201=12.8s readiness200=17.8s
| t (s) | POST /events | readiness | problem type | Retry-After |
|---|---|---|---|---|
| 0.0 | conn-err | conn-err | URLError |  |
| 6.3 | conn-err | conn-err | RemoteDisconnected |  |
| 11.3 | 503 | 503 | service-unavailable | 5 |
| 12.8 | 201 | 503 |  |  |
| 17.8 | 201 | 200 |  |  |
RESULT after-9: saw500=False

## cycle after-10: 119 polls, status counts {'conn-err': 86, 503: 1, 201: 32}; first201=12.5s readiness200=17.4s
| t (s) | POST /events | readiness | problem type | Retry-After |
|---|---|---|---|---|
| 0.0 | conn-err | conn-err | URLError |  |
| 6.3 | conn-err | conn-err | RemoteDisconnected |  |
| 11.0 | 503 | 503 | service-unavailable | 5 |
| 12.5 | 201 | 503 |  |  |
| 17.4 | 201 | 200 |  |  |
RESULT after-10: saw500=False

## cycle after-load-1: 148 polls, status counts {'conn-err': 119, 503: 1, 201: 28}; first201=17.3s readiness200=22.2s
| t (s) | POST /events | readiness | problem type | Retry-After |
|---|---|---|---|---|
| 0.0 | conn-err | conn-err | URLError |  |
| 7.3 | conn-err | conn-err | RemoteDisconnected |  |
| 15.3 | 503 | 503 | service-unavailable | 5 |
| 17.3 | 201 | 503 |  |  |
| 22.2 | 201 | 200 |  |  |
RESULT after-load-1: saw500=False

## cycle after-load-2: 149 polls, status counts {'conn-err': 120, 503: 1, 201: 28}; first201=17.5s readiness200=22.3s
| t (s) | POST /events | readiness | problem type | Retry-After |
|---|---|---|---|---|
| 0.0 | conn-err | conn-err | URLError |  |
| 7.4 | conn-err | conn-err | RemoteDisconnected |  |
| 15.5 | 503 | 503 | service-unavailable | 5 |
| 17.5 | 201 | 503 |  |  |
| 22.3 | 201 | 200 |  |  |
RESULT after-load-2: saw500=False

## init.sh
- `./init.sh` (sin integracion): `BUILD SUCCESSFUL in 1m 13s`, `==> init.sh OK`, 791 tests, 0 fallos (JaCoCo 90 % verificado dentro del build).
- `INCLUDE_INTEGRATION=true ./init.sh` ejecucion 1: `BUILD SUCCESSFUL in 5m 39s`, `==> init.sh OK`, 964 tests, 0 fallos/errores/omitidos (baseline 948 + 16 nuevos).
- Ejecucion 2: `BUILD SUCCESSFUL in 5m 35s`, `==> init.sh OK`, 964 tests, 0 fallos. (Un primer intento de la 2.ª pasada se cortó por el limite de tiempo de la herramienta, sin lanzar nada concurrente; se repitio completa.)
- `./init.sh --check-registry`: OK (32 features, 38 decisiones, 69 filas de requisitos). DP-038 era el siguiente libre.
- Sin contenedores ni procesos de carga al final; ningun Gradle concurrente.
