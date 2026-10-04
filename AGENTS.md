# AGENTS.md — ticketflow

Plataforma reactiva de procesamiento de eventos de ticketing. Especificación original: "Plataforma de Procesamiento de Eventos de Ticketing" (prueba técnica).

## Stack

Java 25 · Spring Boot 4.x · Spring WebFlux · Gradle (Kotlin DSL) · DynamoDB (Local) · SQS (LocalStack) · Docker / Docker Compose · JUnit 5 + Mockito + reactor-test · JaCoCo (mínimo 90%).

## Mapa del repo

| Ruta | Contenido |
|------|-----------|
| `feature_list.json` | Fuente de verdad de las tareas (una entrada por feature, con `origin` y, si no es `spec`, `decisions`) |
| `progress/` | `current.md`, `history.md`, `impl_<name>.md`, `review_<name>.md` |
| `README.md` | Documento único de entrada: inicio rápido, configuración, API, errores, pruebas, CI/CD, decisiones, troubleshooting |
| `docs/architecture.md` | Capas, diagramas Mermaid (componentes, secuencias, estados, modelo de datos), decisiones |
| `docs/conventions.md` | Estilo, nombres, errores, git |
| `docs/verification.md` | Qué significa "verificado" y cómo se prueba |
| `docs/requirements.md` | Matriz de trazabilidad del enunciado original: cada ítem, su estado, dónde se implementa y su evidencia |
| `docs/decisions.md` | Registro `DP-NNN` de lo que decidimos nosotros y el enunciado no exige (interpretación o extra propio) |
| `CHECKPOINTS.md` | Checklist que aplica el reviewer |
| `requests/` | Colección de Postman + entorno, `run-newman.sh` (Newman en Docker) y `demo.sh` (curl) |
| `init.sh` | Verificación única: la usan implementer, reviewer y CI. Incluye la validación del registro (`./init.sh --check-registry` la ejecuta sola) |
| `.github/pull_request_template.md` | Plantilla de PR con el checklist (incluye el registro de decisiones) |

## Flujo de trabajo

1. `leader` toma una feature `pending` (respetando `depends_on`) y lanza un `implementer`. Al redactar una feature fija su `origin` (`spec`, `interpretation` u `own`) preguntándose: **«¿esto agrega algo que el enunciado no exige?»** (ver [Enunciado vs decisiones propias](#enunciado-vs-decisiones-propias)).
2. `implementer` trabaja en la rama `feature/<id>-<name>`, escribe código + tests, **agrega o actualiza las entradas `DP-NNN` de `docs/decisions.md` y la matriz de `docs/requirements.md`**, corre `./init.sh` y reporta en `progress/impl_<name>.md`.
3. `reviewer` ejecuta `./init.sh` por su cuenta y aplica `CHECKPOINTS.md` (incluido **C13**: registro de decisiones y matriz al día); escribe `progress/review_<name>.md`.
4. Con `APPROVED`: se abre el PR (`Closes #<issue>`), el CI debe estar en verde y se hace merge a `main`. (Con el repositorio privado en plan Free GitHub no impone la protección de `main`: respetar el CI en verde es una disciplina del flujo, no una regla técnica.)
5. El `implementer` marca `done` en `feature_list.json`.

## Reglas globales

- Una sola feature `in_progress` a la vez.
- Código, nombres y comentarios en **inglés**; issues, README y docs en español.
- Nunca commitear secretos. El repositorio es **privado**, pero no es un almacén de secretos (puede clonarse o volver a hacerse público).
- AWS real queda fuera de alcance hasta la fase 9 (solo documentación).

## Enunciado vs decisiones propias

El enunciado original (la prueba técnica) pide unas cosas; muchas otras las decidimos nosotros (límites, endurecimiento, observabilidad, CI...). Y **el usuario seguirá agregando features de criterio propio**. Para mantener explícito qué es del enunciado y qué es nuestro hay dos documentos vivos que el arnés exige:

- [`docs/requirements.md`](docs/requirements.md): lo que el enunciado pide, ítem por ítem, con estado, dónde se implementa y evidencia.
- [`docs/decisions.md`](docs/decisions.md): lo que el enunciado **no** pide (`DP-NNN`), clasificado como *Interpretación del enunciado* o *Extra propio*, con el porqué y cómo cambiarlo o quitarlo.

Quién hace qué:

- **leader**: al redactar una feature fija `origin` en `feature_list.json` (`spec` si solo cumple el enunciado; `interpretation` si el enunciado es ambiguo y elegimos una lectura; `own` si añade algo no pedido) y se pregunta «¿esto agrega algo que el enunciado no exige?». Las features `interpretation` y `own` deben listar sus `decisions`.
- **implementer**: agrega o actualiza las entradas `DP-NNN` y las filas de la matriz que toque su feature, en la misma rama.
- **reviewer**: verifica el checkpoint C13 de `CHECKPOINTS.md` y juzga si el `origin` es honesto (el validador no puede hacerlo).

Cómo agregar una decisión nueva, en 5 pasos:

1. Elija el siguiente `DP-NNN` libre de `docs/decisions.md` (tres dígitos; nunca se reutiliza un id).
2. Copie la plantilla de ese documento al final de la sección «Decisiones» y complete tipo, feature, qué, por qué, cómo configurarla o quitarla, impacto y verificación (con propiedades, valores y clases reales).
3. Añada el id a `decisions` de la feature en `feature_list.json` (y fije su `origin`) y su fila al índice de `docs/decisions.md`.
4. Si interpreta o toca un requisito del enunciado, actualice su fila en `docs/requirements.md` (estado, enlace al `DP`, dónde y evidencia); si añade una propiedad, enlace el `DP` desde la tabla de configuración del README.
5. Corra `./init.sh --check-registry` (o `./init.sh`): falla si falta `origin`, si una feature no-`spec` no tiene `decisions`, si un `DP` referenciado no existe, si un `DP` vigente no lo referencia ninguna feature, si hay ids duplicados o mal formados, o si una fila de la matriz tiene un estado no válido. Para reemplazar una decisión, no se edita la historia: la antigua pasa a `Reemplazada por DP-MMM` y se escribe una nueva.
