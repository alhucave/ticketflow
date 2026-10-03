# Estado actual

Feature en curso: F-023 — security-hardening (PARTE 2, rama feature/F-023b-supply-chain-hardening)
Plan:
- Escaneos en CI (security.yml: Trivy deps + imagen, gitleaks historial) con bloqueo de dependencias Gradle.
- Dependabot (gradle, github-actions, docker).
- Imagen distroless fijada por digest + compose endurecido (127.0.0.1, read_only, cap_drop, límites).
- docs/security.md, SECURITY.md, enlaces en README.
Estado: implementado y verificado localmente; pendiente de review (no marcar done).
Informe: progress/impl_supply-chain-hardening.md
Siguiente: F-024, F-022, F-025, F-026
