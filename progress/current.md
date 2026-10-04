# Estado actual

Feature en curso: F-028 — stabilize-integration-tests
Plan: (1) causa raiz desde el log/XML del run; (2) plazos unicos en `testsupport` (propiedad de Boot + helper + guard); (3) auditoria de plazos; (4) evidencia 5x INCLUDE_INTEGRATION + carga CPU; (5) DP-035 y docs. Informe: progress/impl_stabilize-integration-tests.md
Completas: F-001 a F-027 (27/27), repo privado.
Hallazgos abiertos para decidir con el usuario (ver progress/history.md): test inestable en CI (HardeningEndToEndIT, timeout 5 s), TTL de reserva sin tope de 10 min, consumidor/expiracion apagados por defecto, release.yml sin ejecutar.
