# Estado actual

Feature en curso: F-033 — diagnose-request-hang (issue #63)
Plan:
- Sondeo de protocolo con sockets crudos de los rechazos tempranos (401/405/413/415/429, cuerpo tardio, chunked) seguidos de una peticion en la misma conexion; miles de iteraciones sin y con carga de CPU.
- Resultado: la hipotesis NO se reproduce (0 anomalias en 174 000 peticiones sin carga, 174 000 con carga y 25 000 con un pool de una conexion); evidencia de la libreria (reactor-netty 1.3.7 descarta el cuerpo tras responder).
- Instrumentacion: extension JUnit HangDumpExtension (hilos + sockets a build/reports/hang-dumps, subido en el artefacto `reports` de CI).
- Registro DP-039, docs/verification.md (como leer un volcado), informe en progress/impl_diagnose-request-hang.md.

Estado: implementada, verificando con ./init.sh (pendiente de reviewer; NO marcar done).
