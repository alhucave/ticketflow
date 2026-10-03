# AGENTS.md — ticketflow

Plataforma reactiva de procesamiento de eventos de ticketing. Especificación original: "Plataforma de Procesamiento de Eventos de Ticketing" (prueba técnica).

## Stack

Java 25 · Spring Boot 4.x · Spring WebFlux · Gradle (Kotlin DSL) · DynamoDB (Local) · SQS (LocalStack) · Docker / Docker Compose · JUnit 5 + Mockito + reactor-test · JaCoCo (mínimo 90%).

## Mapa del repo

| Ruta | Contenido |
|------|-----------|
| `feature_list.json` | Fuente de verdad de las tareas (una entrada por feature) |
| `progress/` | `current.md`, `history.md`, `impl_<name>.md`, `review_<name>.md` |
| `README.md` | Documento único de entrada: inicio rápido, configuración, API, errores, pruebas, CI/CD, decisiones, troubleshooting |
| `docs/architecture.md` | Capas, diagramas Mermaid (componentes, secuencias, estados, modelo de datos), decisiones |
| `docs/conventions.md` | Estilo, nombres, errores, git |
| `docs/verification.md` | Qué significa "verificado" y cómo se prueba |
| `CHECKPOINTS.md` | Checklist que aplica el reviewer |
| `requests/` | Colección de Postman + entorno, `run-newman.sh` (Newman en Docker) y `demo.sh` (curl) |
| `init.sh` | Verificación única: la usan implementer, reviewer y CI |

## Flujo de trabajo

1. `leader` toma una feature `pending` (respetando `depends_on`) y lanza un `implementer`.
2. `implementer` trabaja en la rama `feature/<id>-<name>`, escribe código + tests, corre `./init.sh` y reporta en `progress/impl_<name>.md`.
3. `reviewer` ejecuta `./init.sh` por su cuenta y aplica `CHECKPOINTS.md`; escribe `progress/review_<name>.md`.
4. Con `APPROVED`: se abre el PR (`Closes #<issue>`), el CI debe estar en verde y se hace merge a `main`.
5. El `implementer` marca `done` en `feature_list.json`.

## Reglas globales

- Una sola feature `in_progress` a la vez.
- Código, nombres y comentarios en **inglés**; issues, README y docs en español.
- Nunca commitear secretos. El repositorio es **público**.
- AWS real queda fuera de alcance hasta la fase 9 (solo documentación).
