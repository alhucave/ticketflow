# Informe de implementación: F-029 pin-ci-runner-image (issue #59, DP-036)

## Evidencia: a qué resuelve `ubuntu-latest` hoy (2026-10-04)
Logs "Set up job > Runner Image" (`gh run view <id> --repo alhucave/ticketflow --log`):

| Run | Workflow / job | Runner Image |
|-----|----------------|--------------|
| 37242201369 (PR #F-028, success) | CI / verify | `Image: ubuntu-24.04`, `Version: 20260927.320.1`, provisioner 20260901.588, runner 2.337.0 |
| 37242201376 (success) | Security / Dependency vulnerabilities (Trivy) | `Image: ubuntu-24.04`, provisioner 20260901.588 |
| 37232741364 (main, success) | CI / verify | `Image: ubuntu-24.04`, provisioner 20260901.588 |

README de actions/runner-images (curl, main): la fila `Ubuntu 24.04 | x64 | ubuntu-latest or ubuntu-24.04`; `ubuntu-26.04` existe como etiqueta aparte. Issue actions/runner-images#14748 (creada 2026-09-17), titulo "ubuntu-latest label will use Ubuntu 26.04 in November 2026": migracion gradual **desde 2026-10-19 hasta el 2026-11-19** (la issue de F-029 dice solo "2026-10-19"; en docs se documento el rango real). Politica: maximo 2 imagenes GA + 1 beta; se depreca la mas antigua al salir una nueva.

Conclusion: `ubuntu-24.04` es el equivalente exacto de `ubuntu-latest` hoy.

## Archivos
- `.github/workflows/ci.yml` (1), `release.yml` (1), `security.yml` (3): solo `runs-on: ubuntu-latest` -> `ubuntu-24.04`. Nada mas cambiado.
- `init.sh`: nuevo paso "Validating CI runner images are pinned (no -latest labels)" (python3 stdlib, antes de Gradle, tras el registro) y flag `--check-runners` (sale tras las validaciones rapidas; `--check-registry` sigue saliendo antes).
- `docs/verification.md`: item en la lista de `init.sh` y seccion fechada "Imagen del runner de CI fijada (nota del 2026-10-04)" (por que, como mover, Dependabot no bumpea, fecha de revision, que solo prueba el CI real).
- `README.md` (CI/CD): bullet "Runner fijado". `AGENTS.md`: fila de `init.sh` menciona `--check-runners`.
- `docs/decisions.md`: DP-036 (Extra propio, F-029) + fila de indice. `requirements.md` sin cambios (ninguna fila afectada).
- `feature_list.json`: ya traia F-029 in_progress con DP-036 (sin tocar; NO marcada done). `progress/current.md` actualizado.
- Sin cambios en src/, build, Dockerfile, compose.

## Decisiones de diseno
- Guarda sin libreria YAML (PyYAML no esta en el python3 3.9 del sistema): lee la linea `runs-on` y, si esta vacia, las lineas mas indentadas (lista en bloque o `group:/labels:`). Falla con `-latest` (incluye `ubuntu-latest-4-cores`) y con `${{ }}` ("cannot verify"), y con `runs-on` sin valor. Comentarios `# ...` se ignoran.
- Fecha de revision: atada a la politica de soporte de runner-images (fin de migracion 2026-11-19, luego cada 6 meses o cuando 24.04 figure como deprecada). No se invento una fecha de fin de soporte de 24.04.
- La etiqueta fija no congela el contenido de la imagen (GitHub sigue publicando versiones de ubuntu-24.04); documentado como riesgo en DP-036.

## Validacion
- actionlint (Docker, `rhysd/actionlint:1.7.12`; tag verificado en Docker Hub y release v1.7.12): `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:1.7.12 -color=false` -> sin salida, exit 0. Control del propio actionlint: `runs-on: ubuntu-99.99` en un repo scratch -> `label "ubuntu-99.99" is unknown` (la lista de etiquetas conocidas incluye `ubuntu-24.04`), exit 1.
- Parseo YAML (ruby): ci.yml verify=ubuntu-24.04; release.yml publish=ubuntu-24.04; security.yml dependencies/secrets/image=ubuntu-24.04.
- Controles negativos del guarda (copia en scratchpad `.../scratchpad/nc`, `./init.sh --check-runners`):
  - ubuntu-latest escalar: exit 1 | ubuntu-24.04: exit 0 | `"ubuntu-latest"` entrecomillado: 1 | `'macos-latest' # c`: 1
  - `[self-hosted, ubuntu-latest]`: 1 | `[self-hosted, ubuntu-24.04]`: 0
  - lista en bloque con latest: 1 | lista en bloque fijada: 0
  - `group/labels` con latest: 1 | fijado: 0
  - `${{ matrix.os }}`: 1 (mensaje "cannot verify") | `ubuntu-latest-4-cores`: 1 | `ubuntu-24.04 # latest note`: 0
  - Mensaje real: `.github/workflows/zz.yml:4: floating runner label 'ubuntu-latest'; pin an explicit image such as ubuntu-24.04 (see docs/verification.md, DP-036)`
- Repo real: `./init.sh --check-runners` -> `OK: 5 runs-on entries, none uses a floating label`.
- `./init.sh` completo (una vez, en primer plano): `BUILD SUCCESSFUL in 57s` ... `==> init.sh OK` (sin integracion, como en local por defecto).

## Lo que solo prueba el CI real del PR
Que `verify` (con `INCLUDE_INTEGRATION=true`, Docker/Testcontainers, Java 25) y los jobs Trivy/gitleaks/imagen pasan en el runner `ubuntu-24.04` solicitado por etiqueta explicita, y que el "Runner Image" del log coincide con el de los runs de arriba. actionlint y el guarda solo validan sintaxis y etiqueta, no ejecucion.

## Pendiente
Commit local `[F-029]` (sin push/PR). Estado de F-029 sigue `in_progress`; reviewer -> APPROVED antes de `done`.
