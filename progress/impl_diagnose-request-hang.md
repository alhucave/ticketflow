# F-033 — diagnose-request-hang (informe del implementer)

Alcance: solo pruebas (`src/test`, `src/test/resources`), un cambio de una línea en `.github/workflows/ci.yml` (ruta del artefacto `reports`) y docs. **Nada bajo `src/main`** (`git diff --stat main -- src/main` vacío). Decisión: DP-039 (Extra propio). La feature sigue `in_progress` (no se marca `done`).

## 1. Resultado: la hipótesis NO se reproduce (y no se afirma que el cuelgue esté arreglado)

Hipótesis: un rechazo temprano (401/405/415/413/429) que no consume el cuerpo de la petición deja la conexión keep-alive en un estado en el que la petición siguiente no recibe respuesta.

### Experimento (sockets crudos, aplicación real en un puerto real, sin Docker)
`EarlyRejectionProbe` + `RawHttpConnection` (socket TCP único, control exacto de los bytes y de cuándo se escriben). Por cada variante: una conexión que se reutiliza durante todas las iteraciones (si el servidor la cerrara se contaría); en cada iteración se envía el rechazo y luego un `GET /no/such/route` (404 trivial, como el `GET /actuator/health` del test fallido) en la MISMA conexión, con plazo de 5 s por respuesta. Cada respuesta se valida por estado y por el `X-Correlation-Id` propio (detecta una respuesta de otra petición); también se registran no-respuesta (con los bytes parciales), cierre sin respuesta, errores de análisis y de E/S.

29 variantes (modo «esperar la respuesta» y, cuando procede, «segunda petición encadenada sin esperar»):
- 401 clave de administración ausente / incorrecta con cuerpo JSON; 401 con cuerpo de 10 KB.
- 405 `DELETE /events` con y sin cuerpo.
- 415 `text/plain` con 1 byte y con 10 KB.
- 413 cuerpo de 100 KB (`/events`) y de 1 MB (`/orders`) con `Content-Length` (límite: 32 KB).
- 429 por presupuesto de escritura agotado (`/orders`, y `/events` con 100 KB) y 429 por bloqueo de clave de administración (la clave ni se comprueba).
- Cuerpo que llega DESPUÉS de la respuesta (cabeceras con `Content-Length: 2000`, se lee el 401, luego se envía el cuerpo).
- Cuerpo `chunked` de 64 KB de una vez; `chunked` a goteo después del 401; `chunked` a goteo con 413 a mitad.

| Corrida | Rondas (rechazo + siguiente) | Peticiones | Anomalías | Cierres `Connection: close` | Reconexiones |
|---------|------------------------------|------------|-----------|-----------------------------|--------------|
| `EARLY_REJECTION_ITERATIONS=3000`, sin carga (1 min) | 29 x 3000 = 87 000 | 174 000 | **0** | 0 | 1 por variante (la inicial) |
| Igual, con 14 bucles `while :; do :; done` en un host de 10 núcleos (carga media 31; 2 min 21 s) | 87 000 | 174 000 | **0** | 0 | 1 por variante |
| Cliente Reactor Netty (el de la suite) con `ConnectionProvider.maxConnections(1)`, secuencia 401 con JSON, 405, 415, 413 de 100 KB, 404, `EARLY_REJECTION_ITERATIONS=5000` | 5000 x 5 | 25 000 | **0** | n/a | n/a |

Los bucles de CPU se lanzaron con un script que guarda sus PID en un archivo y se mataron con `kill $(cat pids)` desde `bash`; verificado con `ps` que no queda ninguno. No queda ningún proceso java propio.

### Evidencia de la librería (Reactor Netty 1.3.7 / Spring Boot 4.1.1), no solo números
- Código (`reactor-netty-http-1.3.7-sources.jar`, `HttpTrafficHandler.channelRead`): con la conexión persistente y `pendingResponses == 0` (la respuesta ya salió) el contenido HTTP restante de la petición se descarta y se sigue leyendo: `"Dropped HTTP content, since response has been sent already"`, `ReferenceCountUtil.release(msg); ctx.read();`. Una petición pipelined se encola (`Buffering pipelined HTTP request`) y se sirve al terminar la anterior.
- Ejecución con `LOGGING_LEVEL_REACTOR_NETTY_HTTP_SERVER=DEBUG` (2 iteraciones por variante): 636 apariciones de `Dropped HTTP content, since response has been sent already` (con `widx: 1900`, `8192`... bytes del cuerpo tardío), y el mismo id de conexión (`[adf4d1ae]`, `[adf4d1ae-1]`, `[adf4d1ae-2]`...) con `Increasing/Decreasing pending responses count` alternando 1 y 0 en cada petición: la conexión se reutiliza y cada petición se contesta.
- Conclusión: el servidor descarta el cuerpo sin leer sin almacenarlo (el rechazo sigue ocurriendo antes de que el cuerpo se procese) y mantiene la conexión; no hace falta `Connection: close` ni drenar a mano. Por eso **no se cambió código de producción**.

