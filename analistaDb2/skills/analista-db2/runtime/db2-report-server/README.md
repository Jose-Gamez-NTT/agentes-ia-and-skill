# DB2 Report Server (Java)

Runtime requerido para conectar con DB2 mediante JDBC. El agente y la skill usan este servidor como backend cuando se ejecuta junto a un adaptador/Connector compatible.

## Requisitos

- JDK 11 o superior (el proyecto compila para Java 11).
- Maven 3.8+ disponible en `PATH` (`mvn -v`).
- Acceso de red al host DB2 y una cuenta DB2 con permisos mínimos de solo lectura.
- Driver JDBC IBM DB2 autorizado para este entorno. Por licencia, este paquete no distribuye esos JAR; obténlos de un repositorio/instalador aprobado y colócalos en `Drivers_win/`.

## Instalación y configuración

1. Copia el paquete completo de `analistaDb2/` a una ubicación local de trabajo. Al instalar la skill en un workspace, el runtime queda en `.axet-code/skills/analista-db2/runtime/db2-report-server/`. Copia esta carpeta a una ruta local de trabajo si la instalación de aXet.code está administrada o es de solo lectura; no ejecutes Maven dentro de una carpeta protegida.
2. Abre PowerShell en el directorio de trabajo del runtime y obtén el driver IBM autorizado. Crea `Drivers_win/` si hace falta y coloca allí los JAR requeridos.
3. Desde este directorio, crea la configuración local desde la plantilla:

   ```powershell
   Copy-Item db2.properties.example db2.properties
   notepad db2.properties
   ```

4. Rellena `db2.jdbcUrl` (`jdbc:db2://HOST:PORT/DATABASE`), `db2.user` y `db2.password`. Conserva `db2.driverDir=Drivers_win`; opcionalmente define `db2.schema`. No uses ni copies credenciales de otro entorno. Guarda el fichero solo en el equipo autorizado y no lo añadas a Git, tickets ni conversaciones.
5. Verifica que `db2.properties` está ignorado por Git (`git check-ignore db2.properties`). La plantilla y `.gitignore` del directorio protegen las configuraciones y drivers locales.
6. Mantén permisos DB2 de solo lectura. `ReadOnlyJdbc` y el DSL restringido son defensas adicionales, no sustituyen la autorización de la base ni una revisión de seguridad corporativa.

El fichero lee `db2.jdbcUrl` o construye la URL a partir de `db2.host`, `db2.port` y `db2.database`. No hay que configurar ambos formatos. Se pueden sobreescribir propiedades al arrancar con `-Ddb2.<propiedad>=...`, pero evita escribir contraseñas en comandos/shell history y prefiere el fichero local protegido.

## Arranque y parada

Desde este directorio, en PowerShell:

```powershell
mvn test
mvn compile exec:java
```

El segundo comando queda en primer plano. Espera a que anuncie `http://localhost:8080`; para detenerlo usa `Ctrl+C`. El servidor se enlaza solo a `localhost`, adecuado para uso local. No lo expongas en interfaces/redes compartidas sin autenticación y autorización apropiadas.

Para elegir otro puerto:

```powershell
mvn "-Dserver.port=8081" compile exec:java
```

Comprueba disponibilidad local sin consultar datos:

```powershell
Invoke-RestMethod -Method Get -Uri 'http://localhost:8080/health'
```

Debe devolver `status: ok`. La verificación de salud no prueba las credenciales ni la conectividad DB2; una llamada a `/query` de lectura confirma ambas.

## API de consultas

- `POST /query/preview`: valida el DSL y devuelve el SQL/los parámetros compilados, sin conectarse a DB2.
- `POST /query`: ejecuta el DSL contra DB2, devuelve columnas y filas, con `maxRows` por defecto 200 y máximo 1000; timeout por defecto 30s, máximo 120s.
- `GET /health`: comprobación local del proceso.
- `POST /generate`: compila y genera informes Jasper; necesita conexión DB2 y no es necesario para análisis tabular.

