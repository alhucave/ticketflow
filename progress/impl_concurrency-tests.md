# F-022 concurrency-tests: informe del implementador

## Archivos (todos nuevos salvo indicación)
- `src/test/java/com/ticketflow/concurrency/Reconciliation.java`: comprobación de reconciliación (inventory vs orders vs order_audit).
- `.../ConcurrencySupport.java`: cliente HTTP real (WebClient, pool de 1000 conexiones), muestreador de disponibilidad, helpers de cola, esperas.
- `.../PurchaseConcurrencyIT.java` (escenarios 1, 2, 3, 8, 9), `MessageRedeliveryIT.java` (4, 7), `FailureInjectionIT.java` (5), `ExpiryUnderLoadIT.java` (6).
- Modificados: `docs/verification.md`, `README.md` (sección "Pruebas de concurrencia"), `progress/current.md`, `feature_list.json` (F-022 `in_progress`; NO `done`).
- Sin cambios en código de producción.

## Diseño
- Una clase y un contexto Spring por configuración (consumer/expiración/TTL distintos), cada una con su cola+DLQ; contenedores compartidos vía `E2eContainers`; `@DirtiesContext(AFTER_CLASS)` para que consumers/barredores de una clase no interfieran con otra.
- Reconciliación (siempre al final, en quiescencia): (a) invariante y no negativos; (b) contadores recalculados desde `orders` (scan consistente); (c) cadena de auditoría válida siguiendo enlaces from->to (un solo asiento de creación, sin bifurcaciones ni huérfanas, termina en el estado actual; NO usa el orden de timestamps, limitación documentada en la clase y en docs); (d) órdenes aceptadas existen y, con `exactly`, no hay otras.
- Escenario 1: 300 POST concurrentes, cantidades 1-3 (semilla fija), capacidad 100, muestreador continuo (cada muestra consistente y held <= capacity); solo 202/409 insufficient-inventory; available final < menor cantidad rechazada; todo SOLD.
- Escenario 2: 100 paralelas misma clave (1 orden, 3 auditorías) y 50/50 payloads distintos (un grupo todo 202, el otro todo 409 idempotency-key-reused).
- Escenario 3: 150 compras + 40 cortesías (admin key) + 100 lecturas + muestreador; cortesías nunca vendidas.
- Escenario 4: consumer parado, 30 órdenes reales, 6 copias directas de cada mensaje + 3 venenosos, arranque; una venta por orden (3 auditorías), cola vacía, DLQ == venenosos exactos; segunda oleada de duplicados para órdenes SOLD sin cambios.
- Escenario 5: decorador `@Primary` (en `@TestConfiguration`) del adaptador real `OrderFulfillmentRepository`: fallos transitorios (2 primeros intentos) antes del primer paso y entre pasos -> acaban SOLD; permanentes -> DLQ (10+10), reserva intacta (RESERVED / PENDING_CONFIRMATION, comprobada mientras no venza el TTL de 25 s) y luego liberadas por el job (TTL 25 s, intervalo 1 s); nada filtrado.
- Escenario 6: TTL 4 s, consumer y job apagados; dos casos de uso de barrido concurrentes (barrera) liberan exactamente una vez (suma de `released` == órdenes aceptadas, 2 auditorías por orden), disponibilidad completa, nuevas compras OK; carrera consumer + 2 `ReservationExpirationScheduler` en la frontera de expiración: cada orden exactamente un desenlace terminal (SOLD o AVAILABLE), las ya vencidas al arrancar solo pueden estar AVAILABLE.
- Escenario 7: 120 compras HTTP mientras un hilo hace >= 10 ciclos stop()/start() de consumer y scheduler (ritmo por respuestas, sin sleeps); nada perdido, sin doble venta, cola vacía.
- Escenario 8: evento inexistente -> 404 `event-not-found`, ni orden ni inventario (también 60 en paralelo).
- Escenario 9: 120 compras canceladas por timeout de cliente (1-25 ms); sondas de servidor (filtro + subclase de `RequestPurchaseUseCase` @Primary en `@TestConfiguration`) para saber cuándo termina el trabajo desacoplado; luego todas las órdenes creadas acaban SOLD (una sin mensaje se quedaría RESERVED y fallaría la espera), reintento de todas las claves converge a una orden cada una.
- Determinismo: sin sleeps como aserción; Awaitility con plazos de 90 s; semillas fijas. Concesión conocida: en el escenario 9 el "al menos una cancelación" se afirma sobre una carrera real (probabilístico en teoría, nunca falló); la comprobación de reserva intacta del escenario 5 solo se evalúa mientras el TTL no haya vencido.

## Bugs reales encontrados
Ninguno: todos los escenarios pasaron contra el código de producción sin cambios (tampoco hubo 503 bajo 300 peticiones concurrentes). Prueba de sensibilidad: quitando temporalmente la condición `#src >= :qty` de `moveInventory` (restaurada) la suite falla (2 tests rojos en `PurchaseConcurrencyIT`).

## Verificación
- 5 ejecuciones consecutivas de `com.ticketflow.concurrency.*` (11 tests): todas verdes. Pared por ejecución (incluye arranque de Gradle): 91, 89, 90, 90, 88 s. Tiempo de tests por clase: Expiry ~20 s, Failure ~28 s, Redelivery ~15 s, Purchase ~8 s.
- `./init.sh`: OK (30 s). `INCLUDE_INTEGRATION=true ./init.sh`: OK (4 m 42 s toda la suite de integración, cobertura >= 90 %).
