# Review — feature F-025 (docs-and-requests)

**Veredicto:** APPROVED

Rama `feature/F-025-docs-and-requests` (3 commits sobre `main` f0540d4, HEAD 34c6991). Verificado de forma independiente; nada se tomó del informe del implementer sin reejecutarlo.

## 1. Build, cobertura y alcance
- `./init.sh`: verde (`==> init.sh OK`). 768 pruebas, 0 fallos, líneas 2.139/2.152 = 99,40 %. Coincide con el README.
- `INCLUDE_INTEGRATION=true ./init.sh` (Colima: `DOCKER_HOST` y `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` exportados): verde, `BUILD SUCCESSFUL in 4m 42s`. 929 pruebas, 0 fallos/errores/omitidas; líneas 2.142/2.152 = 99,54 %; ramas 653/681 = 95,89 %. Coincide con el README (sección «Cifras actuales»).
- `git diff main --stat -- src build.gradle.kts gradle.lockfile Dockerfile docker docker-compose.yml .env.example .github` está vacío: no cambió código de aplicación, build, imagen ni CI. El diff completo son 14 ficheros: README, docs/ (architecture, security, observability, verification), AGENTS.md, CHECKPOINTS.md, feature_list.json (F-025 pasa a `in_progress`, no `done`), progress/ y requests/.

## 2. Diagramas Mermaid (docs/architecture.md)
Renderizados con `mermaid-cli` 12.0.0 (`mmdc-local`: node:22 + chromium, `--no-sandbox`). La imagen oficial `minlag/mermaid-cli` falla en este host arm64 por falta de Chrome (confirmado; no es un fallo del repo).
- d1 Componentes y capas (flowchart): OK, SVG 272 KB; PNG revisado visualmente, legible.
- d2 Flujo de compra (sequenceDiagram, con alt/opt de compensación y replay): OK, 220 KB.
- d3 Expiración y carrera con el consumer (sequenceDiagram con `par`/`and`): OK, 204 KB.
- d4 Cortesías (sequenceDiagram): OK, 207 KB.
- d5 Estados (stateDiagram-v2): OK, 212 KB.
- d6 Modelo de datos (erDiagram; solo PK, sin `SK` como clave): OK, 261 KB.
- Los bloques no contienen `;` ni `#` (causas habituales de fallo en motores más antiguos). README no tiene bloques Mermaid; los enlaza a los seis encabezados de `docs/architecture.md` (anclas verificadas contra los encabezados reales). `docs/architecture.md` tiene un índice propio.
- Contenido de los diagramas contrastado con el código: transiciones de `TicketStatus.canTransitionTo`, orden de filtros (`@Order` MIN, +5, +15, +20), consulta del GSI con `RESERVED`/`PENDING_CONFIRMATION`, cortesía con `reservationExpiresAt == createdAt`, `Detached`, tope de 5 relecturas, etc.

## 3. Colección de peticiones (requests/)
Pila de compose NUEVA (`down -v` + `up --build -d --wait`, 12 s, tres servicios `Healthy`) con `ADMIN_API_KEY=$(openssl rand -hex 32)`:
- `./requests/demo.sh`: `Demo finished OK` (evento 201, compra 202, polling a SOLD, disponibilidad 97/3, replay 202 mismo `orderId`, 409 `idempotency-key-reused`, 400 `invalid-idempotency-key`, cortesías 201 con `available` 95 y `complimentary` 2, SSE con valor actual, métricas `ticketflow_*`).
- `./requests/run-newman.sh`, dos ejecuciones seguidas: ambas EXIT=0, `requests 31 (0 failed)`, `test-scripts 31`, `prerequest-scripts 11`, `assertions 63 (0 failed)`, ~14,5 s, sin 429.
- Pila sin `ADMIN_API_KEY`: Newman 28 peticiones / 57 aserciones, 0 fallos (cortesías omitidas, «sin X-Admin-Key» espera 403); `demo.sh` omite el paso 8 y termina OK.
- Flujos cubiertos por la colección: crear, consultar y listar evento; disponibilidad instantánea y stream (omitido en Newman con `skipStream`, 404 JSON del stream sí aserto); compra 202; polling a SOLD; replay; 409 clave reutilizada; 409 inventario insuficiente; 400 (sin clave, clave corta, cantidad > máx., evento inválido, fecha pasada, JSON roto); 404 (evento, orden, disponibilidad, stream, compra en evento inexistente); cortesías sin clave (401/403), con clave 201 y replay 201; liveness, readiness y Prometheus en 8081; 404 de `/actuator` en 8080.
- Variables de colección: `baseUrl`, `managementUrl`, `adminKey` (vacía), `eventId`, `orderId`, `idempotencyKey`. La clave de idempotencia se genera en pre-request como `pm-` + GUID (39 caracteres, >= 16). Entorno: solo `baseUrl`, `managementUrl`, `adminKey` (tipo `secret`, valor vacío).
- Sin secretos: `adminKey` vacía, `run-newman.sh` pasa `ADMIN_API_KEY` por entorno, sin cadenas hex largas en `requests/`, README ni docs. `demo.sh` tiene `set -euo pipefail` y lee `ADMIN_API_KEY` del entorno. Ambos scripts son ejecutables (`-rwxr-xr-x`, se conserva en el clon).

