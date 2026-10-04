<!-- Español / English. Rellene o marque lo que aplique. / Fill in or tick what applies. -->

## Qué cambia / What changes

<!-- Resumen breve / Short summary -->

Closes #<issue>

Feature: `F-0xx` (`origin`: `spec` | `interpretation` | `own`)

## Checklist

- [ ] Rama `feature/<id>-<name>`; commits con prefijo convencional (ver [`docs/conventions.md`](../docs/conventions.md)) / Branch and conventional commit prefixes
- [ ] Tests nuevos para cada criterio de `acceptance` / New tests for every `acceptance` criterion
- [ ] `./init.sh` en verde (en CI: `INCLUDE_INTEGRATION=true ./init.sh`) / `./init.sh` is green
- [ ] Docs afectados actualizados (README, `docs/`, `requests/`) / Affected docs updated
- [ ] **¿Este cambio añade algo que el enunciado no pide?** -> registrado en [`docs/decisions.md`](../docs/decisions.md) (`DP-...`) y enlazado en `decisions` de `feature_list.json` / **Does this change add anything the statement does not ask for?** -> registered in `docs/decisions.md` (`DP-...`) and linked in `feature_list.json`: `DP-...` / n/a
- [ ] Si toca un requisito del enunciado, actualizada la fila en [`docs/requirements.md`](../docs/requirements.md) / If it touches a statement requirement, its row in `docs/requirements.md` is updated
- [ ] Sin secretos ni credenciales reales / No secrets or real credentials
