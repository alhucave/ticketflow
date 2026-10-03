# Estado actual

Feature en curso: F-022 — concurrency-tests
Plan:
- Soporte reutilizable `com.ticketflow.concurrency.ConcurrencySupport` + `Reconciliation` (cruza inventory/orders/order_audit).
- 4 clases IT (contexto completo, RANDOM_PORT, DynamoDB Local + LocalStack reales): compras concurrentes (1,2,3,8,9), redelivery (4,7), inyeccion de fallos (5), expiracion bajo carga (6).
- Documentar en docs/verification.md y README; informe en progress/impl_concurrency-tests.md.