## 4. Clon limpio (literal)
`git clone -q -b feature/F-025-docs-and-requests <repo local> ticketflow` en `$HOME/.cache/rev-f025/clone` (la rama no está publicada, así que no se puede usar la URL de GitHub del README; es la única desviación). Después, los comandos del «Inicio rápido»:
1. clone: OK, HEAD 34c6991, árbol limpio.
2. `export ADMIN_API_KEY=$(openssl rand -hex 32); docker-compose up --build -d --wait`: OK, 11,7 s, los tres servicios `Healthy` (docker-compose 5.6.0).
3. `./requests/demo.sh`: `Demo finished OK`.
4. `./requests/run-newman.sh`: EXIT=0, 63 aserciones, 0 fallos.
5. Las tres peticiones manuales: evento creado; compra `202 Accepted` + `Location: /orders/{orderId}` + cabeceras de seguridad; disponibilidad `{"available":117,...,"sold":3,...,"capacity":120}`.
6. `docker-compose down -v`: OK. `docker ps -a` queda vacío (todas las pilas, incluida la del árbol de trabajo, desmontadas).

## 5. Comprobaciones puntuales de README y docs contra el código
- PASS Defaults de `ticketflow.rate-limit.*` (true, 20, 1, 5, 0.05, 10000, 15m, false): `RateLimitProperties`.
- PASS Defaults de `ticketflow.expiration.*` (false, PT1M, PT10S, 4, 500, PT20S): `ExpirationProperties`.
- PASS Defaults y rangos de `ticketflow.sqs.consumer.*` (false, 10 [1-10], 20s [1-20], 30s, 4, 25s, 1s/30s): `SqsConsumerProperties`.
- PASS `ticketflow.dynamodb.*` (provisioning-max-attempts 30, poll 500ms, region, enabled false) y `ticketflow.sqs.*` (orders-queue-name `orders`, orders-queue-url): `DynamoDbProperties`, `SqsProperties`.
- PASS `ticketflow.observability.*` (queue-metrics false/15s/5s, health 2s/5s) y `ticketflow.reservation.ttl` PT10M, `ticketflow.availability.poll-interval` 1s (`UseCaseConfig`), `max-quantity` 10, `max-in-memory-size` 32KB, puerto 8081 y dirección 127.0.0.1 (`application.yml`).
- PASS Variables de compose del README (`APP_PORT`, `MANAGEMENT_PORT`, `DYNAMODB_PORT`, `LOCALSTACK_PORT`, `AWS_*`, `ORDERS_*`, `ADMIN_API_KEY`, `TICKETFLOW_ENVIRONMENT`) frente a `docker-compose.yml` y `.env.example`; puertos solo en `127.0.0.1`; imágenes `amazon/dynamodb-local:3.3.1` y `localstack/localstack:4.14.0` (pin con su motivo).
- PASS Catálogo de errores: los 28 `type` que emite `ApiExceptionHandler` (validation-error ... service-unavailable, incluidos admin-unauthorized, admin-disabled, client-error) aparecen en la tabla; ninguno de más.
- PASS Cabeceras: `Retry-After: 1` en 409 concurrent-modification, `Retry-After: 5` en 503 service-unavailable, `WWW-Authenticate: ApiKey` en 401; cabeceras de seguridad (`nosniff`, `no-store`, `X-Frame-Options: DENY`, CSP) en `SecurityHeadersWebFilter`, y observadas en la respuesta real 202.
- PASS Estados y transiciones: 5 estados, matriz de `TicketStatus` coincide con diagrama y tabla; no existen `EXPIRED`/`FAILED`.
- PASS Tablas y GSI: `events`/`inventory` PK `eventId`, `orders` PK `orderId` con `idempotencyKey-index` y `status-reservationExpiresAt-index` (ALL), `order_audit` PK `orderId` + SK `timestamp`, `PAY_PER_REQUEST`; SK con 9 decimales y sufijo `#uuid` (`DynamoDbTables`, `DynamoDbOrderRepository`).
- PASS Límites de entrada: `Idempotency-Key` 16-128 `[A-Za-z0-9._:-]`, ids de ruta `{1,64}`, `name`/`venue` <= 200, capacidad <= 1.000.000, cortesía 1-1000 y `reason` <= 200 sin control, correlation id `{1,64}`.
- PASS Rutas limitadas por el rate limit: `POST /orders`, `POST /events`, `POST /events/{id}/complimentary` (`RateLimitWebFilter`); backoff SSE 1 s-30 s (`AvailabilityController`).
- PASS Métricas: `ticketflow_orders_placed_total`, `orders_sold_total`, `purchases_replayed_total`, `complimentary_issued_total` aparecen en la salida real de Prometheus del demo.
- PASS CI/CD: `ci.yml` (job `verify`, Java 25 Temurin, `INCLUDE_INTEGRATION=true`, artefacto `reports`), `security.yml` (PR, push a main, cron `17 5 * * 1`; jobs dependencies/secrets/image), `release.yml` (tag `v*`, `ghcr.io/alhucave/ticketflow`, tags semver x.y.z, x.y y latest, `packages: write` solo en el job), `dependabot.yml` existe, acciones fijadas por SHA.
- PASS Sondeo de salud: grupos `liveness` = `livenessState`, `readiness` = `readinessState,dynamodb,sqs`; solo `health,info,prometheus` expuestos; 8080 responde 404 a `/actuator` (aserto de Newman).
- PASS Docs de apoyo sin hechos obsoletos: `docs/verification.md` (nivel Newman y sección F-025, cifras 31/63 y demo reales), `docs/security.md` (sección «Controles de la aplicación» con anclas correctas; referencias antiguas a «README, Rate limiting» corregidas), `docs/observability.md` (referencia al catálogo de errores), `AGENTS.md` (mapa con README y `requests/`), `CHECKPOINTS.md` (C6 y C11 consistentes con los encabezados reales), `progress/current.md`.
- Contenido del README: solución, instalación y ejecución detalladas (compose, imagen suelta, JDK 25), comandos Docker, decisiones de diseño y enlace a las decisiones clave de `docs/architecture.md`, ejemplos de cada endpoint, tabla de contenidos (16 secciones), tabla de configuración, referencia de API, catálogo de errores, pruebas y cobertura, resúmenes de observabilidad y seguridad, CI/CD, solución de problemas, limitaciones. Prosa en español, términos técnicos en inglés. No se creó `docs/aws.md`; AWS solo aparece como «ver F-026». `feature_list.json`: F-025 está `in_progress`, no `done`.

