# Instalación y configuración de Azure DevOps CI/CD

Paquete genérico para reutilizar el agente y la skill en distintos repositorios, organizaciones y proyectos. No contiene nombres específicos de proyecto ni credenciales reales. La skill incluye el gestor de Variable Groups inspirado en `asisa-DevopsCICD`; para consultar repositorios, PRs, pipelines y ejecuciones se necesita un connector/API/CLI accesible con permisos adecuados.

## Dependencias y alcance

- aXet.code para cargar skills locales y custom agents.
- PowerShell 5.1 o PowerShell 7+ únicamente si se va a usar el gestor incluido en `assets/ado/ado-library-manager.ps1`.
- Acceso de red a Azure DevOps.
- Un PAT de Azure DevOps para el gestor de Libraries, con el scope mínimo requerido. Para leer Variable Groups, permiso de lectura de Library; para añadir/editar/eliminar grupos o variables, permiso `Variable Groups: Read & manage`. El PAT también debe tener permisos sobre el proyecto y grupos concretos.
- Un connector/herramienta, Azure DevOps CLI ya instalada y autenticada, o cliente API aprobado para consultar PRs, pipelines, runs, deployments y demás APIs. El gestor incluido solo cubre Variable Groups y no reemplaza un connector de lectura/escritura general.
- No se requieren módulos PowerShell adicionales para el gestor incluido; usa REST API y cmdlets estándar.

## Estructura del paquete

La carpeta `Agencia CICD/` contiene `agents/`, `skills/` y `commands/`. Esta guía pertenece a `skills/azure-devops-cicd/`; instala la agencia completa siguiendo `../README.md`.

## Instalación del agente y la skill en un proyecto aXet.code

Instala la agencia completa desde la raíz del workspace consumidor siguiendo los pasos de `../README.md`. Esa guía copia agente, skill y comando desde una sola carpeta `Agencia CICD/`.

Para uso global, instala el contenido de `../agents/`, `../skills/azure-devops-cicd/` y `../commands/` en los directorios globales equivalentes de aXet.code. Comprueba si ya existe un agente con el mismo nombre.

Invoca el agente `azure-devops-cicd` desde el selector de agentes. La skill se activa por su descripción o puede invocarse como `%azure-devops-cicd`. Si no aparecen los archivos recién copiados, abre una sesión nueva de aXet.code.

### Actualizar `/ado-configurar` por separado

El flujo completo está en `references/ado-configurar-workflow.md`; el comando corto de esta agencia está en `../../commands/ado-configurar.md`. La skill no registra slash commands por sí misma. El comando se copia a `.axet-code/commands/ado-configurar.md` durante la instalación. El picker lo muestra como comando del proyecto (`project:ado-configurar`); para hacerlo global, copia el archivo a la carpeta global `commands/`.

## Configurar el perfil de organización/proyectos paso a paso

