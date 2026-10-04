# Review — feature F-028 (stabilize-integration-tests)

**Veredicto:** APPROVED

## Criterios de aceptación
- Causa raíz confirmada desde el log del run fallido y documentada: cubierto por `progress/impl_stabilize-integration-tests.md` §1 y DP-035 — [x]. Verificado de forma independiente: `gh run view 37128517785 --log-failed` muestra `HardeningEndToEndIT.securityHeaders_...` FAILED en `HardeningEndToEndIT.java:339` (TimeoutException). El XML del artefacto `reports` dice `Timeout on blocking read for 30000000000 NANOSECONDS` (30 s, no 5 s), la prueba duró 30,301 s y la clase 79,872 s. En `c526b7e` la clase ya tenía `@AutoConfigureWebTestClient(timeout = "30s")` y la línea 339 es la última petición (`GET /actuator/health`). El diagnóstico del implementer es correcto: la hipótesis de «5 s por defecto» del issue/feature_list queda refutada; fue un cuelgue de una petición, no lentitud generalizada.
- Un único helper/constante para todo `WebTestClient` y auditoría del resto de plazos sin debilitar aserciones: `TestTimeouts`, `TestWebClients`, `spring.test.webtestclient.timeout=60s`, `TimeoutPolicyGuardTest`, `TestTimeoutsTest`, `InjectedWebTestClientTimeoutTest`, `InjectedServerWebTestClientTimeoutTest` — [x]. Mi grep sobre `src/test` (fuera de `testsupport`) no halla `responseTimeout`, `@AutoConfigureWebTestClient(` con argumentos, `.block/.verify(Duration..)`, `atMost(Duration..)` ni `WAIT =` con literal; los `bindToController` pasan todos por `TestWebClients.build`; los 22 ficheros con contenedores tienen `withStartupTimeout`. Los `Duration.ofSeconds` restantes son datos de configuración, relojes virtuales o TTL funcionales (auditados y justificados en el informe §4). Aserciones: el diff de `HardeningEndToEndIT.securityHeaders...` solo cambia cómo se obtienen las cabeceras (`headersOf` lee el cuerpo y devuelve cabeceras); los mismos 11 `expectStatus` y `assertSecurityHeaders` sobre todas siguen intactos.
- Sin cambios de producción: `git diff main --stat -- src/main` vacío — [x].
- Evidencia de estabilidad (≥5 corridas locales verdes + carga artificial que reproduce ANTES y pasa DESPUÉS): parcialmente cumplido, y el informe es honesto al respecto — [x] con reserva. Mis corridas: `./init.sh` plano OK (58 s, JaCoCo 90 % verificado en el build); `INCLUDE_INTEGRATION=true ./init.sh` x3 seguidas OK (4m47s, 5m18s, 5m13s) y x1 con 10 busy-loops de CPU OK (6m30s); procesos de carga terminados y verificados (ninguno queda). Sumadas a las 5 + 1 del implementer, la parte «≥5 verdes» se cumple. La parte «reproduce el fallo ANTES» NO se cumple y el informe lo declara explícitamente (§1 y §5): el cuelgue de 30 s no se reprodujo (~85 000 peticiones bajo carga, suite completa bajo carga), solo se reproduce de forma determinista el modo de fallo de 5 s (`TestTimeoutsTest`, con control negativo: sin la propiedad fallan los tests inyectados con `Timeout ... 5000000000 NANOSECONDS`). El informe no vende humo: dice que la causa del cuelgue no está demostrada, que subir el plazo no lo arregla, y propone `jstack` si reaparece. No repetí el control negativo de la propiedad (no corrí Gradle en paralelo a las corridas), pero los tests que lo prueban existen, comprueban igualdad propiedad/constante y el informe lo documenta.
- Registrado DP-035 en `docs/decisions.md` y enlazado en `feature_list.json` (`origin: own`) — [x]. DP-035 incluye el hallazgo honesto («no era el plazo de 5 s», «un plazo mayor no arregla un cuelgue de verdad»). `docs/verification.md` tiene la guía nueva coherente con el código.

## Checkpoints (CHECKPOINTS.md)
- C1: [x] `./init.sh` plano y 4 corridas con integración, todas verdes (ejecutadas por mí).
- C2: [x] solo código de test.
- C3: [x] ver arriba; el guard tiene prueba de que detecta cada violación (no pasa en vacío).
- C4: [x] sin cambios en producción.
- C5: [x] n/a (sin cambios de inventario).
- C6: [x] n/a.
- C7: [x] comentarios y nombres en inglés; sin Lombok ni `@Autowired` en campo añadidos.
- C8: [x] sin secretos.
- C9: [x] barrera JaCoCo ≥ 90 % pasa en el build (el implementer reporta 99,5 %).
- C10: [x] el alcance se mantiene en plazos de test; `headersOf` leyendo el cuerpo es un cambio defensivo menor y está justificado y documentado.
- C11: [x] DP-035, `docs/verification.md`, `progress/current.md` actualizados.
- C12: [x] n/a: la feature no añade casos de uso; la suite de integración con adaptadores reales existente sigue verde.
- C13: [x] DP-035 registrada y enlazada; `origin: own` honesto (política de plazos añadida por decisión propia, no pedida por el enunciado).

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
1. `feature_list.json` F-028 (`description`, y el criterio «reproduces the failure BEFORE the fix») sigue afirmando la hipótesis de 5 s ya refutada. Conviene que el líder lo corrija al cerrar la feature o deje claro en el PR que ese criterio solo se cumplió para el modo de fallo de 5 s, no para el cuelgue de 30 s del CI.
2. El cuelgue real de 30 s sigue sin causa conocida: este cambio no lo previene (solo evita falsos negativos por lentitud y hace que un cuelgue tarde 60 s en fallar). Abrir issue de seguimiento / capturar `jstack` si reaparece en CI.
3. Estado `in_progress` en `feature_list.json` correcto hasta merge.
