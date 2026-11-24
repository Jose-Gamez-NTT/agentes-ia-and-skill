package es.asisa.jasper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compila un DSL JSON controlado a SQL DB2 de solo lectura y opcionalmente lo ejecuta.
 */
public final class SafeQueryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern QUALIFIED_IDENTIFIER = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){0,2}");

    private static final Set<String> ALLOWED_JOIN_TYPES = new LinkedHashSet<>(
            Arrays.asList("INNER", "LEFT", "LEFT_OUTER", "RIGHT", "RIGHT_OUTER"));
    private static final Set<String> ALLOWED_FUNCTIONS = new LinkedHashSet<>(
            Arrays.asList("COUNT", "SUM", "AVG", "MIN", "MAX", "UPPER", "LOWER",
                    "COALESCE", "ABS", "ROUND", "TRIM", "LTRIM", "RTRIM", "SUBSTR", "LENGTH"));
    private static final Set<String> COMPARISON_OPS = new LinkedHashSet<>(
            Arrays.asList("eq", "ne", "gt", "gte", "lt", "lte", "like", "in", "between",
                    "isNull", "isNotNull"));

    private static final int DEFAULT_MAX_ROWS = 200;
    private static final int ABSOLUTE_MAX_ROWS = 1000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 120;

    private SafeQueryService() {
    }

    public static String previewJson(String body) throws Exception {
        return MAPPER.writeValueAsString(preview(body));
    }

    public static PreviewResponse preview(String body) throws Exception {
        CompiledQuery compiled = compileRequest(body);
        PreviewResponse response = new PreviewResponse();
        response.sql = compiled.sql;
        response.parameters = compiled.parameterViews();
        response.maxRows = compiled.maxRows;
        response.timeoutSeconds = compiled.timeoutSeconds;
        return response;
    }

    public static String executeJson(String body, Connection connection) throws Exception {
        return MAPPER.writeValueAsString(execute(body, connection));
    }

    public static QueryResponse execute(String body, Connection connection) throws Exception {
        CompiledQuery compiled = compileRequest(body);
        QueryResponse response = new QueryResponse();
        response.sql = compiled.sql;
        response.parameters = compiled.parameterViews();
        response.maxRows = compiled.maxRows;
        response.timeoutSeconds = compiled.timeoutSeconds;
        response.columns = new ArrayList<>();
        response.rows = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(compiled.sql)) {
            ps.setQueryTimeout(compiled.timeoutSeconds);
            ps.setMaxRows(compiled.maxRows);
            bindParameters(ps, compiled.bindings);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                for (int i = 1; i <= cols; i++) {
                    response.columns.add(meta.getColumnLabel(i));
                }
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        row.put(response.columns.get(i - 1), normalizeJdbcValue(rs.getObject(i)));
                    }
                    response.rows.add(row);
                }
            }
        }
        response.rowCount = response.rows.size();
        return response;
    }

    private static CompiledQuery compileRequest(String body) throws Exception {
        JsonNode root = parseObject(body, "El body debe ser un JSON objeto.");
        JsonNode queryNode = root.has("query") ? root.get("query") : root;
        if (!(queryNode instanceof ObjectNode)) {
            throw new IllegalArgumentException("El nodo 'query' debe ser un objeto JSON.");
        }
        ObjectNode paramsNode = root.path("params") instanceof ObjectNode
                ? (ObjectNode) root.path("params")
                : MAPPER.createObjectNode();

        int maxRows = parseBoundedInt(root.path("maxRows"), DEFAULT_MAX_ROWS, 1, ABSOLUTE_MAX_ROWS, "maxRows");
        int timeout = parseBoundedInt(root.path("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS,
                1, MAX_TIMEOUT_SECONDS, "timeoutSeconds");

        CompileContext ctx = new CompileContext(paramsNode, maxRows, timeout);
        String sql = compileQuery((ObjectNode) queryNode, ctx, true);
        return new CompiledQuery(sql, ctx.bindings, maxRows, timeout);
    }

    private static String compileQuery(ObjectNode query, CompileContext ctx, boolean allowTailClauses) {
        String sql = compileCore(query, ctx);
        JsonNode unions = query.path("unions");
        if (!unions.isMissingNode() && !unions.isNull()) {
            if (!unions.isArray() || unions.isEmpty()) {
                throw new IllegalArgumentException("'unions' debe ser un array no vacío.");
            }
            StringBuilder unionSql = new StringBuilder().append('(').append(sql).append(')');
            for (JsonNode unionNode : unions) {
                ObjectNode unionObj = requireObject(unionNode, "Cada elemento de 'unions' debe ser un objeto.");
                boolean all = unionObj.path("all").asBoolean(false);
                ObjectNode subQuery = requireObject(unionObj.get("query"), "Cada union requiere un objeto 'query'.");
                unionSql.append(all ? " UNION ALL " : " UNION ")
                        .append('(')
                        .append(compileQuery(subQuery, ctx, false))
                        .append(')');
            }
            sql = unionSql.toString();
        }

        if (allowTailClauses) {
            sql = appendOrderAndLimit(sql, query, ctx);
        } else {
            rejectTailClauses(query);
        }
        return sql;
    }

    private static String compileCore(ObjectNode query, CompileContext ctx) {
        JsonNode selectNode = query.get("select");
        if (selectNode == null || !selectNode.isArray() || selectNode.isEmpty()) {
            throw new IllegalArgumentException("'select' es obligatorio y debe ser un array no vacío.");
        }
        ObjectNode fromNode = requireObject(query.get("from"), "'from' es obligatorio y debe ser un objeto.");

        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(compileSelect(selectNode, ctx));
        sql.append(" FROM ").append(compileSource(fromNode));

        JsonNode joinsNode = query.path("joins");
        if (!joinsNode.isMissingNode() && !joinsNode.isNull()) {
            if (!joinsNode.isArray()) {
                throw new IllegalArgumentException("'joins' debe ser un array.");
            }
            for (JsonNode joinNode : joinsNode) {
                sql.append(compileJoin(requireObject(joinNode, "Cada join debe ser un objeto."), ctx));
            }
        }

        JsonNode whereNode = query.get("where");
        if (whereNode != null && !whereNode.isNull()) {
            sql.append(" WHERE ").append(compileCondition(whereNode, ctx));
        }

        JsonNode groupByNode = query.path("groupBy");
        if (!groupByNode.isMissingNode() && !groupByNode.isNull()) {
            if (!groupByNode.isArray() || groupByNode.isEmpty()) {
                throw new IllegalArgumentException("'groupBy' debe ser un array no vacío.");
            }
            sql.append(" GROUP BY ").append(compileExpressionList(groupByNode, ctx));
        }

        JsonNode havingNode = query.get("having");
        if (havingNode != null && !havingNode.isNull()) {
            sql.append(" HAVING ").append(compileCondition(havingNode, ctx));
        }
        return sql.toString();
    }

    private static String compileSelect(JsonNode selectNode, CompileContext ctx) {
        List<String> parts = new ArrayList<>();
        for (JsonNode node : selectNode) {
            ObjectNode item = requireObject(node, "Cada elemento de 'select' debe ser un objeto.");
            // { "star": true }  →  SELECT *
            // { "star": "alias" }  →  SELECT alias.*
            if (item.has("star") && !item.has("expr") && !item.has("column") && !item.has("func") && !item.has("value") && !item.has("param")) {
                JsonNode starNode = item.get("star");
                if (starNode.isBoolean()) {
                    if (!starNode.asBoolean()) {
                        throw new IllegalArgumentException("select.star debe ser true o un alias válido.");
                    }
                    parts.add("*");
                } else if (starNode.isTextual()) {
                    String alias = starNode.asText().trim();
                    parts.add(safeIdentifier(alias, "select.star") + ".*");
                } else {
                    throw new IllegalArgumentException("select.star debe ser true o un string con el alias de tabla.");
                }
                continue;
            }
            JsonNode exprNode = item.has("expr") ? item.get("expr") : item;
            String expr = compileExpression(exprNode, ctx);
            if (item.has("as")) {
                expr += " AS " + safeIdentifier(text(item.get("as"), "select.as"), "select.as");
            }
            parts.add(expr);
        }
        return String.join(", ", parts);
    }

    private static String compileJoin(ObjectNode join, CompileContext ctx) {
        String joinType = join.has("type") ? text(join.get("type"), "joins.type") : "INNER";
        String normalized = joinType.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!ALLOWED_JOIN_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Tipo de join no permitido: " + joinType);
        }
        String sqlJoinType = normalized.replace('_', ' ');
        StringBuilder sb = new StringBuilder().append(' ').append(sqlJoinType).append(" JOIN ")
                .append(compileSource(join));
        JsonNode onNode = join.get("on");
        if (onNode == null || onNode.isNull()) {
            throw new IllegalArgumentException("Cada join requiere 'on'.");
        }
        if (onNode.isArray()) {
            List<String> conditions = new ArrayList<>();
            for (JsonNode cond : onNode) {
                conditions.add(compileCondition(cond, ctx));
            }
            if (conditions.isEmpty()) {
                throw new IllegalArgumentException("'join.on' no puede estar vacío.");
            }
            sb.append(" ON ").append(String.join(" AND ", conditions));
        } else {
            sb.append(" ON ").append(compileCondition(onNode, ctx));
        }
        return sb.toString();
    }

    private static String compileSource(ObjectNode source) {
        String table = text(source.get("table"), "table");
        String qualified = qualifyTable(source.path("schema"), table);
        String alias = source.has("alias") ? text(source.get("alias"), "alias") : "";
        return alias.isEmpty() ? qualified : qualified + ' ' + safeIdentifier(alias, "alias");
    }

    private static String appendOrderAndLimit(String sql, ObjectNode query, CompileContext ctx) {
        StringBuilder sb = new StringBuilder(sql);
        JsonNode orderByNode = query.path("orderBy");
        if (!orderByNode.isMissingNode() && !orderByNode.isNull()) {
            if (!orderByNode.isArray() || orderByNode.isEmpty()) {
                throw new IllegalArgumentException("'orderBy' debe ser un array no vacío.");
            }
            List<String> items = new ArrayList<>();
            for (JsonNode node : orderByNode) {
                ObjectNode item = requireObject(node, "Cada elemento de 'orderBy' debe ser un objeto.");
                String expr;
                if (item.has("expr")) {
                    expr = compileExpression(item.get("expr"), ctx);
                } else if (item.has("column")) {
                    expr = safeQualifiedIdentifier(text(item.get("column"), "orderBy.column"), "orderBy.column");
                } else {
                    throw new IllegalArgumentException("Cada orderBy requiere 'expr' o 'column'.");
                }
                String dir = item.has("dir") ? item.get("dir").asText("ASC") : "ASC";
                String normalized = dir.trim().toUpperCase(Locale.ROOT);
                if (!"ASC".equals(normalized) && !"DESC".equals(normalized)) {
                    throw new IllegalArgumentException("Dirección de orderBy no permitida: " + dir);
                }
                items.add(expr + ' ' + normalized);
            }
            sb.append(" ORDER BY ").append(String.join(", ", items));
        }

        int offset = parseBoundedInt(query.path("offset"), 0, 0, Integer.MAX_VALUE, "offset");
        int requestedLimit = parseBoundedInt(query.path("limit"), ctx.maxRows, 1, ctx.maxRows, "limit");
        if (offset > 0) {
            sb.append(" OFFSET ").append(offset).append(" ROWS FETCH NEXT ")
                    .append(requestedLimit).append(" ROWS ONLY");
        } else {
            sb.append(" FETCH FIRST ").append(requestedLimit).append(" ROWS ONLY");
        }
        return sb.toString();
    }

    private static void rejectTailClauses(ObjectNode query) {
        for (String field : Arrays.asList("orderBy", "limit", "offset")) {
            if (query.has(field)) {
                throw new IllegalArgumentException("'" + field + "' solo está permitido en la query principal.");
            }
        }
    }

    private static String compileExpressionList(JsonNode arrayNode, CompileContext ctx) {
        List<String> parts = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            parts.add(compileExpression(node, ctx));
        }
        return String.join(", ", parts);
    }

    private static String compileExpression(JsonNode node, CompileContext ctx) {
        ObjectNode expr = requireObject(node, "Cada expresión debe ser un objeto.");
        if (expr.has("column")) {
            return safeQualifiedIdentifier(text(expr.get("column"), "column"), "column");
        }
        if (expr.has("param")) {
            String name = text(expr.get("param"), "param");
            JsonNode valueNode = ctx.params.get(name);
            if (valueNode == null || valueNode instanceof MissingNode || valueNode.isMissingNode()) {
                throw new IllegalArgumentException("Parámetro no encontrado en 'params': " + name);
            }
            ctx.bindings.add(new Binding(name, toJavaValue(valueNode)));
            return "?";
        }
        if (expr.has("value")) {
            ctx.bindings.add(new Binding(null, toJavaValue(expr.get("value"))));
            return "?";
        }
        if (expr.has("func")) {
            String func = text(expr.get("func"), "func").trim().toUpperCase(Locale.ROOT);
            if (!ALLOWED_FUNCTIONS.contains(func)) {
                throw new IllegalArgumentException("Función no permitida: " + func);
            }
            boolean star = expr.path("star").asBoolean(false);
            boolean distinct = expr.path("distinct").asBoolean(false);
            JsonNode argsNode = expr.path("args");
            List<String> args = new ArrayList<>();
            if (star) {
                if (!"COUNT".equals(func)) {
                    throw new IllegalArgumentException("Solo COUNT permite star=true.");
                }
                args.add("*");
            } else {
                if (!argsNode.isArray() || argsNode.isEmpty()) {
                    throw new IllegalArgumentException("La función " + func + " requiere 'args'.");
                }
                for (JsonNode arg : argsNode) {
                    args.add(compileExpression(arg, ctx));
                }
            }
            if (distinct) {
                if (args.size() != 1) {
                    throw new IllegalArgumentException("distinct solo se admite con un argumento.");
                }
                args.set(0, "DISTINCT " + args.get(0));
            }
            return func + '(' + String.join(", ", args) + ')';
        }
        throw new IllegalArgumentException("Expresión no soportada. Usa 'column', 'param', 'value' o 'func'.");
    }

    private static String compileCondition(JsonNode node, CompileContext ctx) {
        ObjectNode condition = requireObject(node, "Cada condición debe ser un objeto.");
        if (condition.has("and")) {
            return joinLogical(condition.get("and"), ctx, "AND");
        }
        if (condition.has("or")) {
            return joinLogical(condition.get("or"), ctx, "OR");
        }
        if (condition.has("not")) {
            return "(NOT " + compileCondition(condition.get("not"), ctx) + ')';
        }

        String op = text(condition.get("op"), "op");
        if (!COMPARISON_OPS.contains(op)) {
            throw new IllegalArgumentException("Operador no permitido: " + op);
        }
        String left = compileExpression(condition.get("left"), ctx);
        switch (op) {
            case "eq":
                return '(' + left + " = " + compileExpression(condition.get("right"), ctx) + ')';
            case "ne":
                return '(' + left + " <> " + compileExpression(condition.get("right"), ctx) + ')';
            case "gt":
                return '(' + left + " > " + compileExpression(condition.get("right"), ctx) + ')';
            case "gte":
                return '(' + left + " >= " + compileExpression(condition.get("right"), ctx) + ')';
            case "lt":
                return '(' + left + " < " + compileExpression(condition.get("right"), ctx) + ')';
            case "lte":
                return '(' + left + " <= " + compileExpression(condition.get("right"), ctx) + ')';
            case "like":
                return '(' + left + " LIKE " + compileExpression(condition.get("right"), ctx) + ')';
            case "isNull":
                return '(' + left + " IS NULL)";
            case "isNotNull":
                return '(' + left + " IS NOT NULL)";
            case "in": {
                JsonNode values = condition.get("values");
                if (values == null || !values.isArray() || values.isEmpty()) {
                    throw new IllegalArgumentException("El operador 'in' requiere 'values' como array no vacío.");
                }
                List<String> compiled = new ArrayList<>();
                for (JsonNode valueNode : values) {
                    compiled.add(compileExpression(valueNode, ctx));
                }
                return '(' + left + " IN (" + String.join(", ", compiled) + "))";
            }
            case "between": {
                JsonNode values = condition.get("values");
                if (values == null || !values.isArray() || values.size() != 2) {
                    throw new IllegalArgumentException("El operador 'between' requiere 'values' con dos elementos.");
                }
                return '(' + left + " BETWEEN " + compileExpression(values.get(0), ctx)
                        + " AND " + compileExpression(values.get(1), ctx) + ')';
            }
            default:
                throw new IllegalArgumentException("Operador no implementado: " + op);
        }
    }

    private static String joinLogical(JsonNode node, CompileContext ctx, String operator) {
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("El bloque lógico requiere un array no vacío.");
        }
        List<String> compiled = new ArrayList<>();
        for (JsonNode child : node) {
            compiled.add(compileCondition(child, ctx));
        }
        return '(' + String.join(" " + operator + " ", compiled) + ')';
    }

    private static void bindParameters(PreparedStatement ps, List<Binding> bindings) throws SQLException {
        for (int i = 0; i < bindings.size(); i++) {
            Object value = bindings.get(i).value;
            int param = i + 1;
            if (value == null) {
                ps.setObject(param, null);
            } else if (value instanceof Integer) {
                ps.setInt(param, (Integer) value);
            } else if (value instanceof Long) {
                ps.setLong(param, (Long) value);
            } else if (value instanceof Double) {
                ps.setDouble(param, (Double) value);
            } else if (value instanceof Float) {
                ps.setFloat(param, (Float) value);
            } else if (value instanceof BigDecimal) {
                ps.setBigDecimal(param, (BigDecimal) value);
            } else if (value instanceof Boolean) {
                ps.setBoolean(param, (Boolean) value);
            } else if (value instanceof LocalDate) {
                ps.setDate(param, java.sql.Date.valueOf((LocalDate) value));
            } else if (value instanceof LocalDateTime) {
                ps.setTimestamp(param, Timestamp.valueOf((LocalDateTime) value));
            } else if (value instanceof LocalTime) {
                ps.setTime(param, Time.valueOf((LocalTime) value));
            } else if (value instanceof byte[]) {
                ps.setBytes(param, (byte[]) value);
            } else {
                ps.setObject(param, value);
            }
        }
    }

    private static Object normalizeJdbcValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date || value instanceof Time || value instanceof Timestamp) {
            return value.toString();
        }
        if (value instanceof byte[]) {
            return Base64.getEncoder().encodeToString((byte[]) value);
        }
        return value;
    }

    private static ObjectNode parseObject(String body, String error) throws JsonProcessingException {
        JsonNode root = MAPPER.readTree(body == null ? "{}" : body);
        if (!(root instanceof ObjectNode)) {
            throw new IllegalArgumentException(error);
        }
        return (ObjectNode) root;
    }

    private static ObjectNode requireObject(JsonNode node, String message) {
        if (!(node instanceof ObjectNode)) {
            throw new IllegalArgumentException(message);
        }
        return (ObjectNode) node;
    }

    private static String text(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            throw new IllegalArgumentException("El campo '" + fieldName + "' es obligatorio.");
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("El campo '" + fieldName + "' no puede estar vacío.");
        }
        return value;
    }

    private static int parseBoundedInt(JsonNode node, int defaultValue, int min, int max, String fieldName) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaultValue;
        }
        if (!node.canConvertToInt()) {
            throw new IllegalArgumentException("El campo '" + fieldName + "' debe ser numérico.");
        }
        int value = node.asInt();
        if (value < min || value > max) {
            throw new IllegalArgumentException("El campo '" + fieldName + "' debe estar entre " + min + " y " + max + '.');
        }
        return value;
    }

    private static String qualifyTable(JsonNode schemaNode, String table) {
        String safeTable = safeIdentifier(table, "table");
        if (schemaNode == null || schemaNode.isNull() || schemaNode.isMissingNode()) {
            return safeTable;
        }
        String schema = text(schemaNode, "schema");
        return safeIdentifier(schema, "schema") + '.' + safeTable;
    }

    private static String safeIdentifier(String value, String fieldName) {
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Identificador no válido en '" + fieldName + "': " + value);
        }
        return value;
    }

    private static String safeQualifiedIdentifier(String value, String fieldName) {
        if (!QUALIFIED_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Identificador cualificado no válido en '" + fieldName + "': " + value);
        }
        return value;
    }

    private static Object toJavaValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isInt()) {
            return node.intValue();
        }
        if (node.isLong()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        throw new IllegalArgumentException("Solo se permiten valores escalares en 'params' y 'value'.");
    }

    private static final class CompileContext {
        final ObjectNode params;
        final List<Binding> bindings = new ArrayList<>();
        final int maxRows;
        final int timeoutSeconds;

        private CompileContext(ObjectNode params, int maxRows, int timeoutSeconds) {
            this.params = params;
            this.maxRows = maxRows;
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    private static final class CompiledQuery {
        final String sql;
        final List<Binding> bindings;
        final int maxRows;
        final int timeoutSeconds;

        private CompiledQuery(String sql, List<Binding> bindings, int maxRows, int timeoutSeconds) {
            this.sql = sql;
            this.bindings = Collections.unmodifiableList(new ArrayList<>(bindings));
            this.maxRows = maxRows;
            this.timeoutSeconds = timeoutSeconds;
        }

        private List<ParameterView> parameterViews() {
            List<ParameterView> params = new ArrayList<>();
            for (int i = 0; i < bindings.size(); i++) {
                Binding binding = bindings.get(i);
                ParameterView view = new ParameterView();
                view.index = i + 1;
                view.name = binding.name;
                view.value = binding.value;
                view.type = binding.value == null ? "null" : binding.value.getClass().getSimpleName();
                params.add(view);
            }
            return params;
        }
    }

    private static final class Binding {
        final String name;
        final Object value;

        private Binding(String name, Object value) {
            this.name = name;
            this.value = value;
        }
    }

    public static class PreviewResponse {
        public String sql;
        public List<ParameterView> parameters;
        public int maxRows;
        public int timeoutSeconds;
    }

    public static final class QueryResponse extends PreviewResponse {
        public List<String> columns;
        public int rowCount;
        public List<Map<String, Object>> rows;
    }

    public static final class ParameterView {
        public int index;
        public String name;
        public Object value;
        public String type;
    }
}
