# Implementación F-027 — spec-traceability-and-decisions-register

Rama: `feature/F-027-spec-traceability` (desde `main` c526b7e; sin push, sin PR). Estado en `feature_list.json`: `in_progress` (no se marca `done`). Issue #56.

Solo documentación y arnés: **ningún cambio en `src/`, `build.gradle.kts`, `Dockerfile`, `docker-compose.yml` ni `.github/workflows/`**.

## Archivos

Nuevos:
- `docs/requirements.md`: matriz de trazabilidad (69 filas) derivada del PDF `Prueba Ajustada Alex.pdf` (re-extraído con `pdftotext -layout`, no de los docs del repo).
- `docs/decisions.md`: registro de 32 decisiones `DP-001..DP-032`, con índice, plantilla y reglas.
- `.github/pull_request_template.md`: checklist ES/EN.
- `progress/impl_spec-traceability.md` (este informe).

Modificados:
- `feature_list.json`: `origin` en las 27 features, `decisions` en las no-`spec` (y en `spec` con interpretaciones) y la entrada nueva F-027 (`in_progress`, `depends_on: [F-026]`, issue 56, `origin: own`).
- `init.sh`: paso de validación (python3 stdlib) antes del build, y flag `--check-registry`.
- `CHECKPOINTS.md` (C13), `AGENTS.md` (mapa, flujo, sección «Enunciado vs decisiones propias» con los 5 pasos; «repositorio público» corregido a privado).
- `README.md` (TOC + sección «Enunciado vs decisiones propias»; enlaces `DP-xxx` en las tablas de configuración sin cambiar ningún valor; estructura y sección de pruebas).
- `docs/architecture.md` (un párrafo con enlaces en «Decisiones clave»), `docs/verification.md` (descripción de `init.sh`), `docs/conventions.md` (una línea sobre la plantilla de PR y el registro), `docs/security.md` (corregido «El repositorio es público» en dos sitios: es privado).
- `progress/current.md`.

`git diff main --stat` (ver abajo) solo muestra: `docs/`, `README.md`, `init.sh`, `feature_list.json`, `CHECKPOINTS.md`, `AGENTS.md`, `.github/pull_request_template.md` y `progress/`.

## Decisiones de diseño

