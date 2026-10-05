# Estado actual

F-032 (primer release en ghcr.io): preparacion aprobada, PR abierto como "Part of #62". NO se ha etiquetado ni publicado nada.
Pendiente tras fusionar: esperar el CI de main, crear el tag v0.1.0 (accion de la sesion principal), seguir el run (gate -> publish -> smoke) y verificar la imagen publicada; entonces marcar F-032 done y cerrar #62.

---

Feature en curso: F-035 — repository-is-public (issue #72, rama feature/F-035-repository-is-public)
Plan:
- Reconfirmar con consultas de solo lectura a la API los ajustes del repositorio ya aplicados por la sesion del dueno (visibilidad, proteccion de main, reporte privado, secret scanning, aprobacion de forks, token de solo lectura).
- Corregir cada afirmacion falsa (repositorio privado, proteccion de main no aplicable, clonar exige acceso, paquete privado) en README, AGENTS.md, SECURITY.md y docs/.
- Documentar la proteccion activa y su limite honesto, y la visibilidad del paquete como cosa a COMPROBAR tras el primer release.
- Registrar DP-041 (Extra propio); DP-030 pasa a "Reemplazada por DP-041".
- Verificar enlaces/anclas y ./init.sh. Solo documentacion: sin cambios en src/, build, workflows ni scripts.
