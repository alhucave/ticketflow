# Review — feature F-027 (spec-traceability)

**Veredicto:** CHANGES_REQUESTED

Rama `feature/F-027-spec-traceability` (1480a18 sobre main c526b7e). Solo documentación y arnés. El trabajo es de muy buena calidad y casi todo verifica por ejecución; hay **un** defecto bloqueante concreto y pequeño: una decisión propia real omitida del registro (la regla «la fecha del evento debe ser futura»).

## Verificación por ejecución

| # | Comprobación | Resultado |
|---|---|---|
| 1 | `./init.sh` (ejecutado por mí, Colima) | verde, exit 0 (BUILD SUCCESSFUL 30 s; `OK: 27 features with origin, 32 decisions, 69 requirement rows`) |
| 1 | `INCLUDE_INTEGRATION=true ./init.sh` | verde, exit 0 (BUILD SUCCESSFUL 4 m 51 s) |
| 2 | `git diff main --stat` | solo `.github/pull_request_template.md`, `AGENTS.md`, `CHECKPOINTS.md`, `README.md`, `docs/{architecture,conventions,decisions,requirements,security,verification}.md`, `feature_list.json`, `init.sh`, `progress/`. Nada en `src/`, build, Dockerfile, compose ni workflows |
| 4 | Verificador propio de enlaces y anclas (slug GitHub, ignora bloques de código) sobre README, AGENTS, CHECKPOINTS, SECURITY, `docs/*.md`, plantilla de PR | 860 enlaces, 0 rotos, 0 anclas malas |
| 4 | Rutas en backticks de requirements/decisions | 692 destinos/rutas; los 4 «faltantes» son falsos positivos legítimos (`amazon/dynamodb-local`, `text/event-stream`, `ghcr.io/alhucave/ticketflow`, `docker-compose.override.yml` de usuario) |
| 4 | Citas `Clase#método` (37) | las 37 existen en el archivo de la clase |
| 6 | Tiempo del validador | 0,19-0,45 s por ejecución (< 2 s) |
| 8 | F-027 en `feature_list.json` | `in_progress`, única `in_progress` |

## Cobertura del PDF (re-extraído con `pdftotext -layout`) contra la matriz

| Sección del PDF | Ítems del PDF | Ids de la matriz | Falta |
|---|---|---|---|
| Contexto (problema, migración reactiva, no sobreventa/tiempos bajos) | 3 párrafos | CTX-1, CTX-2, CTX-3 | no |
| 5 estados | AVAILABLE, RESERVED, PENDING_CONFIRMATION, SOLD, COMPLIMENTARY + impacto en reportes | CTX-4..CTX-8, CTX-9 | no |
| 5 notas generales | 1..5 | NG-1..NG-5 | no |
| Objetivo | 3 párrafos/6 afirmaciones | OBJ-1..OBJ-6 | no |
| RF 1-7 | 7 | RF-1..RF-7 | no |
| Stack obligatorio | Java 25, Boot 4.x, WebFlux, BD, cola, Docker, Clean Architecture | RT-1..RT-7 | no |
| Especificaciones técnicas | 5 viñetas | ET-1..ET-5 | no |
| Entregable 1 (+3 sub-puntos) | | EN-1, EN-1.1..1.3 | no |
| Entregable 2 (+5 sub-puntos) | | EN-2, EN-2.1..2.5 | no |
| Entregable 3 (+4 sub-puntos, 90 %) | | EN-3, EN-3.0, EN-3.1..3.4 | no |
| Entregables 4 y 5 | | EN-4, EN-5 | no |
| Seguridad | secretos; reintentos maliciosos, idempotencia, abuso de recursos | SEG-1..SEG-4 | no |
| Diagramas | arquitectura; flujo/secuencia; explicación | DIA-1..DIA-3 | no |
| AWS | seguridad, cloud-native, costos, observabilidad, gobernanza | AWS-1..AWS-5 | no |

Ningún ítem del PDF falta. Los estados son honestos: los 2 `Parcial` (CTX-3 sin pruebas de carga/latencia; CTX-9 sin reportes) y los 5 `Solo diseño` (AWS) coinciden con la realidad; RF-1 (CTX-7/NG): `SOLD`/`COMPLIMENTARY` verificado en código. Las afirmaciones de evidencia verificadas por muestreo (PurchaseConcurrencyIT 300 compras/capacidad 100; DynamoDbInventoryRepositoryIT 200 reservas/capacidad 50; RequestPurchaseUseCaseIT 50 peticiones iguales; clases citadas existen y demuestran lo dicho).

