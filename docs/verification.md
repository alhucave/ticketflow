# Verificación

Una feature está verificada cuando `./init.sh` termina en verde y cada criterio de `acceptance` tiene al menos un test que lo demuestra.

## `./init.sh`

1. Valida que `feature_list.json` sea JSON válido y que haya como máximo una feature `in_progress`.
   - Valida el registro de trazabilidad (F-027): cada feature tiene `origin` válido (`spec`, `interpretation`, `own`); las no-`spec` listan `decisions`; todo `DP-NNN` referenciado existe en [`decisions.md`](decisions.md); no hay `DP` vigentes sin feature, ni ids duplicados o mal formados; cada fila de la matriz de [`requirements.md`](requirements.md) tiene un estado válido (y `Cumplido con interpretación` enlaza un `DP`) y el resumen de cobertura coincide con el conteo. `./init.sh --check-registry` ejecuta solo esta validación y sale (tarda unas décimas de segundo).
2. Si existe `./gradlew`: ejecuta `./gradlew clean build jacocoTestReport jacocoTestCoverageVerification`.
   - Falla si la cobertura de líneas global baja del **90%**.
3. Si aún no existe `./gradlew` (antes de la feature F-001), solo corre el paso 1 e informa `BOOTSTRAP`.

El validador solo comprueba coherencia mecánica: no puede juzgar si el `origin` de una feature o el estado de una fila de la matriz son honestos (eso lo revisa el reviewer con el checkpoint C13 de [`CHECKPOINTS.md`](../CHECKPOINTS.md)).

## Verificación de la cadena de suministro

No forma parte de `./init.sh` (necesita Docker y red), pero es obligatoria en CI: `.github/workflows/security.yml` (Trivy sobre `gradle.lockfile` y sobre la imagen, gitleaks sobre el historial). `./init.sh` sí compila con el bloqueo de dependencias: tras cambiar una versión, `./gradlew dependencies --write-locks`. Comandos para ejecutar los escaneos en local: `docs/security.md`.

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