- **Fuente única de los enlaces.** Los dos documentos se generaron desde plantillas en el scratch (fuera del repo) con una pequeña herramienta que resuelve cada `{{Clase}}` a un enlace relativo real y **falla si la clase o el método de prueba citado no existe** (`{{Clase#metodo}}`); así toda ruta/clase/método citado como enlace existe por construcción. El resultado generado es lo que se versiona (la herramienta no).
- **Formato parseable.** Matriz: una fila por línea, 6 columnas `ID | Requisito | Estado | DP | Dónde | Evidencia`; el estado es exactamente uno de 5 valores; sin `|` dentro de celdas. Ids con prefijos `CTX, OBJ, NG, RF, RT, ET, EN, SEG, DIA, AWS` (añadí `OBJ-*` para la sección «Objetivo», que el encargo no nombraba). Registro: cada decisión es un encabezado `### DP-NNN: título` con líneas `- **Tipo:**` y `- **Estado:**`; la plantilla vive en un bloque de código (el validador ignora los bloques de código).
- **Qué valida `init.sh`** (más allá de lo pedido, todo mecánico): (1) `origin` válido en cada feature; (2) no-`spec` con `decisions` no vacío; (3) `DP` referenciado existe; (4) `DP` vigente sin ninguna feature = huérfano (los `Reemplazada por DP-xxx` están exentos y el destino debe existir); (5) ids duplicados/mal formados (en encabezados y en `feature_list.json`); (6) fila de matriz con estado no válido; además: `Tipo`/`Estado` válidos, índice de `decisions.md` idéntico a las entradas, `Cumplido con interpretación` exige un `DP` y todo `DP` de la matriz debe existir, ids de fila únicos, 6 columnas, y el **resumen de cobertura debe coincidir con el conteo real**. Corre siempre (~0,07 s) antes del build, conserva el `BOOTSTRAP` y los exit codes (falla con 1 antes de invocar Gradle); `./init.sh --check-registry` sale tras validar.
- **Honestidad sobre lo que el validador no puede hacer**: no juzga si un `origin` o un estado de la matriz es honesto ni comprueba que las rutas existan; eso es revisión humana (C13) y está dicho en los documentos. Las rutas las comprobé yo con el verificador (abajo).
- **Clasificación.** `Interpretación del enunciado` solo cuando el enunciado pide/define algo y calla el cómo (estados de la orden, TTL, frontera, cortesías, SQS/DLQ, SSE, auditoría...); `Extra propio` cuando nada parecido se pide (máx. 10 por orden, rate limit, límites, hardening, métricas, CI, ghcr). Añadí decisiones que el encargo no listaba pero que son reales y verificadas: **DP-007** (la venta se confirma automáticamente: no hay pago), **DP-008** (inventario por contadores, no por asiento), **DP-010** (listado de eventos sin filtro ni paginación), **DP-021** (problem+json). Las decisiones del encargo sobre cortesía se partieron en DP-013 (endpoint), DP-014 (cortesía como order record) y DP-015 (guardia X-Admin-Key).
- **`origin` por feature** (juzgado por el texto del PDF): `spec`: F-001, F-002, F-004..F-011, F-014..F-018, F-022, F-025, F-026; `interpretation`: F-012, F-013, F-019, F-020, F-021, F-023; `own`: F-003 (CI/ghcr), F-024 (observabilidad), F-027. F-023 es `interpretation` (la sección de seguridad pide considerar abuso de recursos/reintentos; los controles concretos son nuestros) y F-021 `interpretation` (el enunciado define COMPLIMENTARY pero no cómo se emite). El líder puede discrepar: es un juicio.
- **Estados de la matriz** (resumen real: 45 Cumplido, 17 Cumplido con interpretación, 2 Parcial, 5 Solo diseño, 0 No cumplido; total 69). Los dos `Parcial` son **CTX-3** (tiempos de respuesta bajos bajo alta carga: no hay pruebas de carga ni de latencia) y **CTX-9** (reportes operativos/contables/de inventario: no existe ningún reporte). Los 5 de «Solo diseño» son AWS-1..AWS-5.

## Hallazgos honestos (para que el líder decida; no los toqué)

1. **El TTL de reserva (`ticketflow.reservation.ttl`) no tiene tope superior**: el enunciado dice «máximo 10 minutos» y el sistema permite configurar más. Queda dicho en DP-006 y RF-2.
2. **El job de expiración está desactivado por defecto** en la aplicación (`ticketflow.expiration.enabled=false`; `true` solo en compose): RF-6 queda como «Cumplido con interpretación» por eso (DP-006).
3. **`release.yml` nunca se ha ejecutado**: el repositorio no tiene ningún tag, así que no hay imagen en ghcr.io (DP-029).
4. **El CI de `main` falló una vez** (ejecución 37128517785, commit c526b7e, 2026-10-03): `HardeningEndToEndIT#securityHeaders_arePresentOnSuccessAndOnEveryKindOfError` falló (929 pruebas, 1 fallo); el mismo contenido pasó en el PR. Posible prueba intermitente; está mencionado en DP-022/DP-030. En mis dos ejecuciones locales con integración pasó.
5. Recuento propio de la colección de Postman: 32 peticiones y 65 llamadas `pm.test`; el README dice 31 peticiones y 63 aserciones (probablemente sin el stream SSE, que Newman omite). No repetí esas cifras en los documentos nuevos (no cito cifras de la colección); no lo toqué.
6. En `docs/security.md` «El repositorio es público» era falso (es privado); lo corregí (2 sitios). `SECURITY.md` ya refleja que es privado.


## Salida literal de `./init.sh`

Ejecutado sobre los archivos finales (con `DOCKER_HOST` y `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` exportados para Colima). Se muestran las líneas relevantes del log completo (el log de Gradle está en el scratch).

`./init.sh` (sin integración):

```
==> Validating feature_list.json
OK: 27 features, in_progress=['F-027']
==> Validating spec traceability and decisions register
OK: 27 features with origin, 32 decisions, 69 requirement rows
==> Building and verifying (tests + 90% coverage gate)
> Task :testClasses
> Task :test
> Task :jacocoTestCoverageVerification
> Task :jacocoTestReport
BUILD SUCCESSFUL in 30s
11 actionable tasks: 11 executed
==> init.sh OK
./init.sh  64.52s user 3.62s system 219% cpu 30.993 total
```

