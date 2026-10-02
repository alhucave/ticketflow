# Estado actual

Feature en curso: F-007 — event-repository
Plan:
- Excepcion de dominio EventAlreadyExistsException (id duplicado).
- DynamoDbEventRepository (infrastructure.persistence): save con TransactWriteItems (events + inventory, attribute_not_exists), findById (GetItem), findAll (Scan paginado con expand).
- Instant serializado como ISO-8601 string.
- Tests unitarios con mocks (cobertura sin Docker) + IT Testcontainers (dynamodb-local 3.3.1).
- Verificar ./init.sh y INCLUDE_INTEGRATION=true ./init.sh.