## Criterios de aceptación
- README cubre todo lo exigido por la especificación: verificado sección por sección arriba y contra el código — [x]
- Los diagramas de arquitectura y de secuencia se renderizan en GitHub: los seis bloques de `docs/architecture.md` compilan con mermaid-cli 12.0.0 y no usan constructos problemáticos; README los enlaza — [x]
- La colección ejerce crear evento, compra, estado de la orden y disponibilidad: `requests/ticketflow.postman_collection.json` (31 peticiones/63 aserciones, 0 fallos en pila nueva) y `requests/demo.sh` — [x]
- Seguir el README desde un clon limpio funciona: sección 4, todos los pasos OK — [x]

## Checkpoints (CHECKPOINTS.md)
- C1: [x] `./init.sh` e `INCLUDE_INTEGRATION=true ./init.sh` verdes, ejecutados por el reviewer.
- C2: [x] Sin cambios en `src/`; la regla de dependencias no se toca (ArchUnit sigue verde).
- C3: [x] Cada criterio de aceptación tiene verificación real (Newman con 63 aserciones sobre la pila real, demo con comprobaciones y salida != 0, render de diagramas, clon limpio). Es una feature de documentación: no hay código nuevo que requiera tests JUnit.
- C4: [x] Sin código de producción tocado.
- C5: [x] Sin cambios de inventario en código; la documentación describe correctamente las escrituras condicionadas.
- C6: [x] Las transiciones documentadas coinciden exactamente con `TicketStatus.canTransitionTo`; el diagrama 5 y la tabla son correctos.
- C7: [x] Scripts y comentarios de `requests/` en inglés; README y docs en español, según `AGENTS.md`/`docs/conventions.md`. Commits en inglés con prefijo `docs:`.
- C8: [x] Sin secretos: `adminKey` vacía y `secret`, clave por entorno, solo credenciales ficticias `test`/`test`.
- C9: [x] Cobertura 99,40 % (sin integración) y 99,54 % (con integración), mínimo 90 %.
- C10: [x] Solo README, docs/, requests/, AGENTS.md, CHECKPOINTS.md y progress/; sin código ajeno al `acceptance`.
- C11: [x] README, docs/, diagramas y `requests/` coinciden con el código (15+ comprobaciones PASS arriba).
- C12: [x] No aplica (no hay casos de uso nuevos); las pruebas de integración existentes siguen verdes (929).

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
1. `src/main/java/com/ticketflow/infrastructure/web/ratelimit/ClientRateLimiter.java:13` dice «see the README»; el README conserva el tema (viñeta «Rate limit» en la referencia de la API y la limitación de borde), pero ya no hay una sección con ese nombre. Es solo un comentario; se puede ajustar en F-026 u otra feature con código.
2. Hay solapamiento de contenido entre «Flujo de compra: reglas detalladas» (docs/architecture.md) y los diagramas 2 y 3; es complementario, no contradictorio, y los hechos coinciden con el código.
3. El README clona desde `https://github.com/alhucave/ticketflow.git`; la rama aún no está publicada, así que el clon limpio se hizo desde el repo local (mismo contenido).
4. El implementer editó `CHECKPOINTS.md` (C6, C11). Son aclaraciones coherentes con los encabezados reales y sin relajar ningún criterio, pero conviene que el leader lo tenga presente.
5. Tras recrear solo `app` el primer mensaje puede tardar ~30 s (documentado en Troubleshooting; demo y Newman lo toleran). No ocurrió en ninguna de las pilas nuevas de esta revisión.