1. Copia la plantilla:

   ```powershell
   Copy-Item ".axet-code/skills/azure-devops-cicd/config/ado-projects.example.json" `
     ".axet-code/skills/azure-devops-cicd/config/ado-projects.json"
   ```

2. En `ado-projects.json`, sustituye `id`, `name` y `url` de la organización con datos reales. `url` es la URL base de la organización/collection (no la URL de un repo ni una URL que contenga PAT).
3. En `projects`, añade cada proyecto con su **nombre exacto o ID**. No asumas que el nombre del proyecto es el nombre de la organización.
4. Rellena `repositories`, `pipelines` y `libraries` con los IDs y nombres visibles. Añade `defaultBranch`, `path` y `notes` solo cuando estén confirmados. Puedes dejar arrays vacíos hasta completar la lectura remota.
5. Valida la sintaxis/esquema del JSON y revisa el diff. Ejecuta `/ado-configurar` para obtener inventario/contraste de solo lectura; el flujo debe mostrar vista previa antes de cambiar el catálogo.
6. El catálogo contiene metadatos, nunca PATs, tokens, contraseñas ni valores de variables. Decide si debe versionarse: si contiene nombres internos, trátalo como configuración local y añádelo al `.gitignore` del proyecto.

Ejemplo reducido del formato:

```json
{
  "$schema": "./ado-projects.schema.json",
  "organizations": [
    {
      "id": "mi-org",
      "name": "Mi organización",
      "url": "https://dev.azure.com/MI_ORGANIZACION",
      "projects": [
        {
          "id": "ID_O_NOMBRE_EXACTO",
          "name": "Nombre exacto del proyecto",
          "repositories": [],
          "pipelines": [],
          "libraries": []
        }
      ]
    }
  ]
}
```

## Instalar el gestor de Variable Groups y crear `.env` local

1. Desde la raíz del proyecto destino, copia el asset a una carpeta de trabajo local, por ejemplo `scripts/ado/`:

   ```powershell
   New-Item -ItemType Directory -Force scripts/ado | Out-Null
   Copy-Item -LiteralPath ".axet-code/skills/azure-devops-cicd/assets/ado/ado-library-manager.ps1" `
     -Destination "scripts/ado/ado-library-manager.ps1"
   Copy-Item -LiteralPath ".axet-code/skills/azure-devops-cicd/assets/ado/ado.env.example" `
     -Destination "scripts/ado/ado.env.example"
   Copy-Item -LiteralPath ".axet-code/skills/azure-devops-cicd/assets/ado/.gitignore" `
     -Destination "scripts/ado/.gitignore"
   Copy-Item -LiteralPath ".axet-code/skills/azure-devops-cicd/assets/ado/README.md" `
     -Destination "scripts/ado/README.md"
   ```

2. Desde `scripts/ado`, copia la plantilla `.env` y edítala localmente:

   ```powershell
   Copy-Item ado.env.example .env
   notepad .env
   ```

3. Rellena los campos, sin comillas salvo que el valor lo exija:

   ```dotenv
   ADO_ORG=https://dev.azure.com/MI_ORGANIZACION
   ADO_PROJECT=Nombre exacto o ID del proyecto
   ADO_PAT=TU_PAT_LOCAL
   ```

   Para una organización Azure DevOps Server/collection antigua, usa la base URL compatible que te proporcione el administrador. No copies tokens al chat ni a archivos versionados. El PAT lo emite el usuario en Azure DevOps User Settings → Personal Access Tokens y debe caducar según la política corporativa.

4. Verifica que `.env` queda ignorado por Git y no aparece en el diff. El `.gitignore` incluido excluye `.env`, exports/backups y `libraries/`. No quites esa protección si esos JSON contienen información interna.
5. Prueba primero una consulta no destructiva desde el directorio `scripts/ado`:

   ```powershell
   .\ado-library-manager.ps1 -LibraryName "NOMBRE_VARIABLE_GROUP" -Action list
   ```

   El script debe enmascarar variables secretas. `list` requiere que la Library exista; para grupos o permisos no conocidos, detente y revisa el README del gestor.
6. Para consultar una clave no secreta, usa `-Action get -Key "NOMBRE_VARIABLE"`. No uses `get` para intentar extraer secretos.
7. No ejecutes `add`, `update`, `delete` o `import` hasta revisar el archivo del grupo objetivo, confirmar el proyecto y presentar/obtener autorización explícita. `-WhatIf` sirve para previsualizar los cambios que el script soporta; no reemplaza la confirmación.

## Uso seguro de Libraries

El gestor escribe un JSON local al exportar o usa `libraries/<LibraryName>.json` por defecto al importar; esa carpeta está ignorada por Git por seguridad. Los valores secretos exportados quedan vacíos (`value: ""`, `isSecret: true`), ya que Azure DevOps no los devuelve. `merge` es el modo por defecto; `replace` reemplaza todas las variables y puede eliminar las que no estén en el JSON. Consulta `assets/ado/README.md` para comandos y flujo de backup/WhatIf.

Nunca cambies variables duplicadas entre grupos sin verificar precedencia y alcance. Si una variable secreta ya existe en otra Library, conserva el origen compartido salvo que el usuario autorice expresamente lo contrario.

## Troubleshooting de instalación

- **Skill/agente no aparece:** verifica ubicación y nombre de carpeta/archivo; inicia una sesión nueva.
- **`401`:** confirma URL/collection, vigencia del PAT, formato local y scope requerido. No imprimas el PAT.
- **`403`:** pide al administrador acceso mínimo al proyecto/recurso; un permiso de repositorios no implica permiso para Libraries.
- **Proyecto no encontrado:** usa el ID o nombre exacto; cuidado con acentos, espacios y collection heredada.
- **`Variable group not found`:** confirma el proyecto y el nombre exacto de la Library.
- **No existe connector Azure DevOps:** la skill no crea un connector. Usa solo una API/CLI/connector que ya esté disponible y aprobada; el gestor incluido es solo para Variable Groups.

## Validación del paquete

- Revisa `SKILL.md` y sus enlaces relativos.
- Valida `config/ado-projects.example.json` con el schema.
- Confirma que ningún `.env` real, PAT, secreto o valor de Library se encuentra en el paquete.
- Comprueba `.gitignore` en la carpeta efectiva `scripts/ado/` antes de usar credenciales.
- Confirma que PowerShell puede ejecutar el script respetando la Execution Policy aprobada por la organización; no cambies la policy global para sortear un bloqueo.