`INCLUDE_INTEGRATION=true ./init.sh` (lo que corre el job `verify` del CI):

```
==> Validating feature_list.json
OK: 27 features, in_progress=['F-027']
==> Validating spec traceability and decisions register
OK: 27 features with origin, 32 decisions, 69 requirement rows
==> Integration tests enabled (INCLUDE_INTEGRATION=true)
==> Building and verifying (tests + 90% coverage gate)
> Task :testClasses
> Task :test
> Task :jacocoTestCoverageVerification
> Task :jacocoTestReport
BUILD SUCCESSFUL in 4m 49s
11 actionable tasks: 11 executed
==> init.sh OK
INCLUDE_INTEGRATION=true ./init.sh  149.11s user 12.71s system 55% cpu 4:49.54 total
```

Cifras reales de ese build (JaCoCo XML y resultados de test):

```
integration build: tests=929 failures=0 errors=0; LINE 2142/2152 = 99.54%; BRANCH 653/681 = 95.89%
plain build (./init.sh): tests=768 failures=0 errors=0; LINE 2139/2152 = 99.40%; BRANCH 650/681 = 95.45%
```

Tiempo de la validación del registro: `./init.sh --check-registry` = 0,07 s en total (muy por debajo de 2 s). No altera el job `verify` (corre `./init.sh` con `INCLUDE_INTEGRATION=true`): la validación es el paso previo al build y el resto del script no cambió.

Corrida verde del validador aislado sobre los archivos reales:

```
$ ./init.sh --check-registry
==> Validating feature_list.json
OK: 27 features, in_progress=['F-027']
==> Validating spec traceability and decisions register
OK: 27 features with origin, 32 decisions, 69 requirement rows
```

## Controles negativos del validador

Script `controls.sh` (en el scratch, no versionado): para cada regla copia `init.sh`, `feature_list.json` y los dos documentos a un directorio de scratch, rompe UNA cosa y ejecuta `./init.sh --check-registry` (nunca se tocó el repositorio ni se commiteó un estado roto). Resultado: **21/21 con el desenlace esperado** (0 `UNEXPECTED`). Salida literal (se omite la línea repetida `OK: 27 features, in_progress=...` del primer paso):

