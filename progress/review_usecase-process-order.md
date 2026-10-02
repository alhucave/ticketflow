# Review — feature F-014 (usecase-process-order)

**Veredicto:** APPROVED

## Criterios de aceptación
- Procesar el mismo mensaje dos veces da el mismo estado final y contadores: `ProcessOrderUseCaseIT.execute_sameOrderTwiceSequentially_sameStateAndCounters` (compara inventario y auditoría idénticos), `redeliveredWhileOthersProcessedConcurrently_stillConsistent`; unitario `ProcessOrderUseCaseTest` — [x]
- Reserva expirada no se vende y se libera: `ProcessOrderUseCaseIT.execute_expiredReservation_releasedAndNotSold`, `expiredWhileAlreadyPending_releasesFromPendingCounter` (contadores, estado AVAILABLE, razón, sin SOLD en la auditoría); unitario con bordes de nanosegundo — [x]
- Transiciones auditadas: `execute_purchaseThenProcess_sellsAndMovesCountersAndAudits` (camino AVAILABLE→RESERVED→PENDING→SOLD, actor), tests del adaptador — [x]
- Procesamiento concurrente = una sola venta: `execute_twentyWorkersOnSameOrder_exactlyOneSale` (1 `Sold`, 19 `AlreadyProcessed`, contadores y auditoría exactos) y `thirtyOrdersInParallel` — [x]

## Checkpoints
- C1: [x] `./init.sh` y `INCLUDE_INTEGRATION=true ./init.sh` ejecutados por mí: ambos BUILD SUCCESSFUL / "init.sh OK".
- C2: [x] dominio solo añade el puerto (Mono permitido); caso de uso sin Spring; adaptador en infrastructure.
- C3: [x] ver arriba.
- C4: [x] sin `.block()`/`Thread.sleep` en `src/main`.
- C5: [x] el movimiento de contadores es `SET src=src-:qty, dst=dst+:qty, version=version+:one` con condición `attribute_exists AND src >= :qty`.
- C6: [x] solo transiciones válidas; cada paso audita en la misma transacción; sin estados nuevos; expirada termina AVAILABLE vía `releaseReservation` con razón.
- C7: [x] inglés, sin Lombok, `@Autowired` solo en constructor (patrón ya existente).
- C8: [x]
- C9: [x] cobertura global 99.4% según informe y JaCoCo verification en verde.
- C10: [x] cambios en `DynamoDbOrderPlacementRepository` limitados a visibilidad de helpers y parametrizar el estado destino (sin cambio de comportamiento).
- C11: [x] `docs/architecture.md` actualizado (flujo paso 2).
- C12: [x] IT de punta a punta con adaptadores reales (DynamoDB Local por Testcontainers; compra real -> cola falsa -> proceso). Contrato comprobado: la transacción es [0] orden (condición status+eventId+quantity), [1] audit, [2] inventario; todos los `#names`/`:values` declarados están usados, no hay palabras reservadas sin alias; mapeo de cancelación por índice coherente (orden con precedencia; `ALL_OLD` distingue not-found de conflicto/ inventario insuficiente); el caso de uso trata el conflicto como benigno y relee con consistent read.

## Análisis de carreras (sin hallazgos bloqueantes)
- Doble venta: imposible; `confirmSale` solo lo gana uno (condición de estado en la misma transacción que el contador `pending>=qty`). Venta perdida: si el ganador del paso 1 cae, el mensaje reentregado (o el perdedor ayudante) completa `confirmSale`; si el ganador de confirm pierde frente a un ayudante, el ayudante devuelve `Sold`.
- Reintento tras commit ambiguo: falla la condición de estado -> conflicto -> relectura -> resultado benigno; nunca aplica dos veces.
- Bucle de reintentos: peor caso real 3 lecturas (mark conflict, confirm conflict, SOLD) < `MAX_ATTEMPTS=5`; agotado propaga (SQS reentrega). Errores transitorios propagan.
- Borde de expiración: `!expiresAt.isAfter(now)`; ventana RESERVED válido -> PENDING -> confirm puede cruzar el instante de expiración por milisegundos y vende (decisión documentada, contadores consistentes; si F-017 libera entre ambos pasos, la condición de estado decide un único ganador).
- Camino ayudante en conflicto: sin riesgo de inconsistencia; los pasos son transacciones únicas.
- Inventario inconsistente: `InsufficientInventoryException`/`IllegalStateException` propagan (no se silencian).

## Observaciones no bloqueantes
1. Orden de auditoría: la SK `<ts>#<uuid>` ordena aleatoriamente entradas del mismo instante; con desfase de reloj entre nodos un ayudante podría registrar SOLD con timestamp anterior a PENDING_CONFIRMATION. Cosmético (estado y contadores son correctos). Considerar un contador de secuencia por orden en una feature posterior. Los ITs usan un reloj que avanza 1 ms para evitar flakiness; es legítimo, no hay sleeps.
2. `docs/architecture.md` paso 4 dice `expiresAt < now` y F-014 usa `<=`; alinear en F-017.
3. Los ITs usan DynamoDB Local (no LocalStack); no reproduce `TransactionConflict` real, que queda cubierto por los tests unitarios de reintento del adaptador.
