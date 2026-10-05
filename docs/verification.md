# Verificación

Una feature está verificada cuando `./init.sh` termina en verde y cada criterio de `acceptance` tiene al menos un test que lo demuestra.

## `./init.sh`

1. Valida que `feature_list.json` sea JSON válido y que haya como máximo una feature `in_progress`.
   - Valida el registro de trazabilidad (F-027): cada feature tiene `origin` válido (`spec`, `interpretation`, `own`); las no-`spec` listan `decisions`; todo `DP-NNN` referenciado existe en [`decisions.md`](decisions.md); no hay `DP` vigentes sin feature, ni ids duplicados o mal formados; cada fila de la matriz de [`requirements.md`](requirements.md) tiene un estado válido (y `Cumplido con interpretación` enlaza un `DP`) y el resumen de cobertura coincide con el conteo. `./init.sh --check-registry` ejecuta solo esta validación y sale (tarda unas décimas de segundo).
   - Valida que los runners de CI estén fijados (F-029, [`DP-036`](decisions.md#dp-036-los-runners-de-ci-usan-una-imagen-fijada-no-ubuntu-latest)): falla si algún `.github/workflows/*.yml` usa una etiqueta flotante en `runs-on` (cualquier `*-latest`), o una expresión `${{ ... }}` que no se pueda comprobar. `./init.sh --check-runners` ejecuta solo las validaciones rápidas hasta esa y sale.
2. Si existe `./gradlew`: ejecuta `./gradlew clean build jacocoTestReport jacocoTestCoverageVerification`.
   - Falla si la cobertura de líneas global baja del **90%**.
3. Si aún no existe `./gradlew` (antes de la feature F-001), solo corre el paso 1 e informa `BOOTSTRAP`.

El validador solo comprueba coherencia mecánica: no puede juzgar si el `origin` de una feature o el estado de una fila de la matriz son honestos (eso lo revisa el reviewer con el checkpoint C13 de [`CHECKPOINTS.md`](../CHECKPOINTS.md)).

## Verificación de la cadena de suministro

No forma parte de `./init.sh` (necesita Docker y red), pero es obligatoria en CI: `.github/workflows/security.yml` (Trivy sobre `gradle.lockfile` y sobre la imagen, gitleaks sobre el historial). `./init.sh` sí compila con el bloqueo de dependencias: tras cambiar una versión, `./gradlew dependencies --write-locks`. Comandos para ejecutar los escaneos en local: `docs/security.md`.

## Verificación del release (F-032, DP-040)

`./init.sh` ejecuta [`scripts/test-release-scripts.sh`](../scripts/test-release-scripts.sh): prueba sin red ni token (un `gh` simulado) de las dos puertas de `release.yml` (`require-commit-on-main.sh`, `require-green-verify.sh`). No forma parte de `./init.sh` (necesita Docker o red): `scripts/smoke-image.sh <imagen> [versión] [plazo]` (arranca la imagen sin infraestructura y exige liveness 200 y la versión), `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:1.7.12` (los workflows) y `docker buildx`/`docker build --build-arg APP_VERSION=0.1.0 .`. Lo que solo prueba el run real de un tag: el push a ghcr.io con `GITHUB_TOKEN` (y con qué visibilidad nace el paquete, que no se da por supuesta: [`DP-041`](decisions.md#dp-041-el-repositorio-es-público-protección-de-main-y-ajustes-de-seguridad-restablecidos)), el `digest` de la acción, las tags publicadas y el job `smoke` en el runner (lista de comprobación en el README, «Publicar un release»).

## Imagen del runner de CI fijada (nota del 2026-10-04)

Los tres workflows usan `runs-on: ubuntu-24.04`, no `ubuntu-latest` ([`DP-036`](decisions.md#dp-036-los-runners-de-ci-usan-una-imagen-fijada-no-ubuntu-latest)).

- **Por qué:** GitHub migrará la etiqueta `ubuntu-latest` a Ubuntu 26.04 de forma gradual, a partir del **2026-10-19** y hasta el **2026-11-19** ([runner-images#14748](https://github.com/actions/runner-images/issues/14748), comprobado el 2026-10-04). El 2026-10-04 `ubuntu-latest` aún resolvía a `ubuntu-24.04` (sección «Runner Image» de los logs de CI), por lo que fijarla no cambia nada hoy y evita que el sistema operativo cambie sin un commit.
- **Cómo mover a la siguiente imagen a propósito** (p. ej. `ubuntu-26.04`): (1) cambiar la etiqueta en los **tres** workflows en **un único PR**; (2) dejar correr el CI y comparar «Set up job > Runner Image» (`Image:` y `Version:`) con un run anterior; (3) vigilar los pasos sensibles al sistema: Docker y Testcontainers (`INCLUDE_INTEGRATION=true`), Java 25 de `setup-java`, la imagen y el escaneo de Trivy y gitleaks; (4) actualizar esta nota y `DP-036`. `init.sh` acepta cualquier etiqueta explícita; solo rechaza las flotantes.
- **Dependabot no ayuda aquí:** no actualiza las etiquetas de `runs-on` (sí las acciones y la imagen base del `Dockerfile`), así que el cambio es manual.
- **Fecha de revisión:** GitHub mantiene como máximo dos imágenes GA y empieza a deprecar la más antigua cuando sale una nueva ([política de soporte](https://github.com/actions/runner-images#support-policy)). Con Ubuntu 26.04 ya GA, revise esta fijación cuando termine la migración de `ubuntu-latest` (2026-11-19) y después al menos cada seis meses o cuando el README de runner-images marque `ubuntu-24.04` como deprecada; entonces hay que subirla antes de que el CI falle.
- **Lo que solo prueba el CI real del PR:** que `verify` y los tres jobs de Seguridad pasan en una imagen `ubuntu-24.04` recién solicitada. En local se comprueba la sintaxis (actionlint 1.7.12 en Docker, `rhysd/actionlint:1.7.12`: `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:1.7.12`) y el guarda de `init.sh`, no la ejecución.

## Niveles de prueba

| Nivel | Herramientas | Qué cubre |
|-------|--------------|-----------|
| Unitario de dominio | JUnit 5 | Transiciones de estado, invariantes |
| Unitario de casos de uso | JUnit 5 + Mockito + StepVerifier | Lógica con repositorios simulados |
| Web | `WebTestClient` | Contratos HTTP, códigos de error |
| Integración | Testcontainers (LocalStack) | Adaptadores DynamoDB y SQS reales |
| Caso de uso extremo a extremo | Testcontainers + adaptadores reales | Los casos de uso son compatibles con el comportamiento real de los adaptadores (los mocks no detectan contratos incompatibles) |
| Concurrencia | `StepVerifier` + `Flux.merge`/`parallel` | N compras simultáneas nunca sobrevenden |
| Contrato HTTP de extremo a extremo (manual, F-025) | Newman (Docker) sobre la pila de compose | La colección `requests/` recorre la API real y aserta códigos y cuerpos; no forma parte de `./init.sh` |

Las pruebas de integración se etiquetan `@Tag("integration")` y requieren Docker; en CI se ejecutan siempre: el workflow define `INCLUDE_INTEGRATION=true`, que `init.sh` traduce a `-PincludeIntegration`. En local, `INCLUDE_INTEGRATION=true ./init.sh` las activa (requiere Docker); sin la variable se excluyen.

## Cómo escribir una prueba de integración o web (plazos, DP-035)

Ningún plazo se escribe en la prueba: todos salen de `com.ticketflow.testsupport.TestTimeouts` (`RESPONSE` 60 s, `WAIT` 90 s, `CONTAINER_STARTUP` 3 min).

- **`WebTestClient` inyectado** (`@Autowired`, con `@SpringBootTest(RANDOM_PORT)` + `@AutoConfigureWebTestClient`, o `@WebFluxTest`): ya trae `RESPONSE` por la propiedad `spring.test.webtestclient.timeout` de `src/test/resources/application.properties`. **No** pongas `@AutoConfigureWebTestClient(timeout = ...)`.
- **`WebTestClient` construido a mano**: `TestWebClients.build(WebTestClient.bindToController(...)....)` en lugar de `.build()`.
- **Esperas y bloqueos**: `.block(TestTimeouts.WAIT)`, `.verify(TestTimeouts.WAIT)`, `await().atMost(TestTimeouts.WAIT)`; nunca `Duration.ofSeconds(N)` literal ni un `WAIT` propio con número.
- **Contenedores**: `.withStartupTimeout(TestTimeouts.CONTAINER_STARTUP)`.
- **Cuerpos de respuesta**: si una prueba solo mira cabeceras o estado de una respuesta con cuerpo, léelo hasta el final (como `headersOf` en `HardeningEndToEndIT`) para devolver la conexión al pool.
- `TimeoutPolicyGuardTest` falla si se incumple alguna de estas reglas.

## Diagnóstico de cuelgues: volcados de hilos y sondeo de protocolo (F-033, DP-039)

**Volcado automático.** Toda prueba `@Tag("integration")` que siga corriendo a los 20 s (o falle con un timeout) deja `build/reports/hang-dumps/<id de la prueba>.txt` (en CI, dentro del artefacto `reports`) y escribe `[hang-dump] ...` en la salida de error. Cómo leerlo:

1. **Un archivo no es un cuelgue**: las pruebas lentas pero sanas (p. ej. las que reinician el consumidor, ~31 s) también lo dejan. La cabecera de cada sección dice el motivo: `still running after 20 s` (se escribió MIENTRAS la prueba estaba detenida: es la evidencia valiosa) o `failed with a timeout` (después del fallo).
2. **Hilos** (`--- Threads (full stacks) ---`): busque el hilo de la prueba (`Test worker`) y la línea de su pila en `Mono.block`/`DefaultWebTestClient...exchange`: dice qué petición espera. Luego los hilos `reactor-http-nio-*` (cliente) y `webflux-http-nio-*` (servidor de la aplicación): todos en `epollWait`/`kevent`/`select` y sin trabajo = nadie está procesando nada (el servidor no vio la petición o la dio por contestada); uno bloqueado en un monitor o en `Thread.sleep` = un manejador bloqueó el bucle de eventos. `waiting on ... held by` identifica el dueño de un lock.
3. **Interbloqueos** (`--- Deadlocks ---`): la JVM los detecta sola.
4. **Sockets** (`--- TCP sockets ---`): busque el puerto del servidor de la prueba (`Started ...` en el log, o `LocalServerPort`). `Recv-Q` > 0 en el socket del servidor = bytes recibidos que nadie leyó (la petición llegó y no se procesó); `Send-Q` > 0 en el servidor = respuesta escrita que el cliente no leyó; una conexión en `CLOSE_WAIT`/`FIN_WAIT2` = un extremo cerró y el otro la sigue usando (reutilización de una conexión muerta); nada entre cliente y servidor = el cliente no llegó a conectar (agotamiento del pool o del puerto).
5. Ajustes: `HANG_DUMP_THRESHOLD_SECONDS`, `HANG_DUMP_DIR`. La extensión está en `src/test/java/com/ticketflow/testsupport/` y se autodetecta (`META-INF/services` + `junit-platform.properties`).

**Sondeo de protocolo (rechazos tempranos).** `EarlyRejection*Test` (sin Docker) envían por un socket crudo un rechazo temprano con cuerpo sin leer (401, 405, 413, 415, 429, cuerpo tardío, `chunked`) y a continuación una petición trivial en la MISMA conexión; fallan si la segunda no se contesta en 5 s, si llega con otro estado o con el `X-Correlation-Id` de otra petición, o si la conexión se cierra sin avisar. Por defecto 25 iteraciones por variante; el experimento grande: `EARLY_REJECTION_ITERATIONS=3000 ./gradlew cleanTest test --tests '*EarlyRejection*'` (la variable no es entrada de Gradle: sin `cleanTest` la tarea sale «UP-TO-DATE»). Para ver el tráfico en la librería: `LOGGING_LEVEL_REACTOR_NETTY_HTTP_SERVER=DEBUG` y buscar `Dropped HTTP content, since response has been sent already`. Para ejecutarlo bajo carga artificial, arranque los bucles de CPU guardando sus PID en un archivo y mátelos al terminar (`kill $(cat pids)` desde `bash`; en zsh una variable con varios PID no se divide).

## Cobertura

- Mínimo 90% de líneas (JaCoCo). Se excluyen solo clases de arranque (`*Application`) y configuración trivial.

## Documentación y colección de peticiones (F-025)

Se verifican a mano (no están en `./init.sh`, necesitan la pila de compose y Docker):

- **Diagramas Mermaid** de `docs/architecture.md`: cada bloque ` ```mermaid ` debe renderizar sin errores con `mermaid-cli` (`mmdc`) en Docker; GitHub usa el mismo motor. Un fallo típico es usar una palabra clave no soportada (p. ej. `SK` como clave en `erDiagram`: solo valen `PK`, `FK` y `UK`).
- **Colección de Postman**: con la pila arriba (`ADMIN_API_KEY=<clave> docker-compose up --build -d --wait`), `ADMIN_API_KEY=<clave> ./requests/run-newman.sh` debe terminar con 0 fallos (31 peticiones, 63 aserciones). `./requests/demo.sh` debe terminar con `Demo finished OK`. Ver README, «Colección de peticiones y demo».
- **Clon limpio**: clonar el repositorio en un directorio temporal y seguir el «Inicio rápido» del README literalmente.

## Suite de concurrencia de extremo a extremo (F-022)

Paquete `com.ticketflow.concurrency` (todas `@Tag("integration")`, contexto completo con `RANDOM_PORT`, HTTP real con cientos de peticiones en vuelo, DynamoDB Local 3.3.1 y LocalStack 4.14.0 reales compartidos con `E2eContainers`; cada clase usa su cola y su contexto, que se cierra al terminar para que consumers y barridos no se solapen entre clases):

| Clase | Escenarios |
|-------|-----------|
| `PurchaseConcurrencyIT` | alta contención (300 compras sobre capacidad 100 con un muestreador de disponibilidad), tormentas de reintentos con la misma clave (mismo y distinto payload), compras + cortesías + lecturas, evento inexistente (404 de extremo a extremo), clientes que cancelan a mitad de petición |
| `MessageRedeliveryIT` | mensajes duplicados/reentregados enviados a la cola real, mensajes venenosos a la DLQ real, `stop()`/`start()` repetidos del consumer y del scheduler con mensajes en vuelo |
| `FailureInjectionIT` | fallos transitorios (SQS reentrega y acaban `SOLD`) y permanentes (DLQ, reserva intacta, el job de expiración los libera); el fallo se inyecta con un decorador del adaptador real en un `@TestConfiguration` (sin ganchos en producción) |
| `ExpiryUnderLoadIT` | dos barredores concurrentes liberan cada orden exactamente una vez; carrera consumer vs. barredores en la frontera de expiración (un solo desenlace terminal por orden) |

**Comprobación de reconciliación** (`Reconciliation`, se ejecuta al final de cada escenario, leyendo DynamoDB directamente con lecturas consistentes): (a) `available + reserved + pendingConfirmation + sold + complimentary = capacity` y ningún contador negativo; (b) los contadores recalculados desde la tabla `orders` (suma de cantidades por estado) coinciden con los de `inventory`; (c) la auditoría de cada orden es una cadena válida de transiciones que empieza en su creación y termina en su estado actual; (d) ninguna orden aceptada (202/201) se pierde y, donde aplica, las rechazadas no dejaron orden. **Limitación**: la clave de ordenación de `order_audit` es `<timestamp>#<uuid>`, así que entradas del mismo instante se ordenan al azar y los relojes de distintos nodos pueden desfasarse; por eso la cadena se sigue por los enlaces `from -> to` y nunca por el orden de timestamps.

Determinismo: sin `sleep` como aserción; todo resultado asíncrono se espera con Awaitility (plazos generosos) y se afirma sobre el estado final o sobre invariantes muestreadas; semillas fijas (`Random`). Ejecutar solo la suite: `INCLUDE_INTEGRATION=true ./gradlew -PincludeIntegration test --tests 'com.ticketflow.concurrency.*'` (con Colima: `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` y `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`). Dura del orden de 1,5-2 minutos.
