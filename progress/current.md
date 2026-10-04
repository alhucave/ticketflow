# Estado actual

F-028 aprobada (pendiente de PR/merge). Hallazgo abierto nuevo: F-033 (issue #63) diagnostico del cuelgue real de 30 s.
Siguientes del plan: F-029 (fijar runner ubuntu-24.04), F-030 (tope TTL 10 min), F-031 (consumidor/expiracion activos por defecto), F-032 (primer release ghcr), F-033 (diagnostico del cuelgue).

## Feature en curso: F-029 - pin-ci-runner-image
Plan:
- Verificar por logs de CI a que resuelve ubuntu-latest hoy (ubuntu-24.04) y fijar los 5 runs-on.
- actionlint 1.7.12 (Docker) + parseo YAML.
- Nota fechada 2026-10-04 en docs/verification.md + README CI/CD.
- Guarda en init.sh (--check-runners) con controles negativos.
- Registrar DP-036.
