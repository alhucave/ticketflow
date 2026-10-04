# F-028 — stabilize-integration-tests (informe del implementer)

Alcance: solo pruebas (`src/test`, `src/test/resources`) y docs. Nada bajo `src/main` (`git diff --stat -- src/main` vacío).

## 1. Causa raíz: la hipótesis del issue queda REFUTADA

Hipótesis del issue: «un runner cargado superó el timeout por defecto de 5 s de `WebTestClient`». Lo que dicen el log y el artefacto `reports` del run 37128517785 (`gh run download ... -n reports`):

```
HardeningEndToEndIT > securityHeaders_arePresentOnSuccessAndOnEveryKindOfError() FAILED
    java.lang.IllegalStateException at HardeningEndToEndIT.java:339
        Caused by: java.util.concurrent.TimeoutException at HardeningEndToEndIT.java:339
```
`TEST-...HardeningEndToEndIT.xml`:
```
<failure message="java.lang.IllegalStateException: Timeout on blocking read for 30000000000 NANOSECONDS">
  at reactor.core.publisher.Mono.block(Mono.java:1800)
  at ...DefaultWebTestClient$DefaultRequestBodyUriSpec.exchange(DefaultWebTestClient.java:375)
  at ...HardeningEndToEndIT.securityHeaders_arePresentOnSuccessAndOnEveryKindOfError(HardeningEndToEndIT.java:339)
```
- La línea 339 del commit del run (`c526b7e`) es la **última** petición del test: `client.get().uri("/actuator/health").exchange()` (un 404 trivial en el puerto público).
- El plazo que venció fue **30 s, no 5 s** (`30000000000 NANOSECONDS`): `HardeningEndToEndIT` ya tenía `@AutoConfigureWebTestClient(timeout = "30s")` (y otras seis clases también). Solo `EventsApiEndToEndIT` carecía de timeout (sí tenía el exposure de 5 s, pero no fue la que falló).
- El runner no estaba generalmente lento: en el mismo run las otras 11 pruebas de la clase duraron lo mismo que en un run verde (`sqsConsumer_restartedRepeatedly...` 31,36 s vs 31,24 s; el resto de 0,2 a 5 s) y esta prueba normalmente tarda 0,27 s (run verde 37232741364) frente a 30,3 s. Es decir: **una sola petición trivial no recibió respuesta en 30 s**, las 7 anteriores de la misma prueba fueron instantáneas. La clase tardó 79,9 s frente a ~50 s (los 30 s del cuelgue); el job de 8 min contra 6-7 es consistente con esos 30 s más, no con un runner lento.

Conclusión honesta: **no es un problema de plazo por defecto ni de lentitud; es un cuelgue puntual de una petición**, y la causa del cuelgue **no está demostrada**. Subir plazos (lo que pide el issue) evita falsos negativos por lentitud pero **no arregla un cuelgue real** (con 60 s esa petición habría fallado igual, a los 60 s). Esto queda escrito en DP-035 («Impacto y riesgo»).

