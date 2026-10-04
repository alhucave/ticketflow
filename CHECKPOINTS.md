# CHECKPOINTS

Checklist que aplica el `reviewer` a cada feature.

- C1: `./init.sh` termina en verde (ejecutado por el reviewer, no copiado del informe).
- C2: Se respeta la regla de dependencias `infrastructure → usecase → domain`; `domain` no importa Spring ni AWS SDK.
- C3: Todo criterio de `acceptance` tiene un test que lo prueba de verdad (no tests vacíos).
- C4: No hay llamadas bloqueantes (`.block()`, `Thread.sleep`) en código de producción.
- C5: Cualquier cambio de inventario usa conditional writes / optimistic locking.
- C6: Las transiciones de estado son válidas según `docs/architecture.md` (§ «Estados de una entrada») y quedan auditadas.
- C7: Código, nombres y comentarios en inglés; sin Lombok ni `@Autowired` en campos.
- C8: No hay secretos ni credenciales reales en el código, la configuración o los logs.
- C9: La cobertura global se mantiene ≥ 90%.
- C10: El scope no excede la feature (sin cambios ajenos al `acceptance`).
- C11: La documentación afectada (README, docs/, diagramas Mermaid de `docs/architecture.md`, `requests/`) está actualizada y coincide con el código.
- C12: Cada caso de uso que combine puertos (repositorios, cola) tiene al menos una prueba de integración de punta a punta con los **adaptadores reales** (Testcontainers), y el reviewer comprobó que lo que hace cada adaptador (escrituras atómicas, condiciones, contratos de `save`/`create`) es compatible con cómo lo llama el caso de uso. Los mocks por sí solos no bastan.
- C13: Si la feature agrega comportamiento, límite o restricción que el enunciado no exige (`origin` `interpretation` u `own`), está registrada en `docs/decisions.md` (`DP-NNN`) y enlazada en `decisions` de `feature_list.json`; si toca un requisito del enunciado, `docs/requirements.md` está actualizado (estado, dónde, evidencia). El reviewer también juzga que el `origin` sea honesto.