Lo que NO se pudo hacer: reproducir el cuelgue original (no se ejecutó «50 veces `HardeningEndToEndIT`»: esa petición del enunciado era condicional a que la hipótesis se confirmara). El cuelgue sigue sin explicación; las causas aún posibles son el runner (parada del proceso o de la red durante 30 s), el cliente de pruebas o un fallo de Docker/LocalStack, no descartables con estos datos.

## 2. Instrumentación (caso «no se reproduce»)

- `testsupport/HangDumpExtension`: extensión JUnit 5 autodetectada (`src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension` + `src/test/resources/junit-platform.properties` con `junit.jupiter.extensions.autodetection.enabled=true`). Solo actúa en pruebas con `@Tag("integration")` (los `*EndToEndIT` y las demás ITs). `beforeEach` arma un vigilante (hilo daemon único) que, si la prueba sigue corriendo a los 20 s, escribe el volcado MIENTRAS la prueba está detenida (es el único momento en que existe la evidencia); `afterEach` lo cancela; `handleTestExecutionException` vuelve a volcar si el fallo es un timeout (`TimeoutException` o mensaje con `Timeout` en la cadena de causas) y relanza la excepción intacta. Escribe un aviso `[hang-dump] ...` en stderr.
- `testsupport/HangDumper`: `build/reports/hang-dumps/<id de la prueba saneado>.txt`, secciones anexadas, con cabecera (hora, motivo, id), todos los hilos con pila COMPLETA y monitores (`ThreadInfo.toString` corta a 8 marcos; aquí no), interbloqueos y el estado de los sockets TCP del anfitrión (`ss -tan` en Linux/CI; `netstat -an -p tcp` en macOS) con Recv-Q/Send-Q. Nunca lanza excepciones.
- Estado del pool del cliente HTTP: el pool de Reactor Netty de un `WebTestClient` no es accesible sin activar métricas del `ConnectionProvider`, así que se ve indirectamente por los sockets (conexiones `ESTABLISHED`/`CLOSE_WAIT`, bytes en cola) y por los hilos `reactor-http-nio-*`. Es una limitación asumida y documentada en DP-039.
- Configuración: `HANG_DUMP_THRESHOLD_SECONDS` (20), `HANG_DUMP_DIR` (`build/reports/hang-dumps`) o propiedades `ticketflow.hangdump.*`.
- `ci.yml`: SOLO se añadió la línea `build/reports/hang-dumps` a los `path` del artefacto `reports` (acciones fijadas por SHA, `ubuntu-24.04` y permisos intactos; `./init.sh --check-runners` OK).
- Ruido esperado y observado: en la corrida de integración dejaron volcado dos pruebas legítimamente lentas, `HardeningEndToEndIT.sqsConsumer_restartedRepeatedlyWhileDraining...` (~31 s) y `FailureInjectionIT.transientFailuresAreRedeliveredAndSold...`; un archivo no prueba un cuelgue (documentado en `docs/verification.md`). Tamaño: 180-340 KB por volcado.
- Prueba de que funciona (`HangDumpExtensionTest`, 8 pruebas, sin dejar nada fallido ni lento): umbral de 150 ms y contexto JUnit simulado; un hilo detenido a propósito (`deliberately-stalled-test-thread`, bloqueado en `stalledOnPurpose`) produce un archivo con esa pila completa, la sección de hilos, `none detected` de interbloqueos y la de sockets; una prueba que termina antes del umbral no deja archivo; una sin la etiqueta `integration` nunca se vigila; un fallo con timeout se vuelca y se relanza (`isSameAs`); un fallo sin timeout se relanza sin volcado; la extensión está registrada (`ServiceLoader` y `junit-platform.properties`). `TimeoutPolicyGuardTest` sigue en verde (los nuevos archivos de `testsupport` están exentos; los de `infrastructure/web` no usan plazos literales: `TestTimeouts.WAIT`).
- Muestra real del volcado (corrida de integración): cabecera `===== ... | still running after 20 s: sqsConsumer_restartedRepeatedlyWhileDraining_sellsEveryOrderExactlyOnce() | [engine:junit-jupiter]/[class:...HardeningEndToEndIT]/[method:...] =====`, 238 hilos (`"Test worker"` en `Awaitility ConditionAwaiter.await`, `webflux-http-nio-1`, ...), `none detected` y `$ netstat -an -p tcp` con las conexiones `127.0.0.1.*`.

