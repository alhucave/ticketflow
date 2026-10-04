# Estado actual

Feature en curso: F-027 — spec-traceability-and-decisions-register
Plan:
- Matriz de trazabilidad `docs/requirements.md` derivada del PDF original (ítem por ítem, con estado, dónde y evidencia).
- Registro `docs/decisions.md` (DP-NNN) sembrado con las decisiones reales, verificadas contra código e historial.
- `feature_list.json`: campo `origin` en todas las features y `decisions` en las no-`spec`; validación mecánica en `init.sh` (con controles negativos).
- `CHECKPOINTS.md` (C13), `AGENTS.md`, `.github/pull_request_template.md` y enlaces desde README.
- Solo documentación y arnés: ningún cambio en `src/`, build, Dockerfile, compose ni workflows.
