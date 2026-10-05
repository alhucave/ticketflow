# Review — feature F-033 (diagnose-request-hang)

**Veredicto:** APPROVED

## Criterios de aceptación
- Intento de reproducción dirigido con control exacto del cable (sockets crudos, miles de iteraciones, 401/405/415/413/429, siguiente GET en la misma conexión, afirmando respuesta, y declarando si reproduce): cubierto por `EarlyRejectionProbe` + `EarlyRejection{KeepAlive,RateLimitKeepAlive,AdminLockoutKeepAlive,PooledClient}Test` — [x]. Re-ejecutado por mí con `EARLY_REJECTION_ITERATIONS=1000`: 29 variantes (con y sin pipelining; 401 ausente/incorrecta/10 KB, 405 con/sin cuerpo, 415 1 B/10 KB, 413 100 KB/1 MB, 429 presupuesto/100 KB/bloqueo admin, cuerpo tardío, chunked de una vez/a goteo/413 a goteo): 0 anomalías, 0 `Connection: close`, 1 reconexión (la inicial) por variante, BUILD SUCCESSFUL.
- Si reproduce (causa raíz + fix + test): no aplica, no se reproduce — [x].
- Si NO reproduce: extensión JUnit con volcado de hilos/sockets subida como artefacto de CI y issue documentado honestamente: `HangDumpExtension`/`HangDumper`, `HangDumpExtensionTest` (8 pruebas verdes), `ci.yml` — [x].
- DP-039 registrado, enlazado en `feature_list.json` (`decisions: ["DP-039"]`), `docs/verification.md` explica cómo leer un volcado y el sondeo — [x].

## Verificación por ejecución
- `./init.sh`: verde. `INCLUDE_INTEGRATION=true ./init.sh`: verde, 960 tests, 0 fallos/errores/omitidos (sumados de `build/test-results`). `./init.sh --check-runners`: OK (5 `runs-on`, ninguno flotante; 32 features, 38 decisiones).
- Experimento a escala 1000 + `HangDumpExtensionTest` + `TimeoutPolicyGuardTest`: verdes.
- Las aserciones pueden fallar (mutaciones locales de `EarlyRejectionProbe`, revertidas con `git checkout`): (a) esperar 200 en lugar de 404 en el follow-up -> `EarlyRejectionKeepAliveTest` FAILED; (b) comparar el correlation id esperado con otro valor -> FAILED; (c) no escribir nunca el follow-up con plazo de 300 ms -> FAILED (NO_RESPONSE). Árbol limpio tras las mutaciones.
- `git diff main -- src/main`: vacío (AdminKeyWebFilter y demás intactos). `git diff main --stat`: solo pruebas, recursos de prueba, `ci.yml` (una línea), `docs/decisions.md`, `docs/verification.md`, `README.md`, `feature_list.json`, `progress/*`. Sin piezas de F-034 (ApiExceptionHandler/TransientFailures/DP-038).
- `ci.yml`: el único cambio es `build/reports/hang-dumps` en el `path` del artefacto; SHAs, `ubuntu-24.04` y permisos intactos.
- Extensión: vigilante en un único hilo daemon, solo en `@Tag("integration")`, umbral 20 s (por debajo de los 60 s de `TestTimeouts.RESPONSE`), cancelado en `afterEach`; `handleTestExecutionException` vuelca y relanza `failure` intacta (no oculta la excepción original); `HangDumper` documentado como no lanzador. No añade latencia a pruebas sanas.
- Estado de la feature: `in_progress` (no `done`). DP-039 honesto: "el cuelgue original sigue sin explicación", no afirma arreglo; origin `own` correcto (instrumentación/diagnóstico no exigidos por el enunciado). Docs en español, código/comentarios en inglés (C7 ok).
- Limpieza: sin bucles de carga, sin contenedores (`docker ps` vacío); solo quedan el daemon de Gradle y los java del IDE.

## Checkpoints (CHECKPOINTS.md)
- C1: [x]
- C2: [x] (solo código de prueba, `src/main` intacto)
- C3: [x]
- C4: [x] (sin cambios en producción; `Thread.sleep`/bloqueos solo en soporte de pruebas, exento por `TimeoutPolicyGuardTest`)
- C5: [x] (n/a, sin cambios)
- C6: [x] (n/a)
- C7: [x]
- C8: [x] (claves de prueba ficticias)
- C9: [x] (JaCoCo 99,4-99,5 % según el informe; `./init.sh` verifica el umbral)
- C10: [x]
- C11: [x] (README, verification.md, decisions.md)
- C12: [x] (no se agregan casos de uso; las ITs de punta a punta existentes con Testcontainers corrieron verdes)
- C13: [x] (DP-039 registrada, enlazada, origin honesto; no toca ítems del enunciado)

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
- `HangDumpExtension()` hace `Long.parseLong` del umbral sin proteger: un `HANG_DUMP_THRESHOLD_SECONDS` mal escrito fallaría al instanciar la extensión (solo afecta a la configuración manual).
- El cuelgue original queda sin explicar; el artefacto `reports` deberá revisarse si reaparece.
