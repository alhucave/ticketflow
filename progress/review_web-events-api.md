# Review — feature F-018 (web-events-api)

**Veredicto:** APPROVED

Commit revisado: cb09150 (rama feature/F-018-web-events-api).

## Criterios de aceptación
- POST /events devuelve 201 con Location: EventControllerTest.create_validBody_returns201WithLocationAndBody y EventsApiEndToEndIT.createGetList_realAdapters_inventoryMatchesCapacity — [x]
- Body inválido devuelve 400 estructurado: EventControllerTest (create_invalidFields_returns400ProblemWithViolations, capacityAboveMax, missingCapacityAndNameTooLong, malformedJson, badInstantFormat, invalidEventFromUseCase) y EventsApiEndToEndIT.create_invalidBody_returns400WithViolations — [x]
- Id desconocido devuelve 404: EventControllerTest.get_unknownId_returns404Problem y EventsApiEndToEndIT.get_unknownId_returns404Problem — [x]
- WebTestClient por endpoint: POST, GET /{id} y GET list en EventControllerTest y en el IT — [x]
- 409 (EventAlreadyExists): EventControllerTest.create_eventAlreadyExists_returns409 — [x]

## Checkpoints
- C1: [x] `./init.sh` y `INCLUDE_INTEGRATION=true ./init.sh` ejecutados por mí (Colima), ambos BUILD SUCCESSFUL / init.sh OK.
- C2: [x] Web solo depende de usecase y domain; casos de uso y dominio sin cambios; sin Spring en domain.
- C3: [x] Ver criterios; los tests comprueban status, cabeceras y cuerpo.
- C4: [x] Sin `.block()` ni `Thread.sleep` en src/main (grep). El controller es 100% reactivo.
- C5: [x] N/A: no cambia inventario.
- C6: [x] N/A: sin transiciones de estado.
- C7: [x] Inglés, sin Lombok, sin `@Autowired` en la capa web nueva (los `@Autowired` existentes en persistence son de constructor y preexistentes).
- C8: [x] Solo credenciales ficticias `test`/`test` en el IT.
- C9: [x] Gate JaCoCo 90% en verde.
- C10: [x] Cambios limitados a web, dependencia validation, README y bookkeeping.
- C11: [x] README con sección Endpoints (rutas, validación, errores, ejemplos curl); architecture.md ya describía infrastructure.web.
- C12: [x] EventsApiEndToEndIT arranca el contexto completo (UseCaseConfig, repositorios DynamoDB reales sobre DynamoDB Local, controller) y verifica que POST -> GET devuelve inventario `available == capacity` (EventRepository.save persiste evento + inventario inicial, contrato compatible con CreateEventUseCase), listado, 404, 400 y dos POST idénticos -> dos eventos distintos (ids generados en servidor). GetEventUseCase pasa por findById e inventario reales.

## Verificaciones específicas
- Sin fuga de internos: validación y malformed usan textos fijos; ServerWebInputException no propaga su mensaje; los `detail` de 400/404/409 provienen de excepciones de dominio redactadas para el cliente (solo incluyen datos que el cliente envió). Sin trazas.
- Límites: `name`/`venue` @NotBlank @Size(max=200); `capacity` @NotNull @Min(1) @Max(1_000_000); `startsAt` @NotNull. Cubiertos en tests de slice.
- 400 ProblemDetail con `violations` ordenadas (application/problem+json); 201 + Location `/events/{id}`; 404; 409.
- Extensibilidad F-020: helper único `problem(...)` y `@RestControllerAdvice` donde añadir handlers 409/410/429/500 y correlation id.

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
1. Una `IllegalArgumentException` del constructor de `Event` u otras excepciones no mapeadas caerían en el 500 por defecto de Spring; queda para F-020 (catch-all).
2. El 409 solo se prueba en el slice (el conflicto real se prueba en DynamoDbEventRepositoryIT); aceptable porque los ids son generados por el servidor.
3. El 404 incluye el id solicitado en `detail`; es dato del propio cliente, sin riesgo.
