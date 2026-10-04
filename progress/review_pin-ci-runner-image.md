# Review — feature F-029 (pin-ci-runner-image)

**Veredicto:** APPROVED

## Criterios de aceptación
- Todos los workflows usan `ubuntu-24.04`, verificado como equivalente a `ubuntu-latest` hoy: re-extraído por mí de `gh run view --log` (runs 37242201369, 37242201376, 37232741364: `Image: ubuntu-24.04`, `Version: 20260927.320.1`, provisioner 20260901.588). `git diff main` en `.github/` toca solo las 5 líneas `runs-on` — [x]
- actionlint vía Docker (`rhysd/actionlint:1.7.12`, la etiqueta existe en Docker Hub): exit 0 sin hallazgos, ejecutado por mí — [x]. "verify + Security verdes en el PR": solo demostrable en el CI del PR (aún no existe); queda como condición previa al merge, no al commit.
- Nota fechada cómo/cuándo mover de imagen: `docs/verification.md` (sección 2026-10-04) + README CI/CD — [x]. Fechas de runner-images#14748 comprobadas con curl: creada 2026-09-17, migración del 2026-10-19 al 2026-11-19 (el issue de F-029 solo dice 10-19; la nota documenta el rango real, correcto).
- DP-036 en `docs/decisions.md` (+ fila de índice) y enlazado en `feature_list.json` con `origin: own` — [x]
- `init.sh` falla con etiquetas flotantes, con controles negativos — [x]. Re-ejecutado en copia scratch (`.../scratchpad/rv`): `ubuntu-latest`, `"ubuntu-latest"`, `'macos-latest'`, `[self-hosted, ubuntu-latest]`, `windows-latest`, `ubuntu-latest-4-cores`, `${{ matrix.os }}`, lista en bloque y `group/labels` con latest => exit 1; `ubuntu-24.04` => exit 0; repo real => OK (5 entradas).

## Checkpoints (CHECKPOINTS.md)
- C1: [x] `./init.sh` simple, en primer plano, por mí: BUILD SUCCESSFUL, `init.sh OK`.
- C2: [x] N/A (sin cambios en src).
- C3: [x] cada criterio tiene prueba ejecutable (guarda con controles negativos, actionlint, logs); el criterio de CI verde depende del PR.
- C4: [x] N/A.
- C5: [x] N/A.
- C6: [x] N/A.
- C7: [x] código/comentarios de init.sh en inglés; la doc en español es coherente con el resto del repo.
- C8: [x] sin secretos.
- C9: [x] sin cambios de código; build con jacoco verification en verde.
- C10: [x] `git diff main --stat` toca solo workflows (5 líneas), docs, init.sh, feature_list.json, progress/, README y AGENTS.md. AGENTS.md: una línea de la fila de `init.sh` que menciona `--check-runners`, análoga a la ya existente de `--check-registry`; está justificada y es mínima.
- C11: [x] README, verification.md y AGENTS.md coinciden con el código; `requirements.md` sin cambios, correcto (ningún requisito afectado).
- C12: [x] N/A: no hay caso de uso que combine puertos.
- C13: [x] DP-036 registrado y enlazado; `origin: own` es honesto: es robustez de CI que el enunciado no exige, no una interpretación. `feature_list.json` coherente con el issue (añade un 5.º criterio del guarda, razonable) y F-029 sigue `in_progress` (no `done`).

Workflows por lo demás sin cambios (SHA pins, permisos, concurrency intactos según el diff).

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
- El guarda no detecta etiquetas flotantes tipo `ubuntu-slim`/`*-latest` ocultas en matrices (las rechaza por `${{ }}`), lo cual es conservador y está documentado.
- Antes de marcar `done`: confirmar en el PR que `verify` y los 3 jobs de Security pasan y que "Runner Image" coincide (`ubuntu-24.04`).
