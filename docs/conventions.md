# Convenciones

## Código

- Inglés para clases, métodos, variables y comentarios.
- DTOs, eventos de mensajería y modelos inmutables como `record`.
- `switch` con pattern matching para estados; sin `if/else` encadenados sobre enums.
- Nada de `.block()` ni `Thread.sleep` en código de producción. Cero llamadas bloqueantes en el camino reactivo.
- Errores de dominio como excepciones tipadas en `domain.exception`; se traducen a HTTP solo en `infrastructure.web` (`@RestControllerAdvice`).
- Reintentos con `Retry.backoff(...)` solo para errores transitorios (throttling de DynamoDB, fallos de red), nunca para errores de negocio.
- Inyección por constructor. Sin `@Autowired` en campos. Sin Lombok.
- Principios SOLID; un puerto por responsabilidad (`EventRepository`, `InventoryRepository`, `OrderRepository`, `OrderQueuePublisher`).
- Logs con SLF4J; nunca registrar secretos ni datos personales.

## Tests

- JUnit 5, Mockito, `reactor-test` (`StepVerifier`), `WebTestClient`.
- Nombres: `methodUnderTest_condition_expectedResult`.
- Un test por criterio de `acceptance`, como mínimo.

## Git

- Ramas: `feature/<id>-<name>` (p. ej. `feature/F-008-inventory-repository`).
- Commits: imperativo, en inglés, prefijo convencional (`feat:`, `fix:`, `test:`, `docs:`, `chore:`).
- Un PR por feature, con `Closes #<issue>` y CI en verde.
- Los commits terminan con la línea `Co-Authored-By` indicada por la configuración.

## Seguridad

- Secretos solo por variables de entorno; `.env` está en `.gitignore`; solo se versiona `.env.example`.
- Las credenciales de LocalStack/DynamoDB Local son ficticias (`test`/`test`).