```
[PASS] 00-baseline -> exit 0
        OK: 27 features with origin, 32 decisions, 69 requirement rows
[PASS] 01-missing-origin -> exit 1
          - F-005: missing or invalid origin 'None' (valid: spec, interpretation, own)
[PASS] 02-invalid-origin -> exit 1
          - F-005: missing or invalid origin 'foo' (valid: spec, interpretation, own)
[PASS] 03-nonspec-without-decisions -> exit 1
          - F-021: origin 'interpretation' requires a non-empty 'decisions' list (register the decision in docs/decisions.md)
          - docs/decisions.md: DP-013 is not referenced by any feature in feature_list.json (orphan decision)
          - docs/decisions.md: DP-014 is not referenced by any feature in feature_list.json (orphan decision)
          - docs/decisions.md: DP-015 is not referenced by any feature in feature_list.json (orphan decision)
[PASS] 04-unknown-dp-reference -> exit 1
          - F-021: references DP-099, which does not exist in docs/decisions.md
[PASS] 05-orphan-dp -> exit 1
          - docs/decisions.md: DP-001 is not referenced by any feature in feature_list.json (orphan decision)
[PASS] 06-orphan-but-replaced-is-allowed -> exit 0
        OK: 27 features with origin, 32 decisions, 69 requirement rows
[PASS] 07-replaced-by-unknown -> exit 1
          - docs/decisions.md: DP-001 is replaced by DP-777, which does not exist
[PASS] 08-duplicate-dp-id -> exit 1
          - docs/decisions.md: duplicate decision id DP-001
          - docs/decisions.md: index table and '### DP-NNN' entries differ: only in index ['DP-002'], only in entries []
          - docs/decisions.md: index row of DP-001 ('Extra propio' / 'Vigente') does not match its entry ('Interpretación del enunciado' / 'Vigente')
          - F-019: references DP-002, which does not exist in docs/decisions.md
          - F-023: references DP-002, which does not exist in docs/decisions.md
          - docs/requirements.md: row SEG-2 references DP-002, which does not exist in docs/decisions.md
          - docs/requirements.md: row SEG-3 references DP-002, which does not exist in docs/decisions.md
[PASS] 09-malformed-dp-heading -> exit 1
          - docs/decisions.md: malformed decision id in heading '### DP-2: Idempotency-Key obligatoria, de 16 a 128 caracteres y con un conjunto de caracteres seguro' (expected DP-NNN, three digits)
          - docs/decisions.md: index table and '### DP-NNN' entries differ: only in index ['DP-002'], only in entries []
          - F-019: references DP-002, which does not exist in docs/decisions.md
          - F-023: references DP-002, which does not exist in docs/decisions.md
          - docs/requirements.md: row SEG-2 references DP-002, which does not exist in docs/decisions.md
          - docs/requirements.md: row SEG-3 references DP-002, which does not exist in docs/decisions.md
[PASS] 10-malformed-dp-in-feature -> exit 1
          - F-023: malformed decision id 'DP-1' (expected DP-NNN, three digits)
[PASS] 11-invalid-dp-tipo -> exit 1
          - docs/decisions.md: DP-003 has invalid or missing Tipo 'Otro' (valid: ['Extra propio', 'Interpretación del enunciado'])
          - docs/decisions.md: index row of DP-003 ('Interpretación del enunciado' / 'Vigente') does not match its entry ('Otro' / 'Vigente')
[PASS] 12-index-out-of-sync -> exit 1
          - docs/decisions.md: index table and '### DP-NNN' entries differ: only in index [], only in entries ['DP-010']
[PASS] 13-req-invalid-status -> exit 1
          - docs/requirements.md: row RF-5 has invalid status 'Hecho' (valid: ['Cumplido', 'Cumplido con interpretación', 'Parcial', 'Solo diseño (sin despliegue)', 'No cumplido'])
          - docs/requirements.md: coverage summary says Cumplido=45 but the matrix has 44
[PASS] 14-req-interpretation-without-dp -> exit 1
          - docs/requirements.md: row RF-3 is 'Cumplido con interpretación' but links no DP-NNN
[PASS] 15-req-unknown-dp -> exit 1
          - docs/requirements.md: row RF-2 references DP-555, which does not exist in docs/decisions.md
[PASS] 16-req-summary-mismatch -> exit 1
          - docs/requirements.md: coverage summary says Cumplido=999 but the matrix has 45
[PASS] 17-req-duplicate-row -> exit 1
          - docs/requirements.md: duplicate row id RF-1
          - docs/requirements.md: coverage summary says Cumplido=45 but the matrix has 46
[PASS] 18-req-wrong-column-count -> exit 1
          - docs/requirements.md: row RF-4 has 7 columns, expected 6 (ID | Requisito | Estado | DP | Dónde | Evidencia)
          - docs/requirements.md: coverage summary says Cumplido con interpretación=17 but the matrix has 16
          - docs/requirements.md: coverage summary says Total=69 but the matrix has 68
[PASS] 19-bootstrap-no-gradlew -> exit 0
        OK: 27 features with origin, 32 decisions, 69 requirement rows
        BOOTSTRAP: no ./gradlew yet (feature F-001 pending). Skipping build.
[PASS] 20-full-init-fails-before-gradle -> exit 1 (gradle not invoked)
```

Notas: el control 06 es **positivo** (un DP huérfano pero `Reemplazada por DP-016` es válido: exit 0); el 19 prueba que sin `./gradlew` se conserva el `BOOTSTRAP` y el exit 0; el 20 prueba que el `./init.sh` completo falla con exit 1 **antes de invocar Gradle** (un `gradlew` falso que imprime `GRADLE-WAS-RUN` no llegó a ejecutarse).

## Verificadores de enlaces, anclas y rutas

- `verify_md.py` (scratch): sobre todo el markdown tocado (`README.md`, `AGENTS.md`, `CHECKPOINTS.md`, `docs/requirements.md`, `docs/decisions.md`, `docs/security.md`, `docs/verification.md`, `docs/architecture.md`, `docs/conventions.md`, `.github/pull_request_template.md`): cada enlace relativo resuelve a un archivo existente y cada ancla `#...` coincide con el *slug* estilo GitHub de un encabezado del destino (se ignoran bloques de código).

