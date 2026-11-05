# Flujo de configuración `/ado-configurar`

Usa este flujo para configurar o refrescar una instalación genérica de la skill. Mantén perfiles por organización/proyecto en `.axet-code/skills/azure-devops-cicd/config/ado-projects.json` dentro del workspace consumidor; no incrustes proyectos concretos en el prompt del agente ni en esta skill del repositorio personal.

## Pasos obligatorios

1. Lee la guía `README.md` de la skill, el catálogo `.axet-code/skills/azure-devops-cicd/config/ado-projects.json` del workspace si existe y `assets/ado/README.md` si se va a configurar el gestor de Libraries. Nunca leas ni muestres el valor del PAT.
2. Identifica el destino. Si el usuario no lo indica, presenta organizaciones/proyectos conocidos desde el catálogo. Si no hay catálogo, ofrece descubrirlos con la conexión local disponible o solicita URL de organización y proyecto exactos. Si hay varios candidatos razonables, pide al usuario elegir; no adivines por el nombre de repo.
3. Verifica la conectividad con operaciones de solo lectura y herramientas realmente disponibles. Si no existe API/connector configurado, explica las opciones locales sin instalar herramientas ni cambiar permisos por tu cuenta.
4. Descubre nombres e IDs de proyectos, repositorios, pipelines y Variable Groups visibles. Marca cada dato `confirmado remotamente`, `configurado localmente`, `no accesible` o `pendiente`. No guardes ejecuciones efímeras ni valores de variables.
5. Presenta una vista previa de cambios al catálogo: organización, proyecto, IDs, recursos nuevos/cambiados y errores de acceso. Espera aprobación explícita antes de escribir en `.axet-code/skills/azure-devops-cicd/config/ado-projects.json` del workspace consumidor.
6. Tras aprobar, actualiza solo las secciones afectadas del catálogo en el workspace consumidor, conserva las demás entradas y valida contra el esquema de la skill instalada.
7. Informa qué se confirmó y qué quedó inaccesible. Este flujo no modifica Azure DevOps, `.env`, PAT ni definiciones de pipeline.

## Catálogo configurable

Crea `.axet-code/skills/azure-devops-cicd/config/ado-projects.json` copiando `config/ado-projects.example.json` desde la agencia instalada. Cada organización agrupa proyectos; cada proyecto puede contener `repositories`, `pipelines` y `libraries`. Las entradas tienen `id`, `name` y, opcionalmente, `defaultBranch`, `path` o `notes`. El catálogo admite arrays vacíos hasta que se descubran recursos. Solo guarda metadatos no sensibles.

## Si la API no está disponible

No inventes catálogo ni afirmes que se validó la conexión. Informa el diagnóstico de acceso sin exponer credenciales, deja el catálogo del workspace sin cambios y detalla qué corrección de autenticación/permisos o mecanismo aprobado debe proporcionar el usuario.