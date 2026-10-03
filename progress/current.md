# Estado actual

Feature en curso: F-026 — aws-cloud-native-docs (rama feature/F-026-aws-docs; solo documentación, nada se despliega en AWS)
Plan:
- Escribir `docs/aws.md` (alcance, arquitectura con Mermaid validado, cómputo, datos y límites de escalabilidad, seguridad, observabilidad, gobierno, costes con precios reales del Price List API, checklist de producción).
- Verificar cada afirmación sobre la app contra `src/main` y la configuración; cada límite o precio de AWS lleva URL y fecha de consulta (o «to verify»).
- Enlazar desde README, `docs/security.md`, `docs/observability.md`, `docs/architecture.md` y `SECURITY.md` y sustituir todos los marcadores «F-026» por enlaces relativos reales.
- Validar: renderizado de los diagramas con mermaid-cli (más control negativo), JSON de las políticas con `json.loads`, enlaces y anclas relativos, `./init.sh` e `INCLUDE_INTEGRATION=true ./init.sh`.
- Informe en `progress/impl_aws-docs.md`. El estado de `feature_list.json` lo gestiona la sesión principal.

Ultimas completadas: F-001 a F-025 (APPROVED)
