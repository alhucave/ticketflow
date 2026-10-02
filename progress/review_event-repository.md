# Review — feature F-007 (event-repository)

**Veredicto:** APPROVED

Commit revisado: 28f3056 (feature/F-007-event-repository). `./init.sh` ejecutado por el reviewer: verde (JaCoCo >= 90% verificado sin Docker). `INCLUDE_INTEGRATION=true ./init.sh` (colima): verde; DynamoDbEventRepositoryIT tests=5 skipped=0 failures=0; DynamoDbEventRepositoryTest tests=8 failures=0.

## Criterios de aceptación
- Creating an event creates its inventory with available = capacity: DynamoDbEventRepositoryTest.save_newEvent_... (captura la transaccion: available=50, capacity=50, version=0, sold=0) y DynamoDbEventRepositoryIT.save_newEvent_createsInventoryWithAvailableEqualToCapacity (lee el item real) — [x]
- findById returns empty Mono for unknown ids: Test.findById_unknownId_completesEmpty e IT.findById_unknownId_completesEmpty — [x]
- Integration test against DynamoDB Local: DynamoDbEventRepositoryIT (Testcontainers amazon/dynamodb-local:3.3.1; round trip con Instant en milisegundos, duplicado rechazado sin sobrescribir, findAll) — [x]

## Checkpoints (CHECKPOINTS.md)
- C1: [x] ambas variantes de init.sh en verde, ejecutadas por mi.
- C2: [x] domain.exception.EventAlreadyExistsException solo importa domain.model; AWS/Spring solo en infrastructure.persistence.
- C3: [x] ver criterios; los tests verifican contenido (condition expression, tabla, contadores, estado final tras duplicado), no solo ausencia de excepcion.
- C4: [x] grep de `.block()` / `Thread.sleep` en src/main sin resultados; `.block(Duration)` solo en el IT para preparar datos. Usa Mono.fromFuture(Supplier) (perezoso) y Mono.expand para paginar el scan.
- C5: [x] el unico cambio de inventario es la creacion inicial, con `attribute_not_exists(eventId)` dentro de TransactWriteItems (atomico con el evento). Duplicado -> TransactionCanceledException con ConditionalCheckFailed -> EventAlreadyExistsException; otras cancelaciones/errores se propagan intactos (probado). El esquema soporta el requisito de inventario dinamico: contadores + `version` (0 inicial) + `capacity`, listos para UpdateItem condicional en F-008.
- C6: [x] no aplica (sin transiciones de estado en esta feature).
- C7: [x] ingles, constructor injection, sin Lombok ni @Autowired.
- C8: [x] solo credenciales ficticias test/test en el IT; sin rutas de maquina ni secretos.
- C9: [x] jacocoTestCoverageVerification pasa en init.sh sin Docker (unit tests con mocks cubren el adaptador).
- C10: [x] cambios limitados al adaptador, su excepcion, tests y metadatos (feature_list.json, progress/).
- C11: [x] README/docs no requieren cambios: el modelo events/inventory ya esta descrito y el adaptador lo respeta.

## Verificaciones adicionales
- Reutiliza DynamoDbTables y el DynamoDbAsyncClient de F-006 (inyeccion por constructor, sin cliente propio).
- Instant serializado como String ISO-8601 y parseado con Instant.parse; round trip probado con milisegundos.
- Spec original (nombre, fecha, venue, capacidad total; inventario actualizado con las compras): evento guarda los cuatro campos; inventario con version para optimistic locking. Cubierto.
- findAll: scan paginado correctamente (probado con dos paginas y tabla vacia).

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
1. `Map.of` en eventItem/inventoryItem y el uso de `Event.venue` obligatorio: si en el futuro venue pasa a opcional, el mapeo fallara con NPE; hoy es coherente con el dominio.
2. findAll usa Scan sin orden garantizado; aceptable para el alcance, considerar GSI/paginacion en la API si crece.
