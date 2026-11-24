# Repositorio personal de agentes y skills

Repositorio Git independiente para organizar agencias reutilizables. Cada agencia agrupa su agente, comandos, skills, documentación y assets dependientes.

## Índice de agencias

| Agencia | Descripción | Guía |
|---|---|---|
| **Agencia CICD** | Especialista en Azure DevOps: pull requests, pipelines, despliegues y Libraries/Variable Groups. | [README de Agencia CICD](Agencia%20CICD/README.md) |
| **analistaDb2** | Agente y skill para analizar consultas DB2 mediante el stack de solo lectura existente. | [README de analistaDb2](analistaDb2/README.md) |

## Estructura

```text
Agentes y otros/
├── README.md
├── analistaDb2/
│   ├── README.md
│   ├── agents/
│   └── skills/
└── Agencia CICD/
    ├── README.md
    ├── agents/
    ├── commands/
    └── skills/
```

Cada `README.md` de agencia explica su propósito, dependencias, instalación y configuración. No guardes aquí perfiles locales de organizaciones/proyectos, ficheros `.env`, PATs ni secretos.
