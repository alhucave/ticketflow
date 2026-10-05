# Política de seguridad

## Reportar una vulnerabilidad

**No abra un issue público** para una vulnerabilidad. Use el reporte privado de GitHub:

1. Vaya a la pestaña **Security** del repositorio.
2. Pulse **Report a vulnerability** (*private vulnerability reporting*).
3. Describa el problema, los pasos para reproducirlo y el impacto esperado.

Se confirmará la recepción y se informará del avance en ese mismo hilo privado. No incluya credenciales reales en el reporte.

> El repositorio es público (desde 2026-10-05) y el reporte privado de vulnerabilidades está **activado** (comprobado con la API de GitHub el 2026-10-05): el flujo de arriba funciona. Si el botón **Report a vulnerability** no apareciera, no use un issue: contacte con la persona propietaria a través de su perfil de GitHub.

## Alcance

Es un proyecto de demostración/prueba técnica sin despliegue en producción (el diseño de un despliegue en AWS, no ejecutado, está en [`docs/aws.md`](docs/aws.md#5-seguridad-en-la-nube)). Soporta únicamente la rama `main`. El modelo de amenazas, los controles y las limitaciones conocidas están en [`docs/security.md`](docs/security.md).