```
links checked: 722, broken: 0
```

- `verify_paths.py` (scratch): en `docs/requirements.md` y `docs/decisions.md`, todo token en backticks que parezca ruta (contiene `/` o termina en `.java/.md/.yml/.json/.sh/.kts/.toml/.lockfile`) existe (relativo a la raíz o a `docs/`), todo destino de enlace existe, y todo token CamelCase es una clase real de `src/main` o `src/test` (o una palabra de una lista explícita de términos externos). Excepciones declaradas: `docker-compose.override.yml` (archivo del usuario, no versionado), `ghcr.io/alhucave/ticketflow` (nombre de imagen), rutas bajo `build/` (generadas) y este informe (`progress/impl_spec-traceability.md`). Además, el generador de los documentos comprobó que cada `{{Clase#método}}` citado existe en el archivo de la clase.

```
path/class tokens checked: 1077, hard failures: 0
```

## Comprobaciones puntuales contra el código y la configuración (48, todas PASS)

Script `claims.py` (scratch): cada línea contrasta una afirmación de los dos documentos con el archivo real.

```
PASS DP-001 application.yml: ticketflow.orders.max-quantity: 10
PASS DP-001 OrderController @Value default :10 and rejects < 1
PASS DP-001 compose does not forward TICKETFLOW_ORDERS_MAX_QUANTITY
PASS DP-002 OrderMapper KEY_MIN_LENGTH = 16 and charset [A-Za-z0-9._:-]+
PASS DP-002 IdempotencyKey.MAX_LENGTH = 128
PASS DP-003 OrderId: SHA-256, prefixes ticketflow:order: / ticketflow:complimentary:, v5 layout (0x50)
PASS DP-016..018 RateLimitProperties defaults 20/1/5/0.05/10000/15m/false/true
PASS DP-016 rate limited routes: POST /orders, /events, /events/{id}/complimentary only
PASS DP-015 AdminKeyGuard: constant-time MessageDigest.isEqual over SHA-256; unset key -> disabled()
PASS DP-015 AdminKeyWebFilter ADMIN_ROUTES = /events/{id}/complimentary; header X-Admin-Key
PASS DP-015 application.yml admin api-key defaults to empty (${ADMIN_API_KEY:})
PASS DP-019 application.yml max-in-memory-size: 32KB
PASS DP-020 CreateEventRequest MAX_CAPACITY = 1_000_000, name/venue @Size(max = 200)
PASS DP-020 IssueComplimentaryCommand MAX_QUANTITY = 1000, MAX_REASON_LENGTH = 200
PASS DP-020 PathIds [A-Za-z0-9._-]{1,64}
PASS DP-006 UseCaseConfig ttl default PT10M; DP-011 poll-interval default 1s
PASS DP-006 reservation.ttl is NOT in application.yml
PASS DP-006 ExpirationProperties defaults false/PT1M/PT10S/4/500/PT20S
PASS DP-006/012 compose enables expiration and consumer (true), app defaults false
PASS DP-005 expiry boundary: ProcessOrderUseCase !isAfter(now); repository '#expires <= :now'
PASS DP-004 TicketStatus has exactly the 5 spec constants
PASS DP-004 no EXPIRED/FAILED status anywhere in src/main (as an enum constant or status value)
PASS DP-004 audit reasons 'reservation expired' and 'enqueue failed'
PASS DP-009 audit actors: purchase-request, order-processor, reservation-expirer, complimentary-issuance
PASS DP-009 tables events/inventory/orders/order_audit
PASS DP-012 init-queues.sh: DLQ orders-dlq, maxReceiveCount default 3, VisibilityTimeout 30, standard queue (no .fifo)
PASS DP-012 SqsConsumerProperties defaults 10/20s/30s/4/25s/1s/30s
PASS DP-028 compose pins localstack 4.14.0 and dynamodb-local 3.3.1; no :latest
PASS DP-023 management port default 8081 / address 127.0.0.1 / exposure health,info,prometheus
PASS DP-023 liveness=livenessState; readiness=readinessState,dynamodb,sqs
PASS DP-023/026 compose publishes 8080 and 8081 on 127.0.0.1 only
PASS DP-022 six security headers (nosniff, no-store, no-referrer, DENY, CSP, CORP)
PASS DP-026 compose: read_only/cap_drop ALL/no-new-privileges in all 3 services
PASS DP-026 Dockerfile: distroless java25 nonroot, USER nonroot, digests pinned
PASS DP-029 release.yml triggers on tags v*; repo has no tags
PASS DP-029 ci.yml runs ./init.sh with INCLUDE_INTEGRATION=true on PR and push to main
PASS DP-027 dependabot ecosystems: gradle, github-actions, docker; lockAllConfigurations()
PASS DP-011 AvailabilityController: /stream mapping; backoff retry
PASS DP-025 CorrelationId regex [A-Za-z0-9._-]{1,64}
PASS DP-008 Inventory invariant (available+reserved+pendingConfirmation+sold+complimentary = capacity)
PASS RT-1 Java 25 toolchain; RT-2 Spring Boot 4.1.1
PASS EN-3.0 JaCoCo gate: LINE COVEREDRATIO >= 0.90; excludes **/*Application*.class
PASS ET-1 no .block() / Thread.sleep in src/main
PASS RF-4 TicketStatus transitions: AVAILABLE->RESERVED|COMPLIMENTARY, RESERVED->PENDING|AVAILABLE, PENDING->SOLD|AVAILABLE
PASS CTX-9 no reports endpoint: controllers expose only events/orders/availability/complimentary
PASS EN-4 requests/: postman collection + env + run-newman.sh + demo.sh; no insomnia file
PASS feature_list.json: 27 features, every one with origin; non-spec ones with decisions
PASS F-027 stays in_progress and is the only in_progress feature

48/48 checks passed
```

