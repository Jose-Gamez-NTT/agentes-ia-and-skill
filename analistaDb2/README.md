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

El script copia el agente/skill/comando/runtime, incluidos los JAR versionados de `Drivers_win/`; excluye configuración, logs y artefactos temporales. Crea `db2.properties` desde la plantilla solo si aún no existe, pregunta la ruta de drivers si no se pasó y copia JAR adicionales conservando la configuración y drivers preexistentes. Los archivos distribuidos modificados localmente también se conservan, en lugar de sobrescribirse. Después abre Notepad para completar credenciales en local y comprueba Java, Maven, la presencia de configuración/drivers y la exclusión de Git sin imprimir valores secretos. Si quedan requisitos pendientes, devuelve código de salida 2 y permite repetir la comprobación después de corregirlos. `-NoEditor` evita abrir Notepad; `-SkipMavenTests` omite las pruebas Maven durante la instalación.

El instalador no instala JDK/Maven ni descarga drivers: usa dependencias existentes. Los JAR DB2 versionados solo deben redistribuirse conforme a su licencia y a la política corporativa. No copies credenciales a este repositorio.

Para desinstalar el agente, la skill, el comando y todo el runtime instalado en `.axet-code/skills/analista-db2`, incluidos `db2.properties`, drivers, logs, temporales, artefactos compilados y datos locales:

```powershell
& "$package\scripts\install-analista-db2.ps1" -Action Uninstall -Workspace $workspace
```

La desinstalación elimina completa la carpeta de la skill instalada, incluidos archivos modificados o generados localmente; guarda antes cualquier configuración, driver o dato que quieras conservar. No elimina el paquete fuente ni desinstala Java/Maven. Comprueba procesos Java/Maven que usen esa carpeta y aborta si detecta alguno: detén el servidor con `Ctrl+C` y vuelve a ejecutar. Si Windows bloquea algún archivo, la operación falla con la ruta/error en vez de informar éxito. El agente y el comando se retiran solo si coinciden con los archivos fuente (por hash); un archivo local modificado en esas rutas se conserva.

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

`db2.properties`, `.env`, `target/` y logs están excluidos del paquete y del repositorio del proyecto. Los JAR DB2 de `Drivers_win/` están versionados y deben distribuirse solo conforme a la licencia aplicable. La configuración local en el workspace consumidor debe quedar ignorada por Git; el script lo comprueba para `db2.properties`. No leas, compartas ni registres contraseñas, tokens, cadenas JDBC sensibles ni resultados con datos personales.

La configuración de ejemplo usa `db2.jdbcUrl=jdbc:db2://HOST:PORT/DATABASE`, `db2.user`, `db2.password`, `db2.driverDir=Drivers_win` y, opcionalmente, `db2.schema`. Se permite alternativamente especificar host, puerto y base como propiedades separadas. Mantén el driver JDBC compatible y autorizado.
