# Review — feature F-008 (inventory-repository)

**Veredicto:** APPROVED

## Criterios de aceptación
- reserve fails with InsufficientInventory when available < qty: DynamoDbInventoryRepositoryTest (reserve_availableBelowQuantity_...) y IT (reserve_availableBelowQuantity_..., carreras) — [x]
- Each mutation is one atomic conditional write incrementing version: unit test verifica una sola llamada updateItem con expresiones; IT verifica version == escrituras exitosas bajo 200/300 operaciones paralelas — [x]
- Counter invariant after any sequence (property-style): anyOperationSequence_keepsCounterInvariantAndNeverOversells (50 seeds, contra evaluador en memoria; débil por sí solo) reforzado por IT reales con DynamoDB Local (assertInvariant) — [x]
- Transient throttling retried; business errors not: reserve_throttledThenSucceeds, _serviceUnavailable, _persistentThrottling, _nonTransientError, Insufficient con times(1), isTransient — [x]

## Checkpoints
- C1: [x] ./init.sh y INCLUDE_INTEGRATION=true ./init.sh ejecutados por mí, ambos verdes; IT de inventario repetido, verde.
- C2: [x] domain sin imports Spring/AWS (grep vacío); adaptador en infrastructure.persistence.
- C3: [x] ver arriba.
- C4: [x] sin .block()/sleep en src/main (grep vacío); en tests solo .block(TIMEOUT) en IT, sin sleeps.
- C5: [x] cada mutación = un UpdateItem con `attribute_exists AND #src >= :qty`, `version = version + 1`, sin lectura previa.
- C6: [x] solo transiciones válidas (available->reserved, reserved->sold, reserved->available, available->complimentary). La auditoría por orden corresponde a features posteriores (order_audit).
- C7: [x] inglés, sin Lombok, @Autowired en constructor.
- C8: [x] solo credenciales ficticias test/test.
- C9: [x] gate JaCoCo verde.
- C10: [x] cambios acotados (puerto, adaptador, rename de excepción, tests, docs/feature_list/progress).
- C11: [x] docs/architecture.md actualizado (quita `version = :expected`).

## Verificaciones específicas
- Mapeo ConditionalCheckFailed: item en ALL_OLD -> InsufficientInventory; ausente -> EventNotFound. Correcto y más preciso que la spec.
- Reintentos solo transitorios (throttling, 5xx/503); nunca condición fallida ni 4xx. Condicionado a `.filter(isTransient)`.
- Rename completo: no quedan referencias a ConcurrentModificationException (grep).
- Dominio puro; puerto solo usa Mono (permitido).
- Test de concurrencia real: 200 paralelos contra DynamoDB Local, resultados deterministas (exactamente 50 éxitos), sin sleeps, semillas fijas; no es flaky por construcción.
- No lanzar nunca ConcurrentInventoryModificationException: aceptable. La spec dice "InsufficientInventory o ConcurrentModification"; al no usar expectedVersion, la condición de cantidad es atómica y no hay conflicto de versión que mapear. Es incluso más robusto (sin reintentos por contención). Excepción conservada en dominio.

## Observaciones no bloqueantes
1. docs/conventions.md menciona "fallos de red" como reintentables; el implementer excluye SdkClientException deliberadamente (UpdateItem no idempotente: riesgo de doble reserva). Decisión correcta y documentada; considerar aclararlo en conventions.md.
2. El test de propiedad unitario valida un modelo en memoria, no el código del adaptador; el valor real lo aportan los IT.
3. Hace falta documentar que pendingConfirmation no tiene operaciones aún (F-014 deberá ampliar el puerto).