Además, la lista de documentos se contrastó con el PDF: texto re-extraído con `pdftotext -layout` y recorrido sección por sección (contexto, 5 estados, 5 notas generales, objetivo, 7 requisitos funcionales, stack y especificaciones técnicas, entregables 1-5 con sus sub-puntos, seguridad, diagramas y AWS) para derivar los 69 ítems.

## Criterios de aceptación de F-027

- `docs/requirements.md`: hecho (69 ítems, estados honestos, dónde/evidencia con enlaces verificados).
- `docs/decisions.md`: hecho (32 DP, plantilla y reglas, cada una verificada contra código/config/historial).
- Arnés: `origin` en las 27 features + `decisions`; `init.sh` valida mecánicamente con 21 controles negativos/positivos documentados arriba.
- `CHECKPOINTS.md` (C13), `AGENTS.md`, `.github/pull_request_template.md`: hecho.
- README enlaza ambos documentos; todos los enlaces/rutas resuelven; ningún cambio en código de aplicación.

No hay tests nuevos en `src/` porque no hay código de aplicación nuevo (el «test» de esta feature son el validador de `init.sh` y sus controles negativos, que se ejecutan en cada `./init.sh` y en el CI).

## Pendiente

- Revisión (`reviewer`) con C13; en particular, que el líder confirme los `origin` de F-021/F-023 (`interpretation`) y los estados `Parcial` de CTX-3/CTX-9.
- La feature queda `in_progress` en `feature_list.json`; no se hizo push ni PR.

## `git diff main --stat`

```
 .github/pull_request_template.md   |  19 ++
 AGENTS.md                          |  36 +++-
 CHECKPOINTS.md                     |   1 +
 README.md                          |  57 +++---
 docs/architecture.md               |   2 +
 docs/conventions.md                |   1 +
 docs/decisions.md                  | 396 +++++++++++++++++++++++++++++++++++++
 docs/requirements.md               | 156 +++++++++++++++
 docs/security.md                   |   4 +-
 docs/verification.md               |   3 +
 feature_list.json                  | 130 ++++++++++++
 init.sh                            | 166 ++++++++++++++++
 progress/current.md                |  10 +-
 progress/impl_spec-traceability.md | 265 +++++++++++++++++++++++++
 14 files changed, 1212 insertions(+), 34 deletions(-)
```

`git diff main --stat -- src build.gradle.kts Dockerfile docker-compose.yml .github/workflows` no muestra ningún cambio. (La línea de este informe crece unas líneas tras añadir esta sección.)
