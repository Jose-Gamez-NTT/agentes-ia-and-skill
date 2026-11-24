# analistaDb2

Paquete reutilizable de aXet.code para analizar necesidades de consulta DB2 con un agente especializado y una skill de apoyo. El agente y la skill no contienen credenciales ni implementan la conexión: requieren que el workspace tenga un connector aprobado que exponga las operaciones de consulta DB2.

## ¿Agente, skill o ambos?

Se incluyen **ambos**. El agente `analista-db2` aparece como especialista seleccionable y conserva el rol, los límites y el flujo de análisis. La skill `analista-db2` concentra las instrucciones reutilizables y la referencia DSL; también puede invocarse con `%analista-db2`. Mantenerlos separados facilita actualizar la guía sin duplicarla en el agente.

## Contenido

- `agents/analista-db2.md`: definición del agente y enlace a su skill.
- `skills/analista-db2/SKILL.md`: flujo de trabajo, reglas de uso y requisitos de conectividad.
- `skills/analista-db2/references/query-json.md`: referencia del DSL JSON estructurado del servidor.
- `skills/analista-db2/runtime/db2-report-server/`: servidor Java/Maven completo (código fuente, pruebas, recursos y plantilla); va dentro de la skill para instalarse junto con ella. Consulta su README para los pasos de instalación/arranque.

## Requisito de conectividad

Se distribuye el servidor Java/Maven de `conexionDB2` dentro de `skills/analista-db2/runtime/db2-report-server/`, con código fuente, pruebas, recursos y plantilla `db2.properties.example`; al instalar la skill se copia también el runtime. La configuración con contraseña no se incluye. El runtime requiere JDK, Maven, una cuenta DB2 y un driver JDBC IBM obtenido por un canal aprobado; el paquete no redistribuye JARs de IBM. Consulta `skills/analista-db2/runtime/db2-report-server/README.md` para instalar, configurar, arrancar, detener y mantenerlo.

El adaptador MCP de origen (`conexionDB2/segundo`) ofrece `db2_preview` y `db2_query`, pero no está incluido ni se registra directamente como Connector de aXet.code: este producto usa Connectors propios. El runtime Java hace disponible el servicio HTTP local (`/query/preview` y `/query`), pero para que el agente llame DB2 desde aXet.code se necesita un Connector aprobado que alcance el servicio y exponga esas operaciones. Si todavía no existe, el runtime puede probarse localmente con PowerShell; el agente solo podrá preparar y revisar consultas, no invocar DB2.

## Instalación en un workspace aXet.code

Desde la raíz del workspace consumidor, ajusta `$agency` a la ruta de este paquete:

```powershell
$agency = "<ruta-del-repositorio>/analistaDb2"
New-Item -ItemType Directory -Force .axet-code/agents, .axet-code/skills | Out-Null
Copy-Item "$agency/agents/analista-db2.md" ".axet-code/agents/analista-db2.md"
Copy-Item "$agency/skills/analista-db2" ".axet-code/skills/analista-db2" -Recurse -Force
```

Después inicia una nueva sesión si el agente o skill no aparecen. El agente se selecciona como `analista-db2`; la skill se puede invocar como `%analista-db2`.

## Uso local y seguridad

- Sigue `skills/analista-db2/runtime/db2-report-server/README.md`; crea allí `db2.properties` desde la plantilla y comprueba que queda ignorado por Git. No copiar contraseñas, tokens ni `db2.properties` a este repositorio o a la conversación.
- Arranca/paraliza el servidor local y ejecuta pruebas según esa guía. El binding por defecto es `localhost`; no expongas el endpoint sin un diseño aprobado de autenticación/autorización.
- Mantener la cuenta DB2 con permisos mínimos de solo lectura; las protecciones del cliente no sustituyen permisos de base de datos.
- Usar `db2_preview` antes de `db2_query` si el Connector ofrece ambas operaciones.
- Empezar con límites bajos de filas/tiempo y seleccionar solo las columnas necesarias.
- Ante ausencia de Connector, detenerse en el diseño/validación del DSL y explicar que no se pudo ejecutar.

## Evaluación del stack origen

La separación de responsabilidades es útil: el servidor Java implementa JDBC/DSL y el adapter MCP es una capa fina. El DSL parametriza valores y limita funciones, operadores y filas. Sin embargo, en el origen se encontró un `db2.properties` local con credenciales en texto claro; no se copiaron al paquete. Revisa su estado de versionado y rota la contraseña expuesta conforme al procedimiento corporativo. Consulta también el README del servidor origen para su modo de ejecución y sus límites.