Hallazgos del implementer reflejados con honestidad:
- TTL sin tope vs «máximo 10 minutos»: en RF-2 («el sistema no impide configurar un valor mayor») y DP-006 («no hay tope superior… contradiría el enunciado»). Correcto. Observación: RF-2 queda `Cumplido con interpretación`; es defendible porque el valor por defecto cumple y está dicho, pero un lector podría preferir `Parcial`. No bloqueante.
- Job de expiración off por defecto: RF-6 y DP-006. Correcto.
- `release.yml` nunca ejecutado: DP-029 («Estado real»). Correcto (no es ítem del enunciado, por eso no está en la matriz).
- CI de main rojo una vez por IT intermitente: DP-030/DP-022. Correcto.

## Spot-check de decisions.md contra código/configuración (28 afirmaciones, todas coinciden)

`ticketflow.orders.max-quantity: 10` (application.yml:43); `max-in-memory-size: 32KB`; `management.server.port` 8081 y `address` 127.0.0.1; exposure `health,info,prometheus`; liveness=`livenessState`, readiness=`readinessState,dynamodb,sqs`; `ticketflow.admin.api-key: ${ADMIN_API_KEY:}`; `OrderMapper.KEY_MIN_LENGTH=16`; `IdempotencyKey.MAX_LENGTH=128`; `CreateEventRequest.MAX_CAPACITY=1_000_000`; `IssueComplimentaryCommand.MAX_QUANTITY=1000` y `MAX_REASON_LENGTH=200`; `PathIds.MAX_LENGTH=64`; `RateLimitProperties` defaults (true/20/1/5/0.05/10000/15m/false); rutas limitadas POST /orders, /events, /events/{id}/complimentary; `init-queues.sh`: `orders-dlq`, `maxReceiveCount` 3 (`ORDERS_MAX_RECEIVE_COUNT`), `VisibilityTimeout` 30; compose: `localstack/localstack:4.14.0`, `amazon/dynamodb-local:3.3.1`, puertos 8000/4566/8080/8081 todos en `127.0.0.1`; `ORDERS_MAX_RECEIVE_COUNT` reenviado por compose; `TICKETFLOW_ORDERS_MAX_QUANTITY` no reenviado; `ticketflow.reservation.ttl` ausente de application.yml; PureCreate evento `id` generado por servidor; README tabla de configuración: valores sin cambios (solo se añadieron enlaces DP). Suma las 48 comprobaciones del implementer, que también revisé como coherentes.

## Controles negativos del validador (ejecutados por mí en copia de scratch, fuera del repo)

| Caso | exit | Mensaje |
|---|---|---|
| base | 0 | `OK: 27 features with origin, 32 decisions, 69 requirement rows` |
| origin ausente (F-005) | 1 | `F-005: missing or invalid origin 'None' (valid: spec, interpretation, own)` |
| origin inválido | 1 | `F-001: missing or invalid origin 'bogus' ...` |
| no-spec sin `decisions` (F-024) | 1 | `F-024: origin 'own' requires a non-empty 'decisions' list ...` (+ huérfanos) |
| DP inexistente referenciado | 1 | `F-024: references DP-099, which does not exist in docs/decisions.md` |
| DP huérfano vigente | 1 | `DP-023 is not referenced by any feature ... (orphan decision)` |
| DP duplicado | 1 | `duplicate decision id DP-004` (+ índice desincronizado) |
| encabezado DP mal formado | 1 | `malformed decision id in heading '### DP-10: ...' (expected DP-NNN, three digits)` |
| DP mal formado en feature | 1 | `F-024: malformed decision id 'DP-3'` |
| fila de matriz con estado inválido | 1 | `row RF-5 has invalid status 'Hecho' (valid: [...])` |
| fila sin estado | 1 | `row RF-5 has 5 columns, expected 6` |
| fila eliminada | 1 | `coverage summary says Total=69 but the matrix has 68` |
| `DP-666` inexistente en fila | 1 | `row RF-2 references DP-666, which does not exist` |

Todos fallan con mensajes claros. (Límite conocido, no bloqueante: si se borra una fila **y** se ajusta el resumen a mano, el validador no lo detecta porque no tiene la lista canónica de ids del PDF; el validador lo declara como revisión humana.)

## Criterios de aceptación (issue #56)

