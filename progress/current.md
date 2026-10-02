# Estado actual

Feature en curso: F-006 — dynamodb-config-tables (implementada, pendiente de review)
Plan:
- DynamoDbProperties + DynamoDbConfig (clientes async desde propiedades/env, sin valores fijos)
- DynamoDbTables (esquema) + DynamoDbTableProvisioner (idempotente, tablas y GSIs, sin bloqueo)
- Provisioning desactivado por defecto; activado con ticketflow.dynamodb.provisioning-enabled=true (compose lo activa)
- Tests unitarios con mocks + IT con Testcontainers (amazon/dynamodb-local) etiquetado integration
Ultimas completadas: F-001 a F-005 (APPROVED)
