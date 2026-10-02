# Estado actual

Feature en curso: F-008 — inventory-repository
Plan:
- Puerto InventoryRepository con operaciones explicitas reserve/confirmSale/release/issueComplimentary.
- Adaptador DynamoDbInventoryRepository: un UpdateItem condicional por mutacion, incrementa version.
- Retry.backoff solo para errores transitorios; ConditionalCheckFailed -> InsufficientInventory / EventNotFound.
- Renombrar ConcurrentModificationException -> ConcurrentInventoryModificationException.
- Tests unitarios (mock, propiedad de invariante) + IT con Testcontainers (concurrencia real).
