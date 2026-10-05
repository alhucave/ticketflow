# Review — feature F-034 (fix-startup-race-missing-tables, issue #67)

**Veredicto:** APPROVED

## Verificacion por ejecucion (independiente del informe)
- `./init.sh`: BUILD SUCCESSFUL, `==> init.sh OK` (incluye JaCoCo >= 90 % y `--check-registry`).
- `INCLUDE_INTEGRATION=true ./init.sh` (DOCKER_HOST colima + TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE): BUILD SUCCESSFUL in 5m 34s; sumando los XML de `build/test-results/test`: 964 tests, 0 fallos, 0 errores, 0 omitidos. `StartupWindowEndToEndIT` (12) y `MissingTableMappingTest` (4) presentes.
- Regresion antes/despues en copia de scratch FUERA del repo (git archive HEAD; sin tocar el arbol real):
  - Con `TransientFailures` y `ApiExceptionHandler` de `main`: `12 tests completed, 10 failed`, `Status expected:<503 SERVICE_UNAVAILABLE> but was:<500 INTERNAL_SERVER_ERROR>` (8 rutas + readiness/log + solo-tabla-events). BUILD FAILED.
  - Con los ficheros de la rama: BUILD SUCCESSFUL (12/12).
- Experimento real: `docker-compose down -v; docker-compose up --build -d` (sin --wait) con override de rate limit fuera del repo (`ticketflow.rate-limit.capacity/refill-per-second` via env) y sondeo POST /events cada ~120 ms + readiness 8081, 6 ciclos propios: ningun 500 ni 429 en ninguno. Secuencia constante: conn-err (URLError, RemoteDisconnected) -> `503 service-unavailable` con `Retry-After: 5` (1-3 sondeos, readiness 503) -> `201` (readiness aun 503 ~5 s) -> readiness 200. Ejemplo ciclo 1: t=12.4 503, t=12.6 201, t=17.5 readiness 200. Ciclos 2-6 igual (503 en 12.5-14.3 s). `docker-compose down -v` tras cada ciclo; al final `docker ps -a` vacio, sin procesos poll/docker-compose, `git status` limpio.
- `git diff main --stat`: 10 ficheros: 2 de produccion en `infrastructure/web/error` (TransientFailures +8, ApiExceptionHandler, 18 lineas), 2 tests nuevos, README, docs/decisions.md, docs/observability.md, feature_list.json, progress/current.md, progress/impl. Sin cambios de adaptadores, propiedades, dominio ni logica de negocio.

## Criterios de aceptacion (feature_list.json)
- Test determinista de regresion (contexto completo, DynamoDB Local real sin tablas) que falla hoy con 500 en las rutas y pasa cuando las tablas existen, 404/409 sin cambios: `StartupWindowEndToEndIT` (ordenes 1-12; tests 12 verifica 404 event/order-not-found, 409 insufficient-inventory y metrica sin incremento) — [x]. Demostrado rojo (500) sin la correccion.
- Correccion minima y justificada frente a (b)/(c); no oculta una mala configuracion permanente (readiness DOWN: test 10; WARN con excepcion completa: `MissingTableMappingTest.translate_missingTable_logsTheFullException` + test 10), no cuelga ni cae con DynamoDB inaccesible (no se toca arranque; F-031 intacto), sin fuga interna (`expectUnavailable` comprueba que el cuerpo no contiene non-existent/ResourceNotFound/nombres de tabla/Exception), incrementa la metrica (+1 por respuesta) — [x]. La eleccion (a) es razonable: una rama de clasificacion que cubre todas las rutas y tabla borrada tras el arranque, sin estado compartido; (b) y (c) exigen ciclo de vida/estado y pueden dar 503 con app sana.
- Experimento de sondeo real >= 10 ciclos y uno con carga: el informe lo documenta (12 ciclos, 2 con carga) y yo lo reproduje independientemente con 6 ciclos sin carga, mismo resultado. Nota: yo no repeti los ciclos de carga ni los 10 (la CPU-load queda por confianza en el informe, coherente con los resultados observados) — [x] (no bloqueante).
- Sin debilitar mapeos/tests existentes (diff solo anade; no se modifica ningun test existente), README (catalogo 503 + seccion de arranque + troubleshooting), observability.md, DP-038 actualizados; init.sh e integracion en verde — [x].

## Checkpoints (CHECKPOINTS.md)
- C1: [x] ambos init.sh en verde, ejecutados por mi (964 tests).
- C2: [x] solo `infrastructure/web/error` importa SDK de AWS; dominio y usecase intactos.
- C3: [x] cada criterio con test real (IT con adaptador real + test unitario del mapeo); el IT falla con 500 sin la correccion.
- C4: [x] sin `.block()`/`Thread.sleep` en produccion (los `block(WAIT)` solo estan en el test, con plazo de `TestTimeouts`; `TimeoutPolicyGuardTest` pasa dentro de init.sh).
- C5: [x] no aplica: no se toca inventario.
- C6: [x] no aplica: sin transiciones nuevas.
- C7: [x] codigo, nombres y comentarios en ingles; sin Lombok ni `@Autowired` en campos.
- C8: [x] solo credenciales ficticias; el cuerpo no filtra nombres de tabla; el log lleva la excepcion en el servidor.
- C9: [x] JaCoCo >= 90 % verificado dentro de init.sh.
- C10: [x] alcance acotado a manejo de errores, tests, docs y bookkeeping.
- C11: [x] README (catalogo y troubleshooting), observability.md y DP-038 coinciden con el codigo y con lo medido (ventana ~1-2 s).
- C12: [x] el caso de uso/rutas que combinan puertos se prueban de punta a punta con DynamoDB Local real (Testcontainers) en `StartupWindowEndToEndIT`, incluida la transaccion de `POST /events` con solo la tabla `events` creada. Los adaptadores no cambian.
- C13: [x] DP-038 (Extra propio) registrado y enlazado en `decisions` de F-034; `origin: "own"` es honesto (no hay requisito del enunciado afectado; requirements.md sin cambios justificado); la feature sigue `in_progress` (no marcada done).

## Inventario de otras excepciones de la ventana (grep propio)
`ResourceNotFoundException` solo se consume en `DynamoDbTableProvisioner` (propio de provision; lo trata como "no existe") y ahora en la clasificacion web. Los adaptadores traducen `ConditionalCheckFailed`/`TransactionCanceledException` a excepciones de dominio, y `DynamoDbOrderRepository.isTransient` ya cubre cancelaciones transitorias; una transaccion sobre tabla inexistente lanza `ResourceNotFoundException` (probado en el IT, orden 11), no `TransactionCanceled`. SQS: `QueueDoesNotExistException` ya se contesta como 503 `order-enqueue-failed` (publicacion) y el consumidor/job tienen sus propios reintentos (DP-037). Sin otras excepciones sin clasificar halladas en las rutas de escritura/GET.

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
- `Retry-After: 5` es mayor que la ventana real (~1-2 s): aceptado y documentado en DP-038.
- El informe del implementer indica 12 ciclos (2 bajo carga de CPU); mi reproduccion independiente fue de 6 ciclos sin carga.
