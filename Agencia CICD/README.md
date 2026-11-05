# Agencia CI/CD para Azure DevOps

Agencia portable para consultar y analizar repositorios, pull requests, pipelines, ejecuciones, despliegues y Libraries/Variable Groups de Azure DevOps. No está asociada a una organización ni proyecto concreto: cada workspace consumidor configura su propio perfil.

## Contenido

- `agents/azure-devops-cicd.md`: agente especialista, enlazado a la skill por su frontmatter.
- `commands/ado-configurar.md`: comando de proyecto que abre el flujo de configuración.
- `skills/azure-devops-cicd/SKILL.md`: instrucciones breves y navegación a referencias.
- `skills/azure-devops-cicd/README.md`: dependencias, instalación y configuración paso a paso.
- `skills/azure-devops-cicd/config/`: esquema y ejemplo de perfil Azure DevOps.
- `skills/azure-devops-cicd/assets/ado/`: gestor PowerShell opcional de Variable Groups, plantilla `.env.example`, `.gitignore` y su guía.
- `skills/azure-devops-cicd/references/`: flujo `/ado-configurar` y guía operativa.

## Dependencias

- aXet.code para cargar agentes, skills y comandos.
- Para consultar Azure DevOps: una herramienta/connector, CLI ya autenticada o API aprobada, con permisos mínimos.
- Para gestionar Variable Groups con el script incluido: PowerShell 5.1 o PowerShell 7+, red a Azure DevOps, PAT local y permisos de Library adecuados. No requiere módulos adicionales.

El script solo gestiona Variable Groups; no reemplaza un connector general y no consulta PRs, pipelines ni ejecuciones. La configuración de PAT es por workspace, nunca se incluye en esta agencia.

## Instalación en un workspace aXet.code

Desde la raíz del repositorio consumidor:

```powershell
$agency = "<ruta-local-del-repositorio-personal>/Agencia CICD"
New-Item -ItemType Directory -Force .axet-code/agents, .axet-code/skills, .axet-code/commands | Out-Null
Copy-Item "$agency/agents/azure-devops-cicd.md" ".axet-code/agents/azure-devops-cicd.md"
Copy-Item "$agency/skills/azure-devops-cicd" ".axet-code/skills/azure-devops-cicd" -Recurse -Force
Copy-Item "$agency/commands/ado-configurar.md" ".axet-code/commands/ado-configurar.md"
```

El agente aparece en el selector como `azure-devops-cicd`; la skill también puede invocarse con `%azure-devops-cicd`. El comando aparece en el picker `/` con el ID `project:ado-configurar`. Si no se detectan archivos recién copiados, inicia una nueva sesión.

Para detalles de instalación global, sigue la guía de la skill y copia agente, skill y comando a los directorios globales `agents/`, `skills/` y `commands/` de aXet.code.

## Configuración por workspace

1. En el workspace consumidor, copia `skills/azure-devops-cicd/config/ado-projects.example.json` como `.axet-code/skills/azure-devops-cicd/config/ado-projects.json`.
2. Añade organizaciones, proyectos, repositorios, pipelines y Libraries con sus nombres exactos e IDs confirmados. No pongas PATs, tokens, secretos ni valores de variables en el catálogo.
3. Usa `/ado-configurar` para verificar acceso de solo lectura y revisar una vista previa antes de cambiar el catálogo.
4. Para el gestor opcional de Libraries, sigue `skills/azure-devops-cicd/assets/ado/README.md`: copia los assets a `scripts/ado/`, crea el `.env` local desde `.env.example` y comprueba que queda ignorado por Git.

No añadas perfiles internos ni `.env` al repositorio personal. Revisa `../README.md` para el índice de agencias.
