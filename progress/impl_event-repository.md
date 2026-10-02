# Informe F-007 — event-repository

## Archivos
Creados:
- src/main/java/com/ticketflow/domain/exception/EventAlreadyExistsException.java
- src/main/java/com/ticketflow/infrastructure/persistence/DynamoDbEventRepository.java
- src/test/java/com/ticketflow/infrastructure/persistence/DynamoDbEventRepositoryTest.java (mocks, sin Docker)
- src/test/java/com/ticketflow/infrastructure/persistence/DynamoDbEventRepositoryIT.java (Testcontainers, amazon/dynamodb-local:3.3.1)
Modificados: feature_list.json (F-007 in_progress), progress/current.md.

## Decisiones
- `Event` ya tenia `venue` (obligatorio, validado) desde F-005: sin cambios de dominio ni de tests existentes.
- `save`: un `TransactWriteItems` con dos Put (events + inventory inicial: available = capacity, resto 0, version 0), ambos con `attribute_not_exists(eventId)`. Si la transaccion se cancela con razon `ConditionalCheckFailed` se mapea a la excepcion de dominio nueva `EventAlreadyExistsException`; otros errores (throttling, red) se propagan sin cambio.
- `findById`: GetItem con lectura consistente; Mono vacio si no hay item.
- `findAll`: Scan paginado con `Mono.expand` sobre `lastEvaluatedKey` (sin bloqueo, facil de simular con mocks). Sin orden garantizado.
- `startsAt` se guarda como String ISO-8601 (`Instant.toString`), consistente con DynamoDbTables.
- Cliente: `DynamoDbAsyncClient` de F-006 (DynamoDbConfig), inyeccion por constructor; `@Repository`. Sin `.block()` en produccion (solo en tests IT para preparar datos).
- Dominio sigue puro (la excepcion no importa Spring/AWS).

## Salida de ./init.sh
BUILD SUCCESSFUL in 17s ... `==> init.sh OK` (JaCoCo >= 90% verificado).

## Salida de INCLUDE_INTEGRATION=true ./init.sh (colima, DOCKER_HOST + TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE)
BUILD SUCCESSFUL in 24s ... `==> Integration tests enabled`, `==> init.sh OK`. DynamoDbEventRepositoryIT: tests=5 skipped=0 failures=0.

## Acceptance
- available = capacity: unit (captura de la transaccion) + IT (lee inventory).
- findById vacio para ids desconocidos: unit + IT.
- Integracion contra DynamoDB Local: IT (round trip, duplicado rechazado sin sobrescribir, findAll).
