# Skill analista-db2

Skill genérica para orientar análisis de datos DB2 de solo lectura mediante un Connector DB2 disponible en aXet.code.

## Uso

Invócala con `%analista-db2` o delega en el agente `analista-db2`. Para llamadas reales se necesita un Connector aprobado; los archivos de agente/skill no establecen ni transportan la conexión.

## Requisitos y seguridad

- El runtime Java/Maven está incluido en el paquete y requiere JDK 11+, Maven 3.8+ y acceso a DB2. Sigue `runtime/db2-report-server/README.md` para crear la configuración local y arrancarlo.
- La configuración con credenciales reales no se distribuye. Los JAR JDBC DB2 están en `runtime/db2-report-server/Drivers_win/`; su inclusión y redistribución deben ajustarse a la licencia IBM y a la política corporativa.
- El servidor acepta únicamente un DSL JSON limitado, con parámetros vinculados, y aplica límites de filas/tiempo; revisa `references/query-json.md`.
- Conserva una cuenta de DB2 de mínimo privilegio y solo lectura. Las comprobaciones del servidor no sustituyen los permisos de la base.
- Confirma que el Connector no exponga SQL libre antes de usarlo para consultas de análisis.

## Archivos

- `SKILL.md`: flujo y reglas operativas.
- `references/query-json.md`: formato de consulta estructurada del servidor incluido.
- `runtime/db2-report-server/`: runtime Java/Maven, código, pruebas, recursos y configuración de ejemplo. Consulta su README para instalarlo y operarlo.
