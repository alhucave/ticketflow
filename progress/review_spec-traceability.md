# Review — feature F-027 (spec-traceability)

**Veredicto final (ronda 2):** APPROVED

## Ronda 2

Rama `feature/F-027-spec-traceability` (HEAD b2cb3cc). El diff respecto a la ronda 1 toca solo `docs/decisions.md`, `docs/requirements.md`, `feature_list.json` y `progress/`.

| Comprobación | Resultado |
|---|---|
| DP-033 (fecha futura) contra el código | `CreateEventUseCase.java:47-48` lanza `InvalidEventException("Event date must be in the future")` si `startsAt` es nulo o no es posterior a `clock.instant()`. `CreateEventUseCaseTest#execute_dateNotInFuture_failsWithInvalidEvent` (líneas 66-70) cubre igual a ahora, pasada y nula. DP-033 tiene tipo, feature, qué, por qué, cómo quitarla, impacto y verificación. Está en el índice, en `decisions` de F-010 y enlazada en RF-1 (`Cumplido con interpretación`) |
| DP-034 (cobertura de líneas) contra `build.gradle.kts` | `counter = "LINE"`, `value = "COVEREDRATIO"`, `minimum = 0.90`, regla global única (líneas 94-98). Registrada en F-001 y enlazada en EN-3 y EN-3.0, ambas `Cumplido con interpretación` |
| Resumen de cobertura | Recuento propio de las filas: 42 / 20 / 2 Parcial / 5 Solo diseño / 0 No cumplido = 69, coincide con el resumen. El 44/18 que pedí en la ronda 1 solo contemplaba RF-1; con DP-034 son 3 filas, correcto |
| DP-028 | Reclasificada a `Extra propio` (índice y registro) |
| DP-015 | Anota la asimetría: `POST /events` anónimo con solo rate limiting frente a cortesías con `X-Admin-Key` |
| `./init.sh --check-registry` | verde: `27 features with origin, 34 decisions, 69 requirement rows` |
| Controles negativos (copia en scratch) | referencia a `DP-099` inexistente: exit 1 (`F-010: references DP-099, which does not exist`); DP-034 sin referencia: exit 1 (`orphan decision`) |
| Enlaces y anclas en `docs/decisions.md` y `docs/requirements.md` | 243 y 364 enlaces, 0 rotos |
| `./init.sh` (Colima) | verde, exit 0, BUILD SUCCESSFUL 31 s. Sin `INCLUDE_INTEGRATION`: el diff de la ronda 2 no toca nada ejecutable |
| `git diff main --stat` | sin cambios en `src/`, build, Dockerfile, compose ni workflows; árbol limpio |
| `feature_list.json` | F-027 es la única `in_progress` |
| Búsqueda final de decisiones propias omitidas | Revisé todos los `throw` y constantes de límite de `src/main`: el máximo por orden (DP-001), `@Size`/capacidad máxima/longitudes de ids y claves (DP-020), cortesías (DP-013/014), validaciones de propiedades de configuración (límites de DP-016, DP-006 y similares) y reintentos/backoff internos. Todo está registrado o es una guarda técnica de configuración sin restricción de negocio. No encuentro ninguna omitida |

Checkpoints C1-C13: todos `[x]`. C3 y C13, que fallaron en la ronda 1, quedan cubiertos por DP-033 y DP-034.

Observaciones no bloqueantes (sin cambios): la discrepancia README frente a la colección Postman (31/63 frente a 32/65) queda para una feature posterior; el validador no detecta una fila de matriz borrada si se ajusta el resumen a mano (se declara como revisión humana).

---

## Historial ronda 1 (CHANGES_REQUESTED)

Revisión de 1480a18. `./init.sh` y `INCLUDE_INTEGRATION=true ./init.sh` verdes, cobertura del PDF completa (ningún ítem faltante), 28 afirmaciones de `decisions.md` coincidentes con el código y 13 controles negativos del validador correctos. Único bloqueante: faltaba registrar la restricción propia «la fecha del evento debe ser futura» (`CreateEventUseCase.java:47-48`), con su DP, su referencia en `feature_list.json` y la fila RF-1. Observaciones: asimetría de `POST /events` en DP-015, métrica de cobertura sin registrar, DP-028 mal clasificada. Todas resueltas en la ronda 2.
