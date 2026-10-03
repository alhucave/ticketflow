# Estado actual

Feature en curso: F-018 — web-events-api
Plan:
- Anadir spring-boot-starter-validation; DTOs record + mappers en infrastructure.web
- EventController (POST/GET/GET list) + RestControllerAdvice extensible en infrastructure.web.error
- Tests WebFluxTest con casos de uso simulados (todos los codigos de estado)
- IT C12: @SpringBootTest con contexto completo + DynamoDB Local real
- README (Endpoints con curl) + verificacion con docker-compose
