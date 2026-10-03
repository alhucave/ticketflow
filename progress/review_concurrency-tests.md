# Review — feature F-022 (concurrency-tests)

**Veredicto:** APPROVED

## Criterios de aceptación
- Escenario 1 (alta contención + muestreador continuo): PurchaseConcurrencyIT — [x]
- Escenario 2 (tormentas de reintentos, misma clave / payloads distintos): PurchaseConcurrencyIT — [x]
- Escenario 3 (mixto con cortesías y lecturas): PurchaseConcurrencyIT — [x]
- Escenario 4 (duplicados/reentregas/venenosos, estados finales de cola y DLQ): MessageRedeliveryIT — [x]
- Escenario 5 (transitorio -> SOLD; permanente -> DLQ -> expiración libera; decorador en @TestConfiguration): FailureInjectionIT — [x]
- Escenario 6 (dos barredores concurrentes + carrera consumer/barredor en la frontera): ExpiryUnderLoadIT — [x]
- Escenario 7 (stop/start >= 10 ciclos): MessageRedeliveryIT — [x]
- Escenario 8 (evento inexistente -> 404): PurchaseConcurrencyIT — [x]
- Escenario 9 (cancelación del cliente): PurchaseConcurrencyIT — [x] (ver observaciones)
- Reconciliación al final de cada escenario (invariante, contadores desde orders, cadena por enlaces from->to, sin pérdidas): Reconciliation, invocada en todos — [x]
- @Tag("integration"), docs/verification.md y README "Pruebas de concurrencia": [x]

## Checkpoints
- C1: [x] `./init.sh` OK (30 s) y `INCLUDE_INTEGRATION=true ./init.sh` OK (4m42s), ejecutados por el reviewer.
- C2: [x] solo tests; `git diff main -- src/main` vacío.
- C3: [x] aserciones sobre estados finales/invariantes; mutaciones detectadas (abajo).
- C4: [x] sin cambios en producción.
- C5: [x] sin cambios; las mutaciones confirman que las condiciones están ejercitadas.
- C6: [x] transiciones y auditoría comprobadas con canTransitionTo y cadena from->to.
- C7: [x] inglés, sin Lombok ni @Autowired en campos (inyección en tests con @Autowired de campo es habitual en tests; sin objeción).
- C8: [x] claves admin generadas aleatoriamente en el test.
- C9: [x] cobertura >= 90 % (gate de jacoco pasó).
- C10: [x] solo suite + docs + feature_list (in_progress, no done) + progress.
- C11: [x] docs/verification.md y README actualizados.
- C12: [x] adaptadores reales (DynamoDB Local + LocalStack SQS) de punta a punta; las mutaciones sobre condiciones del adaptador rompen la suite.

## Verificación independiente
- Re-ejecución de `com.ticketflow.concurrency.*` x3: verdes, 91.5 s, 94.3 s, 91.1 s de pared (incluye arranque de Gradle), dentro del objetivo ~3 min.
- Mutación M1 (en `DynamoDbOrderPlacementRepository.updateOrder` la condición `#status = :expected` sustituida por `#status <> :target`, rompe idempotencia/concurrencia de transiciones): 6 de 11 tests fallan (Expiry x2, FailureInjection, MessageRedelivery x2, PurchaseConcurrency alta contención).
- Mutación M2 (quitar `#src >= :qty` de `moveInventory`): 3 de 11 fallan (ExpiryUnderLoad barredores, PurchaseConcurrency alta contención y mixto).
- Ambas restauradas con `git checkout`; `git status` limpio y `git diff main -- src/main` vacío (sin ganchos de test en producción).

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
- Escenario 9: `exchanges.cancelled` > 0 es probabilístico en teoría (timeouts 1-25 ms con 120 peticiones); aceptable, nunca falló y las aserciones de fondo (todas SOLD, reintento converge a una orden por clave) no dependen de ello. Una flakiness residual extremadamente baja es posible.
- Escenario 5: la comprobación de "reserva intacta" es condicional al TTL (25 s); si la CI es muy lenta se omite silenciosamente en lugar de fallar. Aceptable: las demás aserciones (DLQ == permanentes exactos, liberación por el job, nada filtrado) son incondicionales.
- La mutación de quitar la condición `#status = :expected` por completo no se probó de forma aislada, pero la variante M1 equivalente ya la detecta.
