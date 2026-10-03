# Review — feature F-016 (sqs-consumer)

**Veredicto:** APPROVED

Commit revisado: 7b702c4 (branch feature/F-016-sqs-consumer). `./init.sh` y `INCLUDE_INTEGRATION=true ./init.sh`
(Colima) ejecutados por el reviewer: ambos `BUILD SUCCESSFUL` / `init.sh OK`.

## Criterios de aceptación
- Borrado solo tras éxito: `SqsOrderConsumerTest.start_validMessage_deletedOnlyAfterUseCaseSucceeds`, `start_everyTerminalResult_isAcknowledged`, `start_useCaseFails_*`, `start_useCaseThrowsSynchronously_*`, `stop_inFlightNeverFinishes_*` (verify never delete) — [x]
- Fallo -> reaparece -> DLQ (IT): `SqsOrderConsumerIT.consumer_alwaysFailingMessage_isRedeliveredThenLandsInDlq...` (LocalStack 4.14.0, 3 intentos, DLQ=1) — [x]
- Concurrencia acotada por configuración: `start_concurrencyConfigured_neverMoreInFlightAndNextPollWaitsForBatch` (máx 3 en vuelo con batch 10), `SqsConsumerConfigTest` — [x]
- Poison no detiene el consumidor: test parametrizado (12 cuerpos + null), `start_poisonMessage_warnsWithoutLeakingPayload`, IT `consumer_poisonMessages_goToDlq...` — [x]

## Búsqueda específica
1. Pérdida de mensajes: el único `deleteMessage` está en `acknowledge`, encadenado tras `useCase.execute` exitoso (`SqsOrderConsumer.java:184-201`). Cancelación/dispose en apagado, fallo de delete, excepción síncrona del caso de uso o de parseo terminan en `onErrorResume` (l.186) sin borrar. `takeUntilOther(stop)` (l.147) está antes de `flatMapMany`, así que solo cancela el long poll, nunca un lote ya recibido. No se encontró ventana de pérdida.
2. Poison: validado a mano (JSON inválido, no objeto, versión ausente/desconocida, orderId ausente/blanco, body null); WARN sin cuerpo; no se borra, SQS lo reentrega y va a DLQ tras maxReceiveCount=3 (init script de compose y SqsTestQueues). Nunca bloquea al resto del lote (error contenido por mensaje en `flatMap`).
3. Parada silenciosa: ReceiveMessage y resolución de URL con `Retry.backoff(Long.MAX_VALUE)` con tope; `retryWhen` externo defensivo; fallo de delete y errores del caso de uso contenidos por mensaje; `subscribe` con error handler que loguea. `waitTime >= 1s` validado, no hay spin. Solo termina tras `stop`.
4. Lotes secuenciales vs throughput: el siguiente poll espera a que acabe el lote (máx min(concurrency, batchSize) en vuelo). Es una decisión consciente y documentada; el test lo asserta. Coste: un mensaje lento retrasa el siguiente poll (ver observaciones).

## Checkpoints (CHECKPOINTS.md)
- C1: [x] ambos `./init.sh` en verde (reviewer)
- C2: [x] dominio/usecase sin cambios; consumer en infrastructure.messaging
- C3: [x] ver criterios
- C4: [x] sin `.block()` ni `Thread.sleep` en `src/main` (grep); tests con VirtualTimeScheduler y Awaitility (los `.block(WAIT)` de tests son aceptables)
- C5: [x] sin cambios de inventario nuevos (el caso de uso F-014 usa conditional writes)
- C6: [x] transiciones auditadas en el IT E2E (AVAILABLE->RESERVED->PENDING_CONFIRMATION->SOLD)
- C7: [x] inglés en código, sin Lombok ni @Autowired
- C8: [x] credenciales ficticias; logs sin payload
- C9: [x] cobertura global ≥ 90% (gate de init.sh verde; informe: 99.5%)
- C10: [x] el refactor del publisher (resolver compartido) y Awaitility son mínimos y justificados
- C11: [x] README (sección Consumidor SQS), compose con flag habilitado
- C12: [x] `OrderProcessingSqsEndToEndIT` con DynamoDB Local 3.3.1 + LocalStack real: compra única, 30 compras sobre capacidad (sold=40, invariante), duplicado -> una sola venta (3 entradas de auditoría). El consumidor invoca `ProcessOrderUseCase.execute(orderId)` real con adaptadores reales; contrato compatible (OrderStatusConflict y errores transitorios se propagan sin borrar; resultados terminales se borran).

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
- `SqsOrderConsumer.java:105-111`/`start()`: reiniciar mientras el bucle anterior aún drena (running vuelve a true) podría dejar dos bucles activos; caso muy improbable (Spring no reinicia así).
- Throughput: con lote de 10 y concurrency 4 los mensajes de la última "ola" esperan invisibles; si el caso de uso fuera lento podrían superar el visibility timeout y duplicarse (seguro por idempotencia). Valorar solapar polls en una feature futura si hace falta.
- `handle` (l.186-190) registra `error.getMessage()` del caso de uso; hoy no contiene payload, pero conviene vigilarlo.
- Poison sin DLQ configurada reentregaría indefinidamente; está cubierto en compose/README con DLQ.