- `docs/requirements.md` derivado del PDF, estados, dónde, evidencia, honesto: cubierto — [x]
- `docs/decisions.md` con DP-NNN, tipo, feature, qué, por qué, configurar, impacto, estado; sembrado con las reales y verificadas: **parcialmente** — [ ] falta una decisión real (ver Cambios requeridos)
- `origin` en las 27 features + `decisions`; `init.sh` valida; controles negativos: cubierto (21 del implementer + 13 repetidos por mí) — [x]
- C13 en `CHECKPOINTS.md`, flujo en `AGENTS.md` (regla, 5 pasos, roles leader/implementer/reviewer; mención de que el usuario seguirá añadiendo features propias) y `.github/pull_request_template.md` (ES/EN, pregunta explícita): cubierto — [x]
- README enlaza ambos, enlaces/rutas resuelven, sin cambios de código: cubierto — [x]

Juicio de `origin`: F-021 y F-023 `interpretation` razonable; F-003 `own` (CI/ghcr) correcto; F-024 `own` correcto (observabilidad de aplicación no pedida; el enunciado solo la menciona en la parte AWS). F-023 es mixto (muchos extras propios: DP-001, 016-019, 022, 026, 027): `interpretation` es aceptable por «abuso de recursos», aunque `own` sería igual de defendible; no bloqueante.

## Checkpoints (CHECKPOINTS.md)

- C1: [x] `./init.sh` y `INCLUDE_INTEGRATION=true ./init.sh` verdes (ejecutados por mí)
- C2: [x] sin cambios de código
- C3: [ ] ← Razón: el criterio «registro sembrado con las reales» no queda cubierto del todo (decisión omitida, ver abajo); el resto de criterios tienen su validador y controles
- C4: [x] sin cambios en `src/main`
- C5: [x] n/a
- C6: [x] n/a
- C7: [x] n/a (el código no cambia; docs en español según AGENTS.md)
- C8: [x] sin secretos
- C9: [x] cobertura sin cambios (línea 99,5 % con integración)
- C10: [x] alcance acotado (los ajustes en `docs/security.md`/`AGENTS.md` «repositorio privado» corrigen una afirmación falsa; aceptables)
- C11: [x] docs coinciden con el código; enlaces verificados
- C12: [x] n/a (sin casos de uso nuevos)
- C13: [ ] ← Razón: la matriz y el registro omiten una restricción propia real: `CreateEventUseCase` rechaza eventos con fecha no futura (ver abajo). El `origin` del resto es honesto

## Cambios requeridos

1. **Registrar la regla «la fecha del evento debe ser futura».** `src/main/java/com/ticketflow/usecase/CreateEventUseCase.java:47-48` lanza `InvalidEventException("Event date must be in the future")` (`400 invalid-event`) si `startsAt` no es posterior a `clock.instant()`; el README (línea 271) la documenta. El enunciado (RF-1) solo pide «fecha»; nada exige que sea futura, así que es una restricción propia que no aparece en `docs/decisions.md` (ni DP-010 ni DP-020 la mencionan; `grep -i futur docs/decisions.md docs/requirements.md` no devuelve nada). Acción: añadir `DP-033` (Tipo `Interpretación del enunciado` o `Extra propio`, a elegir; Feature F-010/F-018) con qué/por qué/cómo quitarla/impacto/verificación (la prueba de `CreateEventUseCase` que cubre la fecha pasada), añadirla al índice, a `decisions` de F-018 (o F-010) en `feature_list.json`, y enlazarla en la columna `DP` de la fila RF-1 de `docs/requirements.md` (cambiando su estado a `Cumplido con interpretación` y actualizando el resumen de cobertura: 44/18). Incluir en el commit la verificación con `./init.sh --check-registry`. Regla incumplida: `docs/decisions.md` «Reglas» punto 1 y C13 («comportamiento, límite o restricción que el enunciado no exige»).

## Observaciones no bloqueantes

- `POST /events` es anónimo (cualquiera crea eventos) mientras que las cortesías exigen `X-Admin-Key`: está implícito en DP-015 («no hay autenticación»), pero la asimetría podría anotarse en una línea de DP-015 o `security.md`.
- La barrera de cobertura mide solo líneas globales (el enunciado dice «cobertura mínima del 90 %» sin definir la métrica): es una pequeña interpretación ya explicada en EN-3.0 pero no registrada como DP.
- El implementer reportó una discrepancia README vs colección Postman (31 peticiones/63 aserciones frente a 32/65 contadas); no se toca en esta feature, pero conviene corregirla en una feature posterior.
- DP-028 (LocalStack 4.14.0) está clasificada como `Interpretación`; es más bien un `Extra propio` (restricción operativa); sin impacto en el validador.
- DP-031 y DP-028 citan «historial» como verificación; aceptable, pero menos comprobable que los demás DP.
