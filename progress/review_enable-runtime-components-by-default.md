# Review — feature F-031 (enable-runtime-components-by-default)

**Veredicto:** APPROVED

## Criterios de aceptación
- Ambos flags `true` por defecto, fuente única (`@DefaultValue("true")` en `SqsConsumerProperties`/`ExpirationProperties` + `matchIfMissing=true` en `SqsConsumerConfig`/`ExpirationSchedulerConfig`; los flags no están en `application.yml`, no hay segunda fuente); `false` explícito sigue posible y documentado (`docs/aws.md`): `RuntimeComponentsDefaultsTest` (aplicación real solo con el `application.yml` principal, propiedad ausente -> presentes; `=false` -> ausentes), `SqsConsumerConfigTest`, `ExpirationConfigTest` — [x]
- Contextos de test sin debilitar aserciones; UN mecanismo compartido (`src/test/resources/application.properties`, no `application.yml`); tests que necesitan los componentes ponen `true` explícito (MessageRedeliveryIT, FailureInjectionIT, PurchaseConcurrencyIT, OrdersApiEndToEndIT, ComplimentaryApiEndToEndIT, ObservabilityEndToEndIT); los `false` explícitos preexistentes se conservan; diff de tests solo cambia 2 tests de config (propiedad ausente ahora = presente, y se añade el caso `=false`), sin quitar aserciones — [x]
- Infra inalcanzable con componentes activos: `RuntimeComponentsDefaultsTest.propertiesAbsent_infrastructureUnreachable_...` (reintentos con backoff >=2, barridos fallidos reintentados >=2, readiness 503 DOWN, liveness 200, contexto vivo; `SpringApplicationBuilder` con try-with-resources, appenders desenganchados: no hay fuga entre contextos cacheados) — [x]
- Prueba de extremo a extremo (hecha por mí, ver abajo) — [x]
- Docs/compose/README/DP actualizados; `INCLUDE_INTEGRATION=true ./init.sh` verde — [x] (el "3 veces" lo reporta el implementer, logs it1..it3; yo lo ejecuté una vez)

## Ejecución independiente
- `./init.sh` plano: `BUILD SUCCESSFUL in 1m 14s`, `==> init.sh OK`.
- `INCLUDE_INTEGRATION=true ./init.sh` (colima): `BUILD SUCCESSFUL in 5m 28s`, `init.sh OK`; sumando `build/test-results`: 948 tests, 0 skipped, 0 failures, 0 errors (baseline 944 + 4).
- (b1) `docker-compose up --build -d --wait` SIN modificar (git status limpio): compra de 2 -> `RESERVED`, +1 s `PENDING_CONFIRMATION`, +2 s `SOLD`; disponibilidad `sold=2, available=8`. Logs de `app`: `Reservation expiration scheduler started (interval=PT1M, ...)` y `SQS order consumer started (batchSize=10, ...)` sin ninguna variable de activación.
- (b2) Override fuera del repo (scratchpad) `TTL=PT30S`, `SQS_CONSUMER_ENABLED=false`, `EXPIRATION_INTERVAL=PT5S`: solo arranca el scheduler (`interval=PT5S`), no el consumidor; la orden RESERVED pasó a `AVAILABLE`; log `Expiration sweep ...: examined=1, released=1, skippedConflicts=0, failed=0`; disponibilidad `available=10, reserved=0`.
- (b3) Ambos `false`: tras 20 s la orden sigue `RESERVED` (`reserved=2`), 0 líneas de arranque del consumidor/scheduler.
- (c) Solo `app` (`--no-deps`, infra inalcanzable), 50 s: `Up (healthy)`, `RestartCount=0`, un solo `Started TicketflowApplication`; `:8081` readiness `503 {"status":"DOWN"}`, liveness `200 UP`; `ReceiveMessage ... retrying with backoff` a 00:50:55.6, :58.8, 00:51:03.3, :10.2, :21.4, :34.1 (separaciones crecientes, sin crash loop); `expiration sweep failed; will retry`.
- (d) `docker-compose down -v` tras cada prueba.
- 500 tras `down -v`: intenté reproducirlo en 8 ciclos frescos (`down -v` + `up --wait` + primer `POST /events` + primer `POST /orders`; 4 con compose sin modificar y 4 con override `PT5S`/`PT30S`/consumer off): 8/8 `202`. Con las 7 repeticiones del implementer son 15 arranques frescos sin recurrencia. El código de ese camino (POST /orders: reserva + publicación SQS) no está en el diff de F-031 (solo cambia defaults de dos flags, guardas y tests/docs), y el único caso observado fue con el consumidor apagado. Conclusión: no atribuible a F-031; probable carrera de primer uso de LocalStack/cola preexistente, no reproducible, sin log. No comprobé main (sin reproducción en la rama no hay base para comparar); se deja como observación.

## Otras comprobaciones
- Grep de estados antiguos ("desactivado por defecto", "solo compose", `enabled=false` como valor por defecto, `*_ENABLED: "true"` en compose) en código, docs, compose, tests: nada obsoleto. README, `docs/aws.md`, `docs/observability.md`, `docs/architecture.md`, `docs/requirements.md` (RF-3, RF-6), DP-006/DP-012/DP-037 coherentes. Compose: eliminadas las dos líneas, quedan comentarios que apuntan a DP-037.
- `git diff main --stat`: 18 archivos, limitados a 4 clases de config (2 properties, 2 config), tests, `src/test/resources/application.properties`, `docker-compose.yml`, docs, README, `feature_list.json` (entrada F-031 en `in_progress`, no `done`), `progress/`.
- C13: origin `own` honesto (la elección del default es decisión propia); DP-037 registrada y enlazada en `decisions` junto con DP-006 y DP-012; RF-3/RF-6 actualizados.
- Sin `.block()` ni `Thread.sleep` en código de producción añadido (solo `.block` en el helper del test). Sin secretos nuevos.

## Checkpoints (CHECKPOINTS.md)
- C1: [x]  C2: [x]  C3: [x]  C4: [x]  C5: [x] (sin cambios de inventario)  C6: [x] (sin cambios de transiciones)
- C7: [x]  C8: [x]  C9: [x] (JaCoCo del init.sh pasó)  C10: [x]  C11: [x]
- C12: [x] el flujo completo compra -> SOLD y expiración con adaptadores reales está cubierto por las IT con Testcontainers (que ahora activan los componentes con `true` explícito) y lo probé con el stack real; no cambia contratos de adaptadores.
- C13: [x]

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
1. `docs/decisions.md:23,133` el título de DP-006 sigue diciendo "job de expiración opcional" (sigue siendo desactivable, pero el default ya es activo); considerar reformular en un cambio futuro (cambiaría el ancla enlazada).
2. El 500 inicial del primer POST /orders tras `down -v` queda sin explicar; si reaparece, capturar `docker-compose logs app localstack` antes de bajar el stack.
