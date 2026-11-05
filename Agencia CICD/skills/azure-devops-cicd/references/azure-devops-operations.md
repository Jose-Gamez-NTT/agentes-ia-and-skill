# Referencia operativa de Azure DevOps

## Tipo de recurso y lectura

| Área | Información que puede consultarse | Nota |
|---|---|---|
| Repos | Repositorios, ramas, refs, commits, políticas visibles | Azure Repos y GitHub son proveedores distintos; valida la remota. |
| Pull Requests | Estado, título, source/target, autores, revisores, votos, threads y cambios | La API requiere el ID del repositorio y la paginación adecuada. |
| Pipelines/Build | Definiciones, carpetas, YAML asociado, triggers, ejecuciones, resultado, logs y artefactos | Consulta definitions y runs por separado; no ejecutar sin autorización. |
| Releases/Deployments | Releases clásicos, environments, stages, checks y despliegues | La visibilidad depende del tipo de pipeline y permisos. |
| Libraries | Variable Groups, proyectos autorizados, nombres de variables y marca secreta | Los valores secretos no deben consultarse ni presentarse. |

Las herramientas disponibles en una sesión determinan si es posible hacer esas consultas. La existencia de un script de Libraries no equivale a disponer de un connector universal. Si no existe API/connector/CLI utilizable, explica el límite y ofrece el procedimiento local sin afirmar acceso remoto.

## Permisos y autenticación

- Usa credenciales mantenidas fuera del código versionado. En el paquete, `.env` es una plantilla local protegida por `.gitignore`; en entornos corporativos prefiere un secret store aprobado.
- Aplica mínimo privilegio. PAT scopes y permisos ACL del proyecto son controles distintos: un token con scope amplio aún necesita acceso al proyecto/recurso; ser miembro del proyecto no implica gestionar Variable Groups.
- Comprueba scopes según tarea. Ejemplos: Code (Read) para repos/PR de lectura; Build (Read) para definiciones y runs; Variable Groups/Library (Read) para consulta y Read & manage para cambios; Release/Environment scopes solo si esos recursos se usan.
- No pidas scopes de escritura para una revisión de solo lectura. Nunca solicites que el usuario pegue un token en el chat.
- No registres cabeceras Authorization, tokens, secretos o bodies completos con datos sensibles.

## Diagnóstico HTTP

- `401 Unauthorized`: revisar autenticación, PAT inválido/expirado, esquema Basic/token, organización/collection o endpoint. No inferir una sola causa.
- `403 Forbidden`: identidad autenticada pero sin permiso para ese proyecto, grupo o acción; solicitar al administrador que revise el permiso mínimo específico.
- `404 Not Found` / `ProjectDoesNotExist`: verificar organización/collection, proyecto exacto, URL-encoding e ID. Algunos endpoints pueden devolver 404 si el recurso no es visible.
- `400 Bad Request`: revisar query string y versión API, campos obligatorios e IDs; inspeccionar respuesta sin exponer datos sensibles.
- Respuestas distintas por API: no asumir permisos uniformes. Registrar qué endpoint funcionó y cuál falló.

## Configuración del catálogo

Mantén el perfil local en `.axet-code/skills/azure-devops-cicd/config/ado-projects.json` del workspace consumidor, derivado de `config/ado-projects.example.json` de la skill instalada. Es información no secreta con esta estructura:

```json
{
  "$schema": "./ado-projects.schema.json",
  "organizations": [
    {
      "id": "org-id-or-alias",
      "name": "Nombre legible",
      "url": "https://dev.azure.com/ORG",
      "projects": [
        {
          "id": "project-id",
          "name": "Nombre exacto",
          "repositories": [{ "id": "repo-id", "name": "repo", "defaultBranch": "refs/heads/main" }],
          "pipelines": [{ "id": "42", "name": "Build", "path": "\\" }],
          "libraries": [{ "id": "7", "name": "Variables-PRU" }]
        }
      ]
    }
  ]
}
```

Mantén IDs de proyecto/recurso cuando se conozcan, especialmente para proyectos con acentos o nombres ambiguos. Nunca incluyas PAT, valores de variables, URLs con credenciales o secretos de conexión. Etiqueta claramente entradas que no se han validado remotamente. El flujo `/ado-configurar` muestra los cambios propuestos y espera aprobación antes de actualizar el catálogo del workspace consumidor.

## Circuito de análisis CI/CD

1. Identifica repositorio y branch source/target, PR asociado y políticas requeridas.
2. Localiza la definición del pipeline, YAML y templates referenciados, y determina triggers (CI, PR, schedules, manual/resource triggers).
3. Sigue stages/jobs/tasks: restore/build, pruebas, análisis, publicación de artefactos/imagen, promoción y despliegue.
4. Identifica inputs externos: variable groups, secure files, service connections, environments, approvals/checks, agent pools y templates centrales. No consultes secretos.
5. Une ejecuciones con commit/branch/PR y extrae resultado/logs pertinentes, evitando volcados con tokens/config confidencial.
6. Comprueba condiciones, dependencias, timeouts, retries, estrategia de rollback y diferencias entre entornos.
7. Comunica hallazgos observados, inferencias separadas, riesgos, permisos que impiden verificar y siguientes pasos seguros.

## Guía de cambios

- Solo cambia pipeline/configuración local con petición explícita y revisión del alcance; valida YAML/JSON y revisa diff.
- Para variables, confirma el grupo correcto, scope por entorno y posibles definiciones homónimas antes de modificar.
- Para imports, crea backup legible antes de aplicarlos, ejecuta WhatIf y verifica que no se pierdan variables ajenas. `replace` es especialmente destructivo y requiere confirmación explícita separada.
- Para ejecutar pipelines, desplegar, completar PR, editar policy o cambiar credenciales, presenta primero recurso, parámetros, entorno, impacto y plan de reversión; espera aprobación para esa operación concreta.
- Verifica el estado remoto tras la acción y reporta resultado; si falla, no repitas una escritura automáticamente sin revisar idempotencia/efectos parciales.
