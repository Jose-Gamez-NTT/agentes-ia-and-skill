# DSL JSON de `/query` y `/query/preview`

## Envelope

```json
{
  "query": { ... },
  "params": {
    "fechaDesde": "2025-01-01",
    "delegacion": "01"
  },
  "maxRows": 200,
  "timeoutSeconds": 30
}
```

También se admite poner la query directamente en la raíz; `params`, `maxRows` y `timeoutSeconds` siguen en la raíz.

## Query principal

```json
{
  "select": [ ... ],
  "from": { "table": "TFACTURA", "alias": "f" },
  "joins": [ ... ],
  "where": { ... },
  "groupBy": [ ... ],
  "having": { ... },
  "orderBy": [ ... ],
  "limit": 200,
  "offset": 0,
  "unions": [ ... ]
}
```

## Expresiones permitidas

### Columna

```json
{ "column": "f.COD_CLIENTE" }
```

### Parámetro externo

```json
{ "param": "fechaDesde" }
```

### Valor literal

```json
{ "value": "A" }
```

### Función

Funciones permitidas: `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`, `UPPER`, `LOWER`, `COALESCE`, `ABS`, `ROUND`, `TRIM`, `LTRIM`, `RTRIM`, `SUBSTR`, `LENGTH`.

```json
{ "func": "COUNT", "star": true }
```

```json
{ "func": "SUM", "args": [{ "column": "f.IMPORTE" }] }
```

```json
{ "func": "COUNT", "distinct": true, "args": [{ "column": "f.ID_CLIENTE" }] }
```

## Select

### Columnas explícitas

```json
"select": [
  { "column": "f.COD_CLIENTE", "as": "cliente" },
  { "expr": { "func": "COUNT", "star": true }, "as": "total" }
]
```

### SELECT * — todas las columnas de todas las tablas

```json
"select": [
  { "star": true }
]
```

Genera: `SELECT *`

### SELECT alias.* — todas las columnas de una tabla concreta

```json
"select": [
  { "star": "t" }
]
```

Genera: `SELECT t.*`

Se puede combinar con otras expresiones:

```json
"select": [
  { "star": "t" },
  { "expr": { "func": "COUNT", "star": true }, "as": "total" }
]
```

Genera: `SELECT t.*, COUNT(*) AS total`

> **Seguridad:** el alias de `star` pasa por la misma validación de identificador (`[A-Za-z_][A-Za-z0-9_]*`) que cualquier otro identificador. No se acepta `star: false` ni valores no textuales distintos de `true`.

## From / joins

```json
"from": { "schema": "DBA1", "table": "TFACTURA", "alias": "f" }
```

Tipos de join permitidos: `inner`, `left`, `left_outer`, `right`, `right_outer`.

```json
"joins": [
  {
    "type": "left",
    "table": "TCLIENTE",
    "alias": "c",
    "on": [
      {
        "left": { "column": "c.ID" },
        "op": "eq",
        "right": { "column": "f.ID_CLIENTE" }
      }
    ]
  }
]
```

## Condiciones

Operadores: `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `like`, `in`, `between`, `isNull`, `isNotNull`.

### AND / OR / NOT

```json
{
  "and": [
    { "left": { "column": "f.FECHA" }, "op": "gte", "right": { "param": "fechaDesde" } },
    { "left": { "column": "f.ESTADO" }, "op": "eq", "right": { "value": "A" } }
  ]
}
```

```json
{ "not": { "left": { "column": "f.BAJA" }, "op": "isNull" } }
```

### IN

```json
{
  "left": { "column": "f.DELEGACION" },
  "op": "in",
  "values": [
    { "value": "01" },
    { "value": "02" }
  ]
}
```

### BETWEEN

```json
{
  "left": { "column": "f.FECHA" },
  "op": "between",
  "values": [
    { "param": "fechaDesde" },
    { "param": "fechaHasta" }
  ]
}
```

## Group by / having / order by

```json
"groupBy": [
  { "column": "f.COD_CLIENTE" }
],
"having": {
  "left": { "func": "COUNT", "star": true },
  "op": "gt",
  "right": { "value": 1 }
},
"orderBy": [
  { "column": "cliente", "dir": "asc" },
  { "expr": { "func": "SUM", "args": [{ "column": "f.IMPORTE" }] }, "dir": "desc" }
]
```

## Unions

`orderBy`, `limit` y `offset` solo pueden ir en la query principal.

```json
{
  "select": [ ... ],
  "from": { "table": "T1", "alias": "a" },
  "unions": [
    {
      "all": true,
      "query": {
        "select": [ ... ],
        "from": { "table": "T2", "alias": "b" }
      }
    }
  ],
  "orderBy": [ { "column": "cliente", "dir": "asc" } ],
  "limit": 100
}
```

## Ejemplo completo con SELECT *

```json
{
  "query": {
    "select": [
      { "star": "t" }
    ],
    "from": { "schema": "DBA1", "table": "TINFORME", "alias": "t" },
    "where": {
      "and": [
        {
          "left": { "column": "t.IND_ONLINE" },
          "op": "eq",
          "right": { "value": "N" }
        },
        {
          "left": { "column": "t.COD_APLICACION" },
          "op": "in",
          "values": [
            { "value": "HA" },
            { "value": "BA" },
            { "value": "HP" },
            { "value": "VA" },
            { "value": "VI" }
          ]
        }
      ]
    }
  },
  "maxRows": 200,
  "timeoutSeconds": 30
}
```

## Ejemplo completo con columnas explícitas

```json
{
  "query": {
    "select": [
      { "column": "f.COD_CLIENTE", "as": "cliente" },
      { "expr": { "func": "COUNT", "star": true }, "as": "total" }
    ],
    "from": { "table": "TFACTURA", "alias": "f" },
    "joins": [
      {
        "type": "left",
        "table": "TCLIENTE",
        "alias": "c",
        "on": [
          {
            "left": { "column": "c.ID" },
            "op": "eq",
            "right": { "column": "f.ID_CLIENTE" }
          }
        ]
      }
    ],
    "where": {
      "and": [
        {
          "left": { "column": "f.FECHA" },
          "op": "gte",
          "right": { "param": "fechaDesde" }
        },
        {
          "left": { "column": "f.ESTADO" },
          "op": "eq",
          "right": { "value": "A" }
        }
      ]
    },
    "groupBy": [
      { "column": "f.COD_CLIENTE" }
    ],
    "orderBy": [
      { "column": "cliente", "dir": "asc" }
    ],
    "limit": 50
  },
  "params": {
    "fechaDesde": "2025-01-01"
  },
  "maxRows": 200,
  "timeoutSeconds": 30
}
```
