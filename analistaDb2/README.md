# analistaDb2

Paquete reutilizable de aXet.code para analizar consultas DB2 de solo lectura. Incluye el agente `analista-db2`, la skill `analista-db2`, el runtime Java DB2 Report Server y un flujo guiado de instalación/configuración.

## Contenido

- `agents/analista-db2.md`: agente especialista.
- `commands/db2-configurar.md`: comando `/db2-configurar` para guiar la instalación y la conexión.
- `scripts/install-analista-db2.ps1`: instala agente, skill, runtime y comando en un workspace; prepara el fichero local de configuración, copia drivers autorizados opcionalmente y verifica requisitos.
- `skills/analista-db2/SKILL.md`: reglas de análisis, seguridad y operación.
- `skills/analista-db2/scripts/db2-runtime.ps1`: configura, comprueba, prueba y arranca el runtime.
- `skills/analista-db2/references/query-json.md`: DSL de solo lectura del servidor.
- `skills/analista-db2/runtime/db2-report-server/`: servidor Java/Maven, pruebas y plantilla de configuración.

## Instalación guiada en Windows

Desde PowerShell en cualquier directorio, usando la ruta local de este paquete y la raíz del workspace consumidor:

```powershell
$package = 'C:\ruta\al\repositorio\analistaDb2'
$workspace = 'C:\ruta\al\workspace'
$drivers = 'C:\ruta\aprobada\Drivers_win'
& "$package\scripts\install-analista-db2.ps1" -Action Install -Workspace $workspace -DriversSource $drivers
```

El script copia el agente/skill/comando/runtime sin drivers, excluye configuración, logs y artefactos temporales, crea `db2.properties` desde la plantilla solo si aún no existe, pregunta la ruta de drivers si no se pasó, copia solo los JAR y conserva la configuración y drivers preexistentes. Los archivos distribuidos modificados localmente también se conservan, en lugar de sobrescribirse. Después abre Notepad para completar credenciales en local y comprueba Java, Maven, la presencia de configuración/drivers y la exclusión de Git sin imprimir valores secretos. Si quedan requisitos pendientes, devuelve código de salida 2 y permite repetir la comprobación después de corregirlos. `-NoEditor` evita abrir Notepad; `-SkipMavenTests` omite las pruebas Maven durante la instalación.

El instalador no instala JDK/Maven ni descarga drivers: usa dependencias existentes y requiere que IBM DB2 JDBC se obtenga por un canal corporativo aprobado. No copies credenciales ni JAR a este repositorio.

Para desinstalar los archivos distribuidos desde el mismo paquete, conservando `db2.properties`, drivers, logs y cualquier archivo editado localmente:

```powershell
& "$package\scripts\install-analista-db2.ps1" -Action Uninstall -Workspace $workspace
```

Antes de desinstalar, detén cualquier servidor activo con `Ctrl+C`. La desinstalación elimina solo archivos distribuidos cuyo hash coincide con la copia del paquete y limpia únicamente directorios que queden vacíos. Los archivos locales modificados o no verificables se preservan; también preserva configuración, drivers, logs, temporales y contenido ajeno al paquete. No desinstala Java/Maven. Ejecuta el script desde la misma versión del paquete utilizada para instalar, para que pueda verificar los hashes.

## Configuración, comprobación y ejecución

Después de instalar, desde la raíz del workspace:

```powershell
$runtime = '.\.axet-code\skills\analista-db2\scripts\db2-runtime.ps1'
& $runtime -Action Check -Workspace (Get-Location).Path
& $runtime -Action Start -Workspace (Get-Location).Path
```

`Start` queda en primer plano en esa terminal. En otra:

```powershell
& '.\.axet-code\skills\analista-db2\scripts\db2-runtime.ps1' -Action Test -Workspace (Get-Location).Path
```

`Test` ejecuta las pruebas Maven, consulta `/health` y realiza una única consulta de solo lectura a `SYSIBM.SYSDUMMY1`. Confirma conectividad básica, no permisos de negocio. El runtime enlaza a `localhost`; no lo expongas a redes ni añadas autenticación improvisada. Detén el servidor con `Ctrl+C`.

También puedes usar el comando `/db2-configurar` dentro de aXet.code para recibir los pasos; debe iniciar una nueva sesión si agente, skill o comando todavía no aparecen. El comando guía al asistente, mientras que los scripts realizan las operaciones locales repetibles.

## Conectividad con aXet.code y análisis

El runtime por sí solo no añade una herramienta DB2 al agente. Las consultas automáticas requieren un Connector aprobado que alcance el runtime, o herramientas de sesión autorizadas que puedan acceder efectivamente a `localhost`. Si no existe ese acceso, el agente puede preparar DSL/comandos PowerShell y analizar resultados que el usuario haya ejecutado, pero no debe afirmar que consultó DB2.

Descubre primero catálogo, tablas y columnas mediante consultas de solo lectura. Revisa `/query/preview` antes de `/query`, parametriza valores y limita columnas, filas y tiempo. La cuenta de DB2 debe tener permisos mínimos de solo lectura; las defensas del runtime no sustituyen privilegios de base de datos.

## Seguridad y archivos locales

`db2.properties`, `.env`, `Drivers_win/`, `target/` y logs están excluidos del paquete y del repositorio del proyecto. La configuración y los drivers en el workspace consumidor también deben quedar ignorados por Git; el script lo comprueba para `db2.properties`. No leas, compartas ni registres contraseñas, tokens, cadenas JDBC sensibles ni resultados con datos personales.

La configuración de ejemplo usa `db2.jdbcUrl=jdbc:db2://HOST:PORT/DATABASE`, `db2.user`, `db2.password`, `db2.driverDir=Drivers_win` y, opcionalmente, `db2.schema`. Se permite alternativamente especificar host, puerto y base como propiedades separadas. Mantén el driver JDBC compatible y autorizado.
