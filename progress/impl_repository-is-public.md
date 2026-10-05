# Informe de implementación: F-035 repository-is-public (issue #72)

Solo documentación y registro de decisión. Sin cambios en `src/`, build, Dockerfile, workflows ni scripts. Sin tocar ajustes del repositorio, tags, releases ni push.

## Desviación a revisar por el leader
`feature_list.json`: F-035 quedó con `status: "pending"`, no `in_progress`. F-032 sigue `in_progress` (publicación del release pendiente) y `./init.sh` rechaza dos `in_progress` a la vez (`AssertionError: more than one in_progress: ['F-032', 'F-035']`, reproducido). Para dejar `./init.sh` en verde sin tocar F-032, F-035 se dejó en `pending`; el leader decide cuándo pasarlo a `in_progress`/`done` (p. ej. al cerrar F-032).

## Archivos tocados
README.md, AGENTS.md, SECURITY.md, docs/decisions.md, docs/security.md, docs/aws.md, docs/verification.md, feature_list.json (entrada F-035, DP-041), progress/current.md, progress/impl_repository-is-public.md.

## Cambios
- feature_list.json: F-035 (phase 10, depends_on F-034, issue 72, origin own, decisions DP-041; 041 era el siguiente libre).
- README: inicio rápido (clonar sin cuenta); CI/CD: protección activa con su límite honesto (sin revisiones, `enforce_admins` false, regla no sobrevivió al periodo privado, `verify` es el único check obligatorio), otros ajustes (reporte privado, secret scanning + push protection, aprobación de externos, token de solo lectura, Dependabot security updates desactivado), CI en repo público sin sobreafirmar; «Usar la imagen publicada»: visibilidad del paquete no asumida, a comprobar tras el primer release, `read:packages` solo si es privado; lista de comprobación del release.
- AGENTS.md: nota del paso 4 y regla de secretos (repositorio público).
- SECURITY.md: nota del repo privado sustituida (canal de reporte privado activo, comprobado).
- docs/security.md: contexto, y nueva sección «Repositorio público: protección y ajustes de GitHub» (tabla con valores comprobados y límites); frase de gitleaks.
- docs/aws.md: etiqueta del diagrama y tabla de gobernanza (7.3).
- docs/verification.md: línea sobre qué solo prueba el run real (ya no «desde un repositorio privado»).
- docs/decisions.md: fila e índice de DP-041; DP-030 -> `Reemplazada por DP-041` (índice y campo Estado, contenido conservado y nota de puntero); DP-029 (paquete privado, 7-9 min con repo privado); DP-040 (`mode=min` y paquete privado, ahora «no se da por supuesta»); DP-041 nueva (Extra propio): decisión y fecha, auditoría, exposiciones aceptadas, ajustes con valores, consecuencias.

## Valores de la API (solo lectura, 2026-10-05)
- repo: `private:false`, `visibility:public`; `secret_scanning: enabled`, `secret_scanning_push_protection: enabled`, `dependabot_security_updates: disabled`, `secret_scanning_non_provider_patterns`/`validity_checks: disabled`.
- branches/main/protection: `required_status_checks.contexts:["verify"]`, `strict:true`; `enforce_admins:false`; `required_pull_request_reviews:null`; `allow_force_pushes:false`; `allow_deletions:false`.
- private-vulnerability-reporting: `enabled:true`.
- fork-pr-contributor-approval: `approval_policy: all_external_contributors`.
- actions/permissions/workflow: `default_workflow_permissions: read`, `can_approve_pull_request_reviews: false`.
- actions/permissions: `enabled:true`, `allowed_actions:all`, `sha_pinning_required:false`.
- `vulnerability-alerts`: 404 (alertas de Dependabot no activadas; no se afirma lo contrario).
- `git log --oneline | wc -l` en esta rama = 39 (no 158: el 158 y los 34 issues / 37 PR son cifras de la auditoría del leader, citadas tal cual en DP-041; no las reverifiqué).