### Intentos de reproducir el cuelgue (todos sin éxito, para no vender humo)
1. 1500 repeticiones de la prueba completa: interrumpido (muy lento, ~1 s por `createEvent`); sin fallos hasta ahí.
2. Bucle de las 9 peticiones del test (mismo orden, mismos `returnResult(String.class)`) con 10 s de plazo, durante 4 minutos con 12 procesos busy-loop en el host de 10 núcleos: **9489 iteraciones (~85 000 peticiones) sin ningún cuelgue**.
3. Hallazgo colateral reproducido de forma determinista (no explica el fallo del CI, pero es un defecto real de las pruebas): una respuesta con cuerpo que la prueba no lee **no devuelve la conexión al pool**. `GET /events` (lista larga) sin consumir el cuerpo consume una conexión por llamada; con el pool por defecto de reactor-netty (500 conexiones por destino) la llamada 501 falla («Pool#acquire ... pending for more than the configured timeout of 45000ms» y `Timeout on blocking read`); con un pool de 30 falla en la iteración 30. Las respuestas pequeñas (404, 405, 415, 400, 401) no filtran. En el CI el pool de 500 no se agota con una llamada por prueba, así que no es la causa del run fallido; aun así `securityHeaders...` ahora lee el cuerpo (ver §2).

Causa más probable del cuelgue (hipótesis, no probada): una carrera de conexión reutilizada en el cliente de pruebas tras el 401 de `POST /events/{id}/complimentary` (el servidor responde antes de leer el cuerpo de la petición). No pudo reproducirse en ~85 000 peticiones. Si vuelve a ocurrir, el nuevo plazo de 60 s no lo evitará: habrá que capturar un volcado de hilos (`jstack`) del worker durante el cuelgue. Se propone abrir un issue aparte si reaparece.

### Reproducción determinista del modo de fallo de 5 s (lo que sí se pudo demostrar)
`TestTimeoutsTest#defaultWebTestClient_failsWhenTheHandlerAnswersAfterFiveSeconds`: un manejador que responde a los 5,5 s hace fallar a un `WebTestClient` por defecto con `IllegalStateException: Timeout on blocking read for 5000000000 NANOSECONDS` (misma familia de excepción que el CI, plazo distinto). El cliente construido con el helper compartido (`helperBuiltClient_waitsForTheSameSlowHandler`) y los clientes inyectados (`InjectedWebTestClientTimeoutTest`, `InjectedServerWebTestClientTimeoutTest`) esperan y pasan. Control negativo ejecutado: comentada la propiedad `spring.test.webtestclient.timeout`, fallan exactamente esas pruebas con `Timeout on blocking read for 5000000000 NANOSECONDS` (y `TestTimeoutsTest#testProperty_equals...` con `'value' must not be null`); restaurada la propiedad, pasan.

## 2. Mecanismo (verificado en Spring Boot 4.1.1)

En Boot 4 el paquete es `org.springframework.boot.webtestclient.autoconfigure` (módulo `spring-boot-webtestclient`). `WebTestClientAutoConfiguration` crea `SpringBootWebTestClientBuilderCustomizer` con `@ConfigurationProperties("spring.test.webtestclient")` (su `setTimeout` llama a `builder.responseTimeout`), y `@AutoConfigureWebTestClient(timeout=...)` no es más que `@PropertyMapping("spring.test.webtestclient")`. Por tanto la **propiedad de entorno `spring.test.webtestclient.timeout` funciona para todos los clientes inyectados**: `@SpringBootTest(RANDOM_PORT)` (`bindToServer`) y `@WebFluxTest` (`bindToApplicationContext`). No afecta a los que se construyen a mano con `WebTestClient.bindToController(...)`.

Un solo mecanismo en dos patas con una sola fuente de verdad (`TestTimeouts`):
- `src/test/resources/application.properties`: `spring.test.webtestclient.timeout=60s` (clientes inyectados).
- `com.ticketflow.testsupport.TestWebClients.build(...)` (clientes a mano) aplica `TestTimeouts.RESPONSE`.
- `TestTimeoutsTest#testProperty_equalsTheSharedConstant...` falla si propiedad y constante divergen.

`TestTimeouts`: `RESPONSE` 60 s, `WAIT` 90 s, `CONTAINER_STARTUP` 3 min.

Guarda: `TimeoutPolicyGuardTest` (escaneo de fuentes de `src/test/java`, excluido el paquete `testsupport`) falla ante: `@AutoConfigureWebTestClient(...)` con argumentos, `.responseTimeout(`, `WebTestClient.bindTo...` sin `TestWebClients.build(`, `.block|.verify|atMost|withStartupTimeout(Duration.of...`, `Duration WAIT|TIMEOUT = Duration.of...`, contenedor sin `withStartupTimeout(TestTimeouts.CONTAINER_STARTUP)`. Incluye una prueba de que el escáner detecta cada tipo de violación (no pasa en vacío).

Cambio defensivo adicional: `HardeningEndToEndIT.headersOf(...)` lee el cuerpo hasta el final antes de devolver las cabeceras de las 11 respuestas de `securityHeaders...` (las aserciones de cabeceras y estado no cambian).

## 3. Archivos

Nuevos (`src/test/java/com/ticketflow/testsupport/`): `TestTimeouts`, `TestWebClients`, `SlowProbeController`, `TestTimeoutsTest`, `InjectedWebTestClientTimeoutTest`, `InjectedServerWebTestClientTimeoutTest`, `TimeoutPolicyGuardTest`.
Modificados: `src/test/resources/application.properties`; 7 clases `*EndToEndIT` (se quita `timeout = "30s"`); `OrderControllerTest`, `AvailabilityControllerTest`, `EventControllerTest` (clientes a mano vía `TestWebClients.build`); ~33 clases de prueba (plazos locales y literales -> `TestTimeouts`, 14 sitios de contenedores con `withStartupTimeout`); `HardeningEndToEndIT` (`headersOf`); `docs/decisions.md` (DP-035 + fila de índice), `docs/verification.md` (sección «Cómo escribir una prueba de integración o web»), `progress/current.md`. `feature_list.json` ya traía F-028 (sin tocar su `status`).

## 4. Auditoría de plazos en `src/test`

| Lugar | Antes | Decisión |
|-------|-------|----------|
| `WebTestClient` inyectado en 8 `*EndToEndIT` | 30 s en 7 clases, 5 s por defecto en `EventsApiEndToEndIT` | Propiedad compartida (60 s); se quitan los `timeout = "30s"` |
| `WebTestClient` en 5 `@WebFluxTest` (`RateLimit*WebTest`, `ComplimentaryDisabledWebTest`, `ComplimentaryControllerTest`, `ErrorHandlingWebTest`, `CorrelationMdcIsolationTest`) | 5 s por defecto | Cubiertos por la propiedad (probado con `InjectedWebTestClientTimeoutTest`) |
| `WebTestClient.bindToController` (`OrderControllerTest` x3, `AvailabilityControllerTest` x2, `EventControllerTest`) | 5 s por defecto | `TestWebClients.build(...)` |
| `WAIT`/`TIMEOUT` locales en ~20 clases IT (15, 60, 90, 120 s) | números sueltos | alias de `TestTimeouts.WAIT` (90 s); el de 15 s (`DynamoDbOrderRepositoryIT`) era el más ajustado |
| `.block(Duration.ofSeconds(10/20))` en `DynamoDbEventRepositoryIT`, `EventUseCasesIT`, `ObservabilityEndToEndIT`, `ReadinessDown*IT`, `ObservabilityEndpointsTest`, `StructuredLoggingTest` | 10-20 s | `TestTimeouts.WAIT` |
| `.block/.verify(Duration.ofSeconds(1/5))` en pruebas unitarias con mocks (`DependencyHealthIndicatorTest`, `QueueDepthMonitorTest`, `ObservabilityConfigTest`, `DynamoDb*RepositoryTest`, `AvailabilityControllerTest`) | 1-5 s | `TestTimeouts.WAIT` (un GC o un runner cargado también las puede superar) |
| `atMost(Duration.ofSeconds(30))` en `EventsApiEndToEndIT`, `DynamoDbProvisioningIT` | 30 s | `TestTimeouts.WAIT` |
| `verify(Duration.ofSeconds(30))` en `SqsOrderQueuePublisherIT` | 30 s | `TestTimeouts.WAIT` |
| `done.await(10, SECONDS)` en `CorrelationMdcIsolationTest` | 10 s | `TestTimeouts.WAIT` |
| `ConcurrencySupport.WAIT` (90 s) y `pendingAcquireTimeout(60 s)` de su pool | números sueltos | `TestTimeouts.WAIT` / `RESPONSE` |
| Contenedores `GenericContainer`/`LocalStackContainer` (14 clases + `E2eContainers`) | 60 s por defecto de Testcontainers (LocalStack puede tardar más en un runner frío) | `.withStartupTimeout(TestTimeouts.CONTAINER_STARTUP)` (3 min) |
| `PurchaseConcurrencyIT:368` `.timeout(Duration.ofMillis(1 + random...))` | 1-25 ms | **Se deja**: es el cliente que cancela a propósito (parte de lo que se afirma), no un plazo de espera |
| `MessageRedeliveryIT:170` `tryAcquire(5, 200 ms)` | 200 ms | Se deja: muestreo de progreso, no un plazo duro |
| `FailureInjectionIT` `TTL = 25 s` | 25 s | Se deja: es configuración funcional (TTL de reserva); la prueba ya solo juzga el estado de reserva «mientras no haya vencido el TTL» (`earliestExpiry.minusSeconds(2)`) |
| `ProcessOrderUseCaseIT`/`ReleaseExpired...IT`/`ReservationExpiration...IT` `TTL = 10 min`, relojes fijos `NOW` | 10 min | Se deja: tiempo lógico de un reloj inyectado, no de pared |
| `SqsOrderConsumerIT` `await().during(3 s)` | 3 s | Se deja: es una aserción de «se mantiene durante 3 s», no un plazo |
| `Duration.ofMinutes/Seconds` en `ClientRateLimiterTest`, `TokenBucketTest`, `ReservationExpirationScheduler*Test`, `ObservabilityConfigTest` (valores de propiedades) | n/a | Se dejan: datos de entrada o reloj de prueba, no esperas |
| `.block()`/`.verifyComplete()` sin argumento | sin plazo | Se dejan (sin plazo no hay falso negativo por lentitud; un cuelgue lo corta el `timeout` global de CI) |

## 5. Evidencia de estabilidad (ejecución real)

Entorno: macOS (10 núcleos), Colima 4 CPU / 6 GiB, `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. 938 pruebas (929 + 9 nuevas), 0 fallos, JaCoCo líneas 99,5 % (2142 cubiertas / 10 sin cubrir; barrera de 90 % superada: el build incluye `jacocoTestCoverageVerification`).

| Ejecución | Resultado | Duración |
|-----------|-----------|----------|
| `./init.sh` (sin integración) | OK | 59 s |
| `INCLUDE_INTEGRATION=true ./init.sh` #1 | OK | 318 s |
| #2 | OK | 285 s |
| #3 | OK | 318 s |
| #4 | OK | 318 s |
| #5 | OK | 318 s |
| #6, bajo carga artificial de CPU | OK | 486 s (8m5s) |

Carga artificial: 12 procesos `while :; do :; done` en el host (carga media 76 durante la corrida) + un contenedor en el VM de Colima (`f028-cpu-load`, 6 bucles de CPU sobre 4 vCPU). Al terminar se mataron los procesos y se hizo `docker rm -f f028-cpu-load`; verificado sin procesos ni contenedores sobrantes (solo la pila `ticketflow-*` que ya estaba arriba).

Honestidad sobre la evidencia pedida «reproduce el fallo ANTES y pasa DESPUÉS»: el cuelgue original **no se reprodujo** (ni con 85 000 peticiones bajo carga ni con la suite completa bajo carga). Lo reproducido de forma determinista es el modo de fallo del plazo de 5 s (§1), y su corrección con el mecanismo compartido. La carga artificial (corrida #6) pasa con el código nuevo, pero tampoco hay una corrida «antes» con carga que fallase, así que no puede afirmarse que la suite sea inmune al cuelgue observado; solo que ahora no depende de plazos ajustados y que ningún run local falló.

## 6. Salida literal de `./init.sh` (última corrida, `INCLUDE_INTEGRATION=true`, bajo carga)

```
==> Validating feature_list.json
OK: 28 features, in_progress=['F-028']
==> Validating spec traceability and decisions register
OK: 28 features with origin, 35 decisions, 69 requirement rows
==> Integration tests enabled (INCLUDE_INTEGRATION=true)
==> Building and verifying (tests + 90% coverage gate)
...
BUILD SUCCESSFUL in 8m 5s
==> init.sh OK
```

## 7. Pendiente / para el reviewer
- La causa del cuelgue de 30 s no está identificada (ver §1). Recomendación: si reaparece en CI, hacer `jstack` en caliente; el timeout de 60 s solo lo convierte en un fallo más lento.
- Estado de la feature: sigue `in_progress` (no se marca `done`).