El API no admite SQL libre. Solo ejecuta consultas del DSL documentado en `../../references/query-json.md`. Mantén parámetros dinámicos en `params`, previsualiza primero y limita filas/tiempo.

Ejemplo en PowerShell (guarda el body en un archivo JSON no sensible y usa los nombres de esquema/campos ya verificados):

```powershell
$body = @'
{
  "query": {
    "select": [{ "column": "t.CODIGO" }],
    "from": { "schema": "ESQUEMA", "table": "TABLA", "alias": "t" },
    "limit": 20
  },
  "maxRows": 20,
  "timeoutSeconds": 15
}
'@
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/query/preview' -ContentType 'application/json' -Body $body
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/query' -ContentType 'application/json' -Body $body
```

No uses identificadores ficticios en una consulta real: primero descubre tablas/columnas con las herramientas de catálogo aprobadas. Al integrar con un cliente/Connector, este debe llamar al preview y query del servidor con su contrato compatible.

## Adaptador MCP de origen

En `conexionDB2/segundo` existe un adaptador MCP convencional Node.js con herramientas `db2_preview` y `db2_query`; no se incluye en este runtime Java. No se conecta automáticamente a aXet.code, cuyo mecanismo de integración son Connectors. Para aXet.code se necesita un Connector aprobado que alcance este servidor y exponga esas operaciones de forma segura. No registres el adaptador MCP como si fuera directamente un Connector ni publiques el servicio HTTP a la red para sortear esa integración.

## Desarrollo y mantenimiento

- Ejecuta `mvn test` después de cambiar Java o el DSL.
- Mantén las versiones Maven y el driver JDBC compatibles y aprobadas por NTT DATA, Inc.; no actualices drivers/JAR sin revisar licencias y compatibilidad DB2.
- Cambios al contrato HTTP/DSL deben reflejarse en `references/query-json.md` y en las instrucciones de la skill.
- Nunca guardes logs con credenciales, cadenas de conexión con datos sensibles o filas de clientes; los logs de errores deben revisarse antes de compartirlos.
- La operación de `/generate` permite una modalidad con rutas locales JRXML. Úsala solo con archivos controlados y acceso local de confianza; el servidor es HTTP local sin autenticación.

## Resolución de problemas

| Síntoma | Revisión |
|---|---|
| `mvn` o `java` no encontrado | Instala JDK/Maven aprobados y verifica `PATH` con `java -version` y `mvn -v`. |
| Falla de driver/clase JDBC | Comprueba que `Drivers_win/` existe, contiene JAR IBM DB2 compatibles y `db2.driverClass` sea `com.ibm.db2.jcc.DB2Driver`. |
| `/health` funciona pero `/query` da error de conexión | Revisa host/puerto/base, VPN/ruta/firewall, disponibilidad DB2 y permisos de red. No imprimas la contraseña al depurar. |
| Error de autenticación DB2 | Reescribe las credenciales localmente mediante el procedimiento autorizado; no las envíes por chat ni las pongas en argumentos visibles. |
| `/query/preview` responde error | Valida JSON, identificadores, operadores y estructura en la referencia DSL. Preview no requiere conexión DB2. |
| Puerto en uso | Arranca con `-Dserver.port=<puerto>` y actualiza la URL que use el Connector. |
| Cliente no puede acceder | Comprueba que el cliente se ejecuta en la misma máquina y apunta a `localhost`; no expongas el server a la red sin un diseño de autenticación/aprobación. |

## Distribución y licencias

El código fuente, `pom.xml`, pruebas y recursos de este directorio forman parte del paquete `analistaDb2`. La configuración real, artefactos `target/`, logs y credenciales se excluyen. Los JAR del driver DB2 se excluyen por ser dependencias de terceros. Deben obtenerse por un canal autorizado; sin ellos no será posible conectarse.
