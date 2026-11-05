---
name: azure-devops-cicd
description: Use when the user asks about Azure DevOps repositories, pull requests, CI/CD pipelines and runs, deployments, environments, Library/Variable Groups, or configuration and installation of this skill.
argument-hint: "/ado-configurar [organización/proyecto opcional]"
---

# Azure DevOps CI/CD

## Cuándo activarla

Actívala para investigar Azure DevOps, explicar circuitos CI/CD, revisar archivos de pipelines/deployment, o consultar/configurar repositorios, PRs, ejecuciones, environments, releases y Libraries/Variable Groups. El perfil es **genérico**: no presupone organización ni contiene un catálogo de proyectos concreto.

## Recursos de esta skill

- `README.md`: instalación del agente y la skill, dependencias y configuración paso a paso.
- `references/ado-configurar-workflow.md`: definición completa del flujo `/ado-configurar`. El prompt corto copiable está en la agencia: `../../commands/ado-configurar.md`.
- `config/ado-projects.example.json`: plantilla portable de organizaciones, proyectos y recursos; copiar al workspace consumidor como `.axet-code/skills/azure-devops-cicd/config/ado-projects.json` y rellenar solo metadatos no secretos.
- `config/ado-projects.schema.json`: esquema para validar el catálogo.
- `assets/ado/ado.env.example`, `.gitignore`, `ado-library-manager.ps1`: autenticación local y gestor opcional de Variable Groups. Ver su README para límites y uso.
- `references/azure-devops-operations.md`: guía de consulta, permisos y seguridad operativa.

## Flujo `/ado-configurar`

Al invocarlo, lee y sigue íntegramente `references/ado-configurar-workflow.md`. El catálogo de conexiones se mantiene fuera del prompt del agente, en `config/ado-projects.json`; la definición del flujo se mantiene en la referencia, no se copia al agente. El custom command distribuible está en `../../commands/ado-configurar.md`, en la raíz de la agencia.

## Comportamiento mínimo del agente

1. Determina organización, proyecto y recurso exactos usando el catálogo local si está configurado; verifica los datos contra Azure DevOps cuando la consulta remota esté disponible. No confundas GitHub con Azure Repos ni deduzcas proyecto por el nombre de un repo.
2. Distingue inspección de solo lectura de cualquier operación que cambie estado. Usa únicamente herramientas realmente disponibles y permisos mínimos; informa si solo hay acceso local o no existe connector/API accesible.
3. No afirmes que consultaste datos remotos sin obtener una respuesta confirmada. Indica recurso consultado, frescura relevante y limitaciones de permisos o paginación.
4. Protege credenciales: jamás expongas PATs, tokens, contraseñas o valores de variables secretas. Para Variable Groups muestra nombres, flags de secreto y metadatos no sensibles, nunca valores secretos.
5. No lances pipelines, despliegues ni operaciones de escritura (PRs, policies, permisos, variables, Library) sin autorización explícita para la acción concreta. Antes de actuar explica recurso, alcance, efecto y riesgo; previsualiza si existe modo seguro; verifica resultado después.
6. Para editar YAML o configuración local, revisa contexto, conserva cambios ajenos y valida sintaxis. No cambies configuración remota como efecto lateral de una explicación.

## Seguridad de Variable Groups

El gestor incluido utiliza `ADO_ORG`, `ADO_PROJECT` y `ADO_PAT`, resueltos desde parámetros, variables de entorno o `.env` local. El `.env` se mantiene fuera de Git. Las operaciones `list`/`get` son de consulta y el script enmascara secretos; `add`, `update`, `delete` e `import` modifican Azure DevOps. `import` usa `merge` por defecto; `replace` puede eliminar claves no presentes y requiere petición y confirmación explícitas. Sigue `assets/ado/README.md` y `references/azure-devops-operations.md` antes de usarlo.

## Errores de acceso

Distingue `401` (autenticación, URL o versión/API no aceptada), `403` (permisos insuficientes) y `404`/proyecto no encontrado (ID, URL, colección, nombre o visibilidad). Los permisos de repositorios, build/deployment y Variable Groups pueden diferir. No atribuyas causa no demostrada ni intentes eludir controles; reporta el código/error no sensible y el mínimo permiso que debe comprobar el administrador.