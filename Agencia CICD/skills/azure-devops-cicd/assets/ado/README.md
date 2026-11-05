# Azure DevOps Library Manager

Script PowerShell para administrar Variable Groups (Azure DevOps Pipelines → Library) mediante REST API. Es una dependencia opcional del agente: cubre Libraries; no consulta repositorios, PR, pipelines o ejecuciones.

## Requisitos

- Windows PowerShell 5.1 o PowerShell 7+.
- Azure DevOps PAT y permisos para el proyecto y Variable Groups. Lecturas requieren permisos de lectura de Library; escrituras requieren `Variable Groups: Read & manage`.
- Conectividad de red con la organización.

## Instalación local

Desde la carpeta `skills/azure-devops-cicd/assets/ado/` de este repositorio personal, copia `ado-library-manager.ps1`, `ado.env.example`, `.gitignore` y este README al directorio `scripts/ado/` del repositorio/proyecto elegido. Desde ese directorio ejecuta:

```powershell
Copy-Item ado.env.example .env
notepad .env
```

Rellena `.env` localmente, sin compartir el PAT:

```dotenv
ADO_ORG=https://dev.azure.com/TU_ORGANIZACION
ADO_PROJECT=Nombre exacto o ID del proyecto
ADO_PAT=TU_PAT_LOCAL
```

Comprueba que `.env` esté ignorado por Git antes de guardar credenciales. La plantilla `.gitignore` excluye `.env`, `backups/` y `libraries/`. No guardes exports de Libraries en una carpeta que no esté protegida: algunos metadatos y valores no secretos pueden ser sensibles.

## Configuración

El script resuelve cada conexión en este orden: parámetros (`-Organization`, `-Project`, `-Pat`), variables de entorno (`ADO_ORG`, `ADO_PROJECT`, `ADO_PAT`) y fichero `.env` en la carpeta del script. No pases `-Pat` en una línea de comandos: puede quedar en historial o listado de procesos. Prefiere `.env` local ignorado o un mecanismo de secretos aprobado.

## Consultas de solo lectura

```powershell
.\ado-library-manager.ps1 -LibraryName "NOMBRE_EXACTO" -Action list
.\ado-library-manager.ps1 -LibraryName "NOMBRE_EXACTO" -Action get -Key "VARIABLE_NO_SECRETA"
```

`list` y `get` enmascaran valores que Azure DevOps marca como secretos. No se debe intentar extraerlos. El script opera sobre una Library por nombre exacto.

## Escrituras (requieren solicitud y confirmación explícitas)

El script dispone de `add`, `update`, `delete`, `import` y `export`. `export` escribe un JSON local. Para cambios, revisa primero el JSON/catálogo, confirma organización, proyecto, grupo y variables afectadas, crea backup y presenta una simulación cuando aplique:

```powershell
.\ado-library-manager.ps1 -LibraryName "NOMBRE_EXACTO" -Action import -WhatIf
```

Importar usa `merge` por defecto. `-Mode replace` reemplaza todas las variables y puede eliminar las que no estén en el JSON; úsalo solo si el usuario lo solicita claramente y confirma ese efecto. Los valores secretos no se exportan; aparecen vacíos y con `isSecret: true`. Un JSON sin valores secretos no es un backup restaurable de los secretos.

## Troubleshooting

- `401`: revisar credencial, vigencia, URL/collection, versión de API y scope.
- `403`: solicitar al administrador permisos mínimos en el proyecto/grupo.
- Library no encontrada: comprobar proyecto y nombre exacto.
- No imprimir PAT, cabeceras Authorization ni variables secretas en logs o respuestas.

Consulta la documentación central en `skills/azure-devops-cicd/README.md` de este repositorio personal y `skills/azure-devops-cicd/SKILL.md` para las reglas generales de seguridad.