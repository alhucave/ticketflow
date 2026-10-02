# Review — feature F-013 (usecase-order-status)

**Veredicto:** APPROVED

## Criterios de aceptación
- Returns the current status for existing orders: GetOrderStatusUseCaseTest.execute_existingOrder_returnsViewWithStatus (parametrizado sobre los 5 TicketStatus, verifica todos los campos) y GetOrderStatusUseCaseIT (RESERVED con expiración tras compra, PENDING_CONFIRMATION, SOLD, AVAILABLE, COMPLIMENTARY) — [x]
- Unknown order id yields OrderNotFound: GetOrderStatusUseCaseTest.execute_unknownOrder_failsWithOrderNotFound (comprueba tipo y orderId) y GetOrderStatusUseCaseIT.execute_unknownId_failsWithOrderNotFound — [x]
- Unit tests cover each status value: @EnumSource(TicketStatus.class) cubre los cinco valores — [x]
- Spec original (consultar estado en cualquier momento por identificador): cubierto por lo anterior; la lectura es consistente (ver C12) — [x]

## Checkpoints (CHECKPOINTS.md)
- C1: [x] `./init.sh` y `INCLUDE_INTEGRATION=true ./init.sh` (Colima) ejecutados por mí: ambos BUILD SUCCESSFUL, `==> init.sh OK`; GetOrderStatusUseCaseIT: tests=5, skipped=0, failures=0.
- C2: [x] Use case sin anotaciones Spring, solo puertos + Reactor; dominio sin cambios; bean en infrastructure.config.UseCaseConfig.
- C3: [x] Ver criterios.
- C4: [x] Sin `.block()`/`Thread.sleep` en src/main (grep). Los `.block(WAIT)` están solo en el IT (setup).
- C5: [x] N/A: feature de solo lectura, no modifica inventario.
- C6: [x] N/A: no introduce transiciones; el IT usa `transition` real y valida las válidas del modelo.
- C7: [x] Inglés, sin Lombok ni @Autowired; inyección por constructor.
- C8: [x] Sin secretos; credenciales ficticias test/test en el IT.
- C9: [x] Gate JaCoCo 90% pasó en ambas ejecuciones.
- C10: [x] Cambios limitados: use case, view, bean, tests, informe. No se tocó producción de adaptadores.
- C11: [x] No hay cambio de comportamiento documentado que requiera actualizar docs; `docs/architecture.md` no describe esta consulta y no contradice nada.
- C12: [x] Contrato verificado contra el adaptador real:
  - `DynamoDbOrderRepository.findById` (l.122-132) emite Mono vacío si no hay item (`filter(hasItem && !isEmpty)`); el use case lo traduce con `switchIfEmpty` a OrderNotFoundException (compatible).
  - Usa `consistentRead(true)` (fijado por el nuevo test `DynamoDbOrderRepositoryTest.findById_usesConsistentRead`), por lo que una consulta justo después de la compra/transición ve el estado vigente.
  - IT con adaptadores reales (DynamoDB Local): compra vía RequestPurchaseUseCase -> RESERVED con `reservationExpiresAt = now+10m`; transiciones reales RESERVED->PENDING_CONFIRMATION->SOLD; liberación a AVAILABLE; COMPLIMENTARY (save AVAILABLE + transition AVAILABLE->COMPLIMENTARY, válido según el modelo); id desconocido. Cola simulada es aceptable: no interviene en esta feature.

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
- La vista expone `reservationExpiresAt` también en estados finales (SOLD/COMPLIMENTARY); F-019 (REST) puede decidir omitirlo en el DTO.
- La orden COMPLIMENTARY del IT se crea con `save` en AVAILABLE como atajo, ya que aún no existe un caso de uso que la genere.

APPROVED
