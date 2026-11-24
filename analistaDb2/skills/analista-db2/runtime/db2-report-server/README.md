# DB2 Report Server (Java)

Runtime local de solo lectura para consultar DB2 por JDBC. La presencia de este servidor no crea un Connector ni añade herramientas DB2 a aXet.code.

## Requisitos

- Windows con PowerShell 5.1+ o PowerShell 7+.
- JDK 11+ y Maven 3.8+ disponibles en `PATH`.
- Acceso de red/VPN a DB2 y cuenta con privilegios mínimos de solo lectura.
- JAR del driver IBM DB2 JDBC autorizado para este entorno. No se incluyen ni descargan automáticamente por licencia.

## Instalación guiada recomendada

Instala agente, skill, runtime y comando desde PowerShell:

```powershell
$package = 'C:\ruta\al\repositorio\analistaDb2'
$workspace = (Get-Location).Path
$drivers = 'C:\ruta\aprobada\Drivers_win'
& "$package\scripts\install-analista-db2.ps1" -Action Install -Workspace $workspace -DriversSource $drivers
```

El instalador copia el runtime excluyendo drivers/configuración local, conserva ficheros locales existentes, crea `db2.properties` desde `db2.properties.example` si falta y copia los JAR autorizados a `.axet-code/skills/analista-db2/runtime/db2-report-server/Drivers_win/`. Abre el fichero con Notepad para la configuración local. No muestra ni solicita credenciales por chat. Si ya están instalados los archivos, usa el script del workspace:

```powershell
$script = '.\.axet-code\skills\analista-db2\scripts\db2-runtime.ps1'
& $script -Action Configure -Workspace (Get-Location).Path -DriversSource $drivers
& $script -Action Check -Workspace (Get-Location).Path
```

`Configure` conserva configuración y JAR existentes. También se puede invocar el comando aXet.code `/db2-configurar` para obtener guía interactiva. El comando instruye al agente; las operaciones del equipo se ejecutan con los scripts de PowerShell.

## Configuración manual

Si se requiere, desde el runtime de la instalación local copia la plantilla a `db2.properties` y edítala con un editor local. No pegues credenciales en comandos, Git, tickets ni chats. Configura `db2.jdbcUrl=jdbc:db2://HOST:PORT/DATABASE` (o `db2.host`, `db2.port`, `db2.database`), `db2.user`, `db2.password`; conserva `db2.driverDir=Drivers_win` y opcionalmente define `db2.schema`. Verifica localmente que `db2.properties` está ignorado por Git. La cuenta de base debe ser solo lectura.

## Comprobar, iniciar y probar

El script `db2-runtime.ps1` ofrece estas acciones:

- `Check`: verifica Java/Maven, configuración y placeholders sin imprimir valores; cuenta JAR y revisa `git check-ignore`.
- `Start`: ejecuta `mvn compile exec:java` en primer plano. Déjalo abierto en una terminal persistente.
- `Test`: ejecuta `mvn test`, consulta `/health` y hace una consulta de una fila a `SYSIBM.SYSDUMMY1`. Debe ejecutarse en otra terminal mientras el servidor está activo.

```powershell
$script = '.\.axet-code\skills\analista-db2\scripts\db2-runtime.ps1'
& $script -Action Check -Workspace (Get-Location).Path
& $script -Action Start -Workspace (Get-Location).Path
```

En una segunda terminal:

```powershell
& '.\.axet-code\skills\analista-db2\scripts\db2-runtime.ps1' -Action Test -Workspace (Get-Location).Path
```

La prueba confirma conectividad básica de lectura, no autorización sobre las tablas funcionales. Para detenerlo, pulsa `Ctrl+C`. El servidor solo se enlaza a `localhost`; no lo publiques en interfaces/redes compartidas.

## API y análisis

- `GET /health`: estado del proceso local; no prueba credenciales.
- `POST /query/preview`: valida el DSL, compila y devuelve SQL/parametrización sin conectar a DB2.
- `POST /query`: ejecuta el DSL JSON restringido, solo de lectura; máximo 1000 filas y 120 s.
- `POST /generate`: genera informes Jasper; no es necesario para análisis tabular.

Consulta `../../references/query-json.md` para el DSL. Nunca envíes SQL libre; parametriza valores, usa columnas explícitas y límites bajos. Previsualiza antes de ejecutar.

La capacidad del agente para enviar solicitudes a `localhost` depende de las herramientas disponibles en la sesión: una sesión remota/sandbox puede no ver el servicio local del usuario. Si no hay Connector aprobado ni herramienta con acceso local efectivo, ejecuta el comando desde PowerShell y comparte solo resultados no sensibles; no afirmes consultas que no se ejecutaron.

## Resolución de problemas

| Síntoma | Qué comprobar |
|---|---|
| Java/Maven no encontrados | Instala versiones aprobadas, actualiza `PATH`, abre una terminal nueva y ejecuta `java -version`, `mvn -v`. |
| Configuración falta o tiene placeholders | Ejecuta `-Action Configure`; edita `db2.properties` localmente y luego `-Action Check`. El script nunca imprime sus valores. |
| Cero JAR detectados | Confirma JAR JDBC IBM aprobados en la carpeta indicada por `db2.driverDir`; no descargues/copias desde fuentes no aprobadas. |
| `/health` no responde | Comprueba que `-Action Start` permanece activo y que `BaseUrl`/puerto coinciden. |
| `/health` funciona, `/query` falla | `/health` no se conecta a DB2. Revisa VPN/ruta/firewall, URL/base, permisos y error JDBC; no imprimas la contraseña. |
| Error de autenticación | Revisa/actualiza la configuración local mediante el canal autorizado; nunca compartas credenciales por chat. |
| `SQLCODE=-204` para catálogo | No asumas que `SYSCAT`/`QSYS2` existe. Confirma la plataforma/versión DB2 y los catálogos disponibles antes de elegir tablas de metadatos. |
| No se puede conectar al endpoint desde el asistente | Es limitación de herramienta/sesión. Ejecuta las llamadas en el equipo que ve `localhost`, o habilita Connector aprobado; no expongas el servidor a la red para evitarlo. |

## Seguridad

Los drivers y ficheros locales (`db2.properties`, `Drivers_win/`, `target/`, logs) no deben subirse al repositorio. El servidor no tiene autenticación HTTP y está pensado para localhost; no lo expongas a terceros. Las barreras del DSL y JDBC son defensa adicional, no sustituyen los permisos DB2 de mínimo privilegio.
