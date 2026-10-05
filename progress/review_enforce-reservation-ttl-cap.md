# Review — feature F-030 (enforce-reservation-ttl-cap)

**Veredicto:** APPROVED

## Criterios de aceptación
- TTL > 10 min rechazado al arrancar con mensaje claro; PT1S..PT10M válidos; defecto intacto; constante nombrada (`Order.MAX_RESERVATION_TTL`) validada en el constructor de `RequestPurchaseUseCase`: `RequestPurchaseUseCaseTest` (frontera PT10M ok, +1 ns / +1 s / PT2H rechazados) y `UseCaseConfigTest` (ApplicationContextRunner: arranca con defecto/PT10M/PT1S; falla con PT10M1S/PT2H nombrando la propiedad) — [x]
- Prueba E2E con el stack real (re-ejecutada por el reviewer con docker-compose, ADMIN_API_KEY aleatoria no versionada, override fuera del repo): defecto => `reservationExpiresAt - createdAt = 0:10:00`; PT15M => la app no arranca, el log dice `ticketflow.reservation.ttl must not exceed PT10M (...), but was PT15M`, localhost:8080 http 000; PT30S (intervalo del job PT5S, consumidor SQS desactivado) => `available 45/reserved 5`, y a los ~45 s la orden pasa a AVAILABLE y la disponibilidad vuelve a 50/0 — [x]
- docs/requirements.md (RF-2), DP-006, tabla de README actualizados — [x]
- Sin cambio de comportamiento con la configuración por defecto (confirmado en E2E y init.sh) — [x]

## Checkpoints (CHECKPOINTS.md)
- C1: [x] `./init.sh` ejecutado por el reviewer: BUILD SUCCESSFUL, `==> init.sh OK`
- C2: [x] constante en domain (solo `java.time`), validación en usecase sin Spring; el fallo de arranque llega por la excepción del factory method de `UseCaseConfig`
- C3: [x] tests de frontera y de contexto reales, no vacíos
- C4: [x] sin bloqueos añadidos
- C5: [x] no toca inventario
- C6: [x] no toca transiciones
- C7: [x] inglés, sin Lombok ni @Autowired
- C8: [x] sin secretos; la clave de la prueba E2E no se versionó (`git status` limpio salvo la rama)
- C9: [x] gate JaCoCo 90% pasa dentro de init.sh
- C10: [x] `git diff main --stat`: README, docs (architecture, decisions, requirements), feature_list.json, progress/impl, Order.java, RequestPurchaseUseCase.java y dos tests. Nada ajeno
- C11: [x] README, architecture (flujo de compra), requirements y DP-006 coinciden con el código
- C12: [x] la feature no añade combinación de puertos; la validación vive en el constructor y el flujo con adaptadores reales sigue cubierto por `RequestPurchaseUseCaseIT` existente y por la prueba E2E de compose
- C13: [x] origin `spec` honesto (el tope es el «máximo 10 minutos» del enunciado), sin DP nuevo, DP-006 y RF-2 actualizados (DP-006 ya no dice «sin tope»); feature_list.json: F-030 `in_progress` (no `done`), sin `decisions` (no requerido para `spec`)

## Otras derivaciones del TTL
`grep` en `src/main`: la única derivación `now.plus(reservationTtl)` es `RequestPurchaseUseCase` (línea ~87), tras la validación del constructor. `UseCaseConfig` es el único origen del valor (`@Value`). Las cortesías usan `expiresAt == createdAt`; `ProcessOrderUseCase` y el job solo comparan con `now`. No hay otra vía que supere el tope.

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
- En `docs/requirements.md` la fila OBJ-1 recibió enlaces de evidencia del tope (aceptable, pero RF-2 ya basta).
- El mensaje con "10 minutes" literal en el texto de error duplica la constante; menor.
- Compose no reenvía `TICKETFLOW_RESERVATION_TTL` (documentado en DP-006); la prueba usó un override fuera del repo.