## Grep antes -> después
Antes (afirmaciones obsoletas): SECURITY.md:13; AGENTS.md:31 y :38; README.md:31 y :486 (protección «ya no se puede aplicar»/«convención»), :493 (paquete privado), :496-502 (read:packages incondicional), :529 (paquete «sigue privado»); docs/aws.md:723 (diagrama), :886-887 (gobernanza), docs/security.md:5 y :53; docs/decisions.md índice DP-030 y :372 (paquete privado), DP-030 Estado Vigente, DP-040 («mode=min» repo privado, paquete privado, «desde un repositorio privado»); docs/verification.md:22.
Después: el grep de `privad|private|Free|colaborador|collaborator|no se puede aplicar|convención|read:packages|plan Pro/Team|2 000 min|disciplina` solo deja: usos legítimos (reporte privado/hilo privado de SECURITY.md, subredes/redes privadas de AWS, «inmutable por convención» de tags de imagen, «disciplina» en DP-032/DP-040 sobre inmutabilidad), las menciones nuevas condicionales («solo si el paquete es privado»), el texto histórico de DP-030 (marcada Reemplazada, con nota de que ya no describe el estado actual) y los textos nuevos de DP-041 / security.md que citan el periodo privado. Ninguna afirmación vigente falsa. `read:packages` aparece solo condicionado a «paquete privado».

## Comprobador de enlaces/anclas
Script fuera del repo (scratchpad, `links.py`, reglas de slug de GitHub, ignora bloques de código) sobre README.md, AGENTS.md, SECURITY.md, docs/decisions.md, docs/security.md, docs/aws.md, docs/verification.md: 576 enlaces relativos, 0 rotos (el único «missing» durante la edición era este mismo informe, ya creado).

## Comentarios de workflow/script que ahora son falsos (NO tocados)
- `.github/workflows/release.yml:126`: `# The package is private while the repository is private: the pull needs a login.` Ya no es cierto el supuesto; el `docker login` en `smoke` sigue siendo correcto y necesario (con `GITHUB_TOKEN`), solo el comentario debería decir que no se asume la visibilidad del paquete.
- `.github/workflows/release.yml` (descripciones de la feature F-032 en feature_list.json): el criterio de aceptación de F-032 dice «from a PRIVATE repo», «private while the repo is private»; es texto histórico del issue, no se tocó.

## ./init.sh (verde, ejecutado una vez en primer plano; salida literal de las secciones y del final)
```
==> Validating feature_list.json
OK: 35 features, in_progress=['F-032']
==> Validating spec traceability and decisions register
OK: 35 features with origin, 41 decisions, 69 requirement rows
==> Validating CI runner images are pinned (no -latest labels)
OK: 7 runs-on entries, none uses a floating label
==> Testing the release gate scripts
ok   require-green-verify.sh [green] -> pass
ok   require-green-verify.sh [rerun-green] -> pass
ok   require-green-verify.sh [failed] -> fail
ok   require-green-verify.sh [none] -> fail
ok   require-green-verify.sh [api-error] -> fail
[... salida de Gradle/tests omitida ...]

[Incubating] Problems report is available at: file:///Users/alexcastrillon/Documents/Code/ticketflow/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/9.8.0/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 1m 27s
11 actionable tasks: 11 executed
==> init.sh OK
```

## Revisión 2

Cambio pedido por el reviewer: retirar la afirmación sin sustento «se observaron ejecuciones más rápidas».

- Reemplazada en README.md (CI en repositorio público), docs/security.md (sección «Qué implica un repositorio público para el CI»), docs/decisions.md DP-029 (Impacto y riesgo) y DP-041 (Impacto y riesgo).
- Texto nuevo: runners gratuitos y sin cupo según la documentación de GitHub; efecto en la duración no medido; ejecuciones del 2026-10-05 de 8-11 min, similares a los 7-9 min de DP-029.
- Grep de «más rápid», «faster», «observaron ejec» sin resultados. Los demás «rápido» son legítimos (inicio rápido, validaciones rápidas, acceso rápido).
- Comprobador de enlaces/anclas y `./init.sh`: ver abajo.
- Comprobador de enlaces y anclas sobre los .md tocados: 576 enlaces, 0 rotos.
- `./init.sh`: `BUILD SUCCESSFUL in 1m 25s`, `==> init.sh OK`.
- `gh run list`: CI en main/PR del 2026-10-05 de 7m47s a 10m51s.