## 3. Pruebas nuevas que se quedan en la suite (rápidas y deterministas, sin Docker)
- `EarlyRejectionKeepAliveTest` (401, 405, 413, 415, cuerpo tardío, `chunked`), `EarlyRejectionRateLimitKeepAliveTest` (429 de escritura), `EarlyRejectionAdminLockoutKeepAliveTest` (429 de bloqueo de administración), `EarlyRejectionPooledClientTest` (cliente Reactor Netty con una sola conexión). 25 iteraciones por variante por defecto (~2-6 s por clase); `EARLY_REJECTION_ITERATIONS` / `EARLY_REJECTION_DEADLINE_MS` las convierten en el experimento grande. Son pruebas de regresión del comportamiento actual (pasan antes y después porque no hay defecto que corregir).
- Soporte: `EarlyRejectionProbe`, `EarlyRejectionTestSupport`, `testsupport/RawHttpConnection` (con vigilante de escritura de 20 s: un socket crudo no tiene timeout de escritura y un servidor que contesta y deja de leer bloquearía una escritura grande).
- `HangDumpExtensionTest`, `HangDumper`, `HangDumpExtension` (ver §2).

## 4. Archivos
Nuevos: `src/test/java/com/ticketflow/infrastructure/web/{EarlyRejectionProbe,EarlyRejectionTestSupport,EarlyRejectionKeepAliveTest,EarlyRejectionRateLimitKeepAliveTest,EarlyRejectionAdminLockoutKeepAliveTest,EarlyRejectionPooledClientTest}.java`; `src/test/java/com/ticketflow/testsupport/{RawHttpConnection,HangDumper,HangDumpExtension,HangDumpExtensionTest}.java`; `src/test/resources/junit-platform.properties`; `src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension`; este informe.
Modificados: `.github/workflows/ci.yml` (una línea), `docs/decisions.md` (DP-039 y su fila de índice), `docs/verification.md` (sección «Diagnóstico de cuelgues»), `README.md` (artefacto `reports`, fila de solución de problemas, descripción del job), `progress/current.md`. `feature_list.json` ya traía F-033 (sin tocar). `docs/requirements.md` no aplica (no toca ningún ítem del enunciado). No se tocó nada de F-034 (`ApiExceptionHandler`, `TransientFailures`, DP-038).

## 5. Verificación (salida real)
Entorno: macOS, Colima (`DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`).

| Ejecución | Resultado | Pruebas | Fallos | JaCoCo líneas | Duración |
|-----------|-----------|---------|--------|---------------|----------|
| `./init.sh` (sin integración) | OK | 799 | 0 | 99,4 % (2142/13 sin cubrir) | 1 m 30 s |
| `INCLUDE_INTEGRATION=true ./init.sh` #1 | OK | 960 (948 de `main` + 12 nuevas) | 0 | 99,5 % (2145/10) | 5 m 48 s |
| `INCLUDE_INTEGRATION=true ./init.sh` #2 | OK | 960 | 0 | 99,5 % | 5 m 53 s |
| `./init.sh --check-runners` | OK (5 `runs-on`, ninguno flotante) | | | | |

Salida literal del final de cada corrida de integración:
```
OK: 32 features, in_progress=['F-033']
OK: 32 features with origin, 38 decisions, 69 requirement rows
OK: 5 runs-on entries, none uses a floating label
BUILD SUCCESSFUL in 5m 53s
==> init.sh OK
```
(38 decisiones: DP-038 vive en la rama de F-034/PR #69, aún no mezclada; DP-039 se usó explícitamente.)

## 6. Para el reviewer
- Juzgar el `origin: own` (instrumentación y sondeos de calidad; no es del enunciado) y que DP-039 no venda como arreglado lo que no lo está.
- Si el cuelgue reaparece en CI: descargar el artefacto `reports`, abrir `hang-dumps/` y seguir `docs/verification.md#diagnóstico-de-cuelgues-...`.
