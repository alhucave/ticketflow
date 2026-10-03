# Informe de implementacion: F-017 reservation-expiration-job

Rama: feature/F-017-reservation-expiration-job. Estado en feature_list.json: `in_progress` (no marcada done).

## Archivos

Nuevos (main):
- usecase/ReleaseExpiredReservationsUseCase.java (record Summary: examined, released, skippedConflicts, failed)
- infrastructure/scheduler/ReservationExpirationScheduler.java (SmartLifecycle)
- infrastructure/config/ExpirationProperties.java, ExpirationSchedulerConfig.java (condicional `ticketflow.expiration.enabled=true`)

Modificados (main): UseCaseConfig (bean del caso de uso + `@EnableConfigurationProperties`), DynamoDbOrderRepository (query `<=`, Javadoc), OrderRepository (Javadoc), docker-compose.yml (`TICKETFLOW_EXPIRATION_ENABLED=true`), README.md (seccion nueva), docs/architecture.md (frontera `<=` y flujo del job), progress/current.md.

Tests nuevos: ReleaseExpiredReservationsUseCaseTest (11), ReservationExpirationSchedulerTest (11, VirtualTimeScheduler), ExpirationConfigTest, ReleaseExpiredReservationsUseCaseIT (8, C12), ReservationExpirationSqsEndToEndIT (1, LocalStack + DynamoDB Local). Modificados: DynamoDbOrderRepositoryTest/IT (frontera, `NOW` incluido, `NOW+1ns` excluido), UseCaseConfigTest.

## Decisiones de diseno

- Liberacion por orden con `OrderPlacementRepository.releaseReservation(order, order.status(), "reservation-expirer", "reservation expired", now)` (reusa `ProcessOrderUseCase.EXPIRED_REASON`). Sin estados EXPIRED/FAILED.
- La query de GSI solo da candidatas (eventualmente consistente): la escritura condicionada decide. `OrderStatusConflictException` -> `skippedConflicts` (benigna, log debug). Cualquier otro error por orden (incluidos `OrderNotFoundException` y excepciones sincronas del puerto) -> `failed` (log warn), el barrido continua. Un fallo de la query propaga el error y el scheduler lo registra.
- Un candidato cuyo estado cambio entre lectura y liberacion (RESERVED -> PENDING) termina en conflicto benigno y se recoge en el siguiente barrido.
- `distinct(Order::id)` antes de `take(maxPerSweep)` (una orden que cambia de estado entre las dos queries del repo puede salir bajo ambos estados); `take` cancela la paginacion perezosa; `flatMap(..., concurrency)`. El reloj se lee una vez por barrido.
- Frontera: expirada cuando `expiresAt <= now` en query (`#expires <= :now`; ancho fijo de 9 decimales, exacto al nanosegundo), Javadocs y docs. Tests: `expiresAt == now` expirada, `now - 1ns` no (use case IT y repo IT), y sweeper y ProcessOrderUseCase coinciden en el instante limite.
- Scheduler: bucle "esperar interval, barrer, repetir" (delay fijo entre fin e inicio): sin solapamiento por construccion; sin `.block()`, sin `Thread.sleep`, sin `@Scheduled`. Errores (Mono.error o throw sincrono) se registran y el ciclo sigue. `stop(Runnable)` cancela la espera pero deja terminar el barrido en curso hasta `shutdown-timeout`, luego dispone (cada liberacion es atomica). Timer inyectable (`VirtualTimeScheduler` en tests). Propiedades: enabled=false, interval=PT1M, initial-delay=PT10S, concurrency=4, max-per-sweep=500, shutdown-timeout=PT20S (validadas).
- Aislamiento de los IT: el barrido es global a la tabla, asi que cada test usa una ventana de tiempo propia hacia atras (`BASE - n dias`); las ordenes sobrantes de otros tests nunca estan expiradas para un test posterior, y se pueden afirmar resumenes exactos.

## Cobertura de acceptance / C12 (IT con adaptadores reales)

- Expiradas vuelven a available exactamente una vez; auditoria con actor y reason; RESERVED y PENDING_CONFIRMATION; no expiradas y SOLD intactas (estado y auditoria iguales); segundo barrido idempotente.
- Dos instancias del caso de uso y 4 barridos concurrentes sobre 40 ordenes: suma de released = 40, failed = 0, cada orden con exactamente una auditoria de liberacion, contadores e invariante correctos.
- Carrera barrido vs ProcessOrderUseCase: (a) misma frontera, todas liberadas exactamente una vez (sweeper + processor = 20); (b) reloj del consumer 1ns antes y del sweeper en el limite: cada orden termina con exactamente un desenlace (SOLD xor AVAILABLE), contadores consistentes.
- max-per-sweep en lotes, frontera, y E2E con SQS real: compra -> barrido -> consumer tardio ve AlreadyProcessed, mensaje borrado, cola y DLQ vacias, nada cambia.
- Limitacion: la paginacion real del GSI (>1MB) se prueba solo con mocks (DynamoDbOrderRepositoryTest ya la cubria) y con un Flux perezoso en el caso de uso.

## Verificacion

`./init.sh` (sin Docker): BUILD SUCCESSFUL, "==> init.sh OK" (gate JaCoCo 90% en verde).
```
BUILD SUCCESSFUL in 19s
10 actionable tasks: 10 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.8.0/userguide/configuration_cache_enabling.html
==> init.sh OK
```
`INCLUDE_INTEGRATION=true ./init.sh` (DOCKER_HOST de Colima, TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock): 461 tests, 0 fallos, 0 errores; cobertura de lineas 1179/1184 (99.6%).
```
BUILD SUCCESSFUL in 1m 28s
10 actionable tasks: 10 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.8.0/userguide/configuration_cache_enabling.html
==> init.sh OK
```

docker-compose real: `docker-compose up --build -d`: los 3 servicios healthy; `/actuator/health` -> `{"groups":["liveness","readiness"],"status":"UP"}`; log `ReservationExpirationScheduler : Reservation expiration scheduler started (interval=PT1M, initialDelay=PT10S, concurrency=4, maxPerSweep=500)`; tras >45 s (primer barrido ya ejecutado contra el GSI real) sin lineas ERROR (solo los WARNING de la JVM por acceso nativo de Netty, preexistentes). `docker-compose down -v` ejecutado.
