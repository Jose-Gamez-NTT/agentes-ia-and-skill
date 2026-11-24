package es.asisa.jasper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.xml.sax.SAXParseException;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servidor HTTP standalone para DB2 en solo lectura.
 */
public class ReportHttpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("server.port", String.valueOf(DEFAULT_PORT)));
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 20);
        server.createContext("/generate", ReportHttpServer::handleGenerate);
        server.createContext("/query", ReportHttpServer::handleQuery);
        server.createContext("/query/preview", ReportHttpServer::handleQueryPreview);
        server.createContext("/health", ReportHttpServer::handleHealth);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("DB2 Report Server Standalone → http://localhost:" + port);
        System.out.println("  POST /generate         genera PDF Jasper contra DB2");
        System.out.println("  POST /query            ejecuta DSL JSON de solo lectura");
        System.out.println("  POST /query/preview    compila DSL JSON y devuelve SQL");
        System.out.println("  GET  /health           health check");
        System.out.println("(Ctrl+C para detener)");
    }

    private static void handleHealth(HttpExchange ex) throws IOException {
        sendJson(ex, 200, "{\"status\":\"ok\"}");
    }

    private static void handleQueryPreview(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, jsonError("Método no permitido, usa POST"));
            return;
        }
        try {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            sendJson(ex, 200, SafeQueryService.previewJson(body));
        } catch (Throwable e) {
            sendJson(ex, 400, jsonError(buildErrorChain(e)));
        }
    }

    private static void handleQuery(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, jsonError("Método no permitido, usa POST"));
            return;
        }
        JasperDb2Runner runner = null;
        try {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            runner = new JasperDb2Runner(Db2Config.fromSystemProperties());
            runner.start();
            sendJson(ex, 200, SafeQueryService.executeJson(body, runner.getConnection()));
        } catch (Throwable e) {
            sendJson(ex, 400, jsonError(buildErrorChain(e)));
        } finally {
            if (runner != null) {
                try { runner.stop(); } catch (Exception ignored) {}
            }
        }
    }

    private static void handleGenerate(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, jsonError("Método no permitido, usa POST"));
            return;
        }

        Thread.currentThread().setContextClassLoader(ReportHttpServer.class.getClassLoader());

        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("db2_report_srv_");
            String ct = Optional.ofNullable(ex.getRequestHeaders().getFirst("Content-Type")).orElse("");

            GenerateRequest req = parseGenerateRequest(ex, ct, tmpDir);
            byte[] pdfBytes = generatePdf(req.mainFile, req.resourceDir, req.subreports, req.params, tmpDir);
            ex.getResponseHeaders().set("Content-Type", "application/pdf");
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"report.pdf\"");
            ex.sendResponseHeaders(200, pdfBytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(pdfBytes);
            }
        } catch (Exception e) {
            System.err.println("[ERROR] " + new java.util.Date() + " — excepción durante generación de PDF:");
            e.printStackTrace(System.err);
            sendJson(ex, 500, jsonError(buildErrorChain(e)));
        } finally {
            ex.close();
            if (tmpDir != null) deleteQuietly(tmpDir);
        }
    }

    private static byte[] generatePdf(File mainFile, File resourceDir, List<File> explicitSubreports,
                                      Map<String, Object> explicitParams, Path tmpDir) throws Exception {
        List<File> subreports = explicitSubreports != null ? explicitSubreports : JrxmlSupport.detectSubreports(mainFile);
        List<JrxmlSupport.ParamDef> paramDefs = JrxmlSupport.extractParams(mainFile);

        Path compiledDir = tmpDir.resolve("compiled");
        Path outputDir = tmpDir.resolve("output");

        JasperDb2Runner runner = new JasperDb2Runner(Db2Config.fromSystemProperties());
        runner.start();
        try {
            for (File sub : subreports) {
                runner.compileReportFromFile(sub, compiledDir, sub.getName().replace(".jrxml", ".jasper"));
            }

            String jasperName = mainFile.getName().replace(".jrxml", ".jasper");
            runner.compileReportFromFile(mainFile, compiledDir, jasperName);
            JrxmlSupport.createMissingImagePlaceholders(mainFile, resourceDir);

            Map<String, Object> params = buildParams(paramDefs, explicitParams, compiledDir, !subreports.isEmpty());
            Path jasperFile = compiledDir.resolve(jasperName);
            Path pdfPath = outputDir.resolve("report.pdf");
            runner.generatePdf(jasperFile, params, pdfPath, resourceDir);
            return Files.readAllBytes(pdfPath);
        } finally {
            runner.stop();
        }
    }

    private static Map<String, Object> buildParams(List<JrxmlSupport.ParamDef> defs,
                                                   Map<String, Object> explicitParams,
                                                   Path compiledDir,
                                                   boolean hasSubreports) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (defs != null) {
            for (JrxmlSupport.ParamDef pd : defs) {
                Object value = explicitParams != null && explicitParams.containsKey(pd.name)
                        ? explicitParams.get(pd.name)
                        : coerceSuggested(pd.javaClass, pd.suggestedValue);
                params.put(pd.name, value);
            }
        }
        if (explicitParams != null) {
            params.putAll(explicitParams);
        }
        if (hasSubreports) {
            params.put("SUBREPORT_DIR", compiledDir.toAbsolutePath().toString() + "/");
        }
        return params;
    }

    private static Object coerceSuggested(String javaClass, String value) {
        String cls = javaClass == null ? "java.lang.String" : javaClass;
        String safe = value == null ? "" : value;
        if (cls.contains("Integer") || cls.contains("Long") || cls.contains("Short") || "int".equals(cls)) {
            try { return Integer.parseInt(safe.trim()); } catch (Exception ignored) { return 0; }
        }
        if (cls.contains("Boolean") || "boolean".equals(cls)) {
            return "S".equalsIgnoreCase(safe) || "Y".equalsIgnoreCase(safe) || Boolean.parseBoolean(safe);
        }
        return safe;
    }

    private static GenerateRequest parseGenerateRequest(HttpExchange ex, String contentType, Path tmpDir) throws Exception {
        if (contentType.startsWith("multipart/form-data")) {
            String boundary = extractBoundary(contentType);
            if (boundary == null) throw new IllegalArgumentException("Falta boundary en Content-Type");
            byte[] body = ex.getRequestBody().readAllBytes();
            Map<String, List<Part>> parts = parseMultipart(body, boundary);
            File main = saveRequiredFilePart(parts, "main", tmpDir, "Falta el campo 'main' con el JRXML principal");
            List<File> subs = saveOptionalFileParts(parts.get("sub"), tmpDir);
            Map<String, Object> params = parseParamsPart(parts.get("params"));
            return new GenerateRequest(main, tmpDir.toFile(), subs, params);
        }

        if (contentType.startsWith("application/json")) {
            ObjectNode json = parseJsonObject(ex.getRequestBody().readAllBytes(), "El body JSON debe ser un objeto");
            String path = json.path("jrxmlPath").asText("").trim();
            if (path.isEmpty()) throw new IllegalArgumentException("Campo 'jrxmlPath' requerido en el JSON");
            File main = new File(path);
            if (!main.isFile()) throw new IllegalArgumentException("Fichero no encontrado: " + path);
            List<File> subreports = new ArrayList<>();
            JsonNode subPaths = json.path("subreportPaths");
            if (subPaths.isArray()) {
                for (JsonNode node : subPaths) {
                    File sub = new File(node.asText(""));
                    if (!sub.isFile()) throw new IllegalArgumentException("Subreport no encontrado: " + node.asText());
                    subreports.add(sub);
                }
            }
            Map<String, Object> params = parseObjectToMap(json.path("params"));
            return new GenerateRequest(main, main.getParentFile(), subreports, params);
        }

        byte[] body = ex.getRequestBody().readAllBytes();
        File main = tmpDir.resolve("report.jrxml").toFile();
        Files.write(main.toPath(), body);
        return new GenerateRequest(main, tmpDir.toFile(), null, null);
    }

    private static ObjectNode parseJsonObject(byte[] body, String error) throws IOException {
        JsonNode root = MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
        if (!(root instanceof ObjectNode)) throw new IllegalArgumentException(error);
        return (ObjectNode) root;
    }

    private static Map<String, Object> parseObjectToMap(JsonNode node) {
        if (!(node instanceof ObjectNode)) return new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> out.put(entry.getKey(), jsonScalarToJava(entry.getValue())));
        return out;
    }

    private static Object jsonScalarToJava(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isInt()) return node.intValue();
        if (node.isLong()) return node.longValue();
        if (node.isFloatingPointNumber()) return node.decimalValue();
        if (node.isBoolean()) return node.booleanValue();
        return node.asText();
    }

    private static File saveRequiredFilePart(Map<String, List<Part>> parts, String key, Path tmpDir, String error) throws IOException {
        List<Part> list = parts.get(key);
        if (list == null || list.isEmpty()) throw new IllegalArgumentException(error);
        return savePartAsFile(list.get(0), tmpDir, key + ".jrxml");
    }

    private static List<File> saveOptionalFileParts(List<Part> parts, Path tmpDir) throws IOException {
        List<File> files = new ArrayList<>();
        if (parts == null) return files;
        for (Part part : parts) files.add(savePartAsFile(part, tmpDir, "sub.jrxml"));
        return files;
    }

    private static File savePartAsFile(Part part, Path tmpDir, String fallback) throws IOException {
        String safeName = sanitizeFilename(part.filename != null ? part.filename : fallback);
        File dest = tmpDir.resolve(safeName).toFile();
        Files.write(dest.toPath(), part.content);
        return dest;
    }

    private static Map<String, Object> parseParamsPart(List<Part> paramsParts) throws IOException {
        if (paramsParts == null || paramsParts.isEmpty()) return new LinkedHashMap<>();
        ObjectNode json = parseJsonObject(paramsParts.get(0).content, "La parte 'params' debe ser un JSON objeto");
        return parseObjectToMap(json);
    }

    private static Map<String, List<Part>> parseMultipart(byte[] body, String boundary) {
        byte[] delim = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] firstDelim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        Map<String, List<Part>> result = new LinkedHashMap<>();
        int start = indexOf(body, firstDelim, 0);
        if (start < 0) return result;
        start += firstDelim.length;
        if (start + 1 < body.length && body[start] == '\r' && body[start + 1] == '\n') start += 2;

        while (start < body.length) {
            int end = indexOf(body, delim, start);
            if (end < 0) break;
            Part part = parsePart(Arrays.copyOfRange(body, start, end));
            if (part != null) result.computeIfAbsent(part.name, k -> new ArrayList<>()).add(part);
            start = end + delim.length;
            if (start >= body.length) break;
            if (start + 1 < body.length && body[start] == '-' && body[start + 1] == '-') break;
            if (start + 1 < body.length && body[start] == '\r' && body[start + 1] == '\n') start += 2;
        }
        return result;
    }

    private static Part parsePart(byte[] partBytes) {
        byte[] sep = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        int sepIdx = indexOf(partBytes, sep, 0);
        if (sepIdx < 0) return null;

        String headers = new String(partBytes, 0, sepIdx, StandardCharsets.ISO_8859_1);
        byte[] content = Arrays.copyOfRange(partBytes, sepIdx + 4, partBytes.length);
        String fieldName = null;
        String filename = null;
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-disposition")) {
                Matcher m = Pattern.compile("name=\"([^\"]+)\"").matcher(line);
                if (m.find()) fieldName = m.group(1);
                m = Pattern.compile("filename=\"([^\"]+)\"").matcher(line);
                if (m.find()) filename = m.group(1);
            }
        }
        if (fieldName == null) return null;
        return new Part(fieldName, filename, content);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int fromIndex) {
        outer:
        for (int i = fromIndex; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
        ex.close();
    }

    private static String extractBoundary(String contentType) {
        Matcher m = Pattern.compile("boundary=([^;\\s]+)").matcher(contentType);
        if (!m.find()) return null;
        return m.group(1).replaceAll("^\"|\"$", "");
    }

    private static String jsonError(String message) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", " ");
        return "{\"error\":\"" + escaped + "\"}";
    }

    private static String buildErrorChain(Throwable root) {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        List<Throwable> queue = new ArrayList<>();
        queue.add(root);
        int idx = 0;
        while (idx < queue.size()) {
            Throwable t = queue.get(idx++);
            if (t == null) continue;
            String msg = t.getClass().getSimpleName() + ": " + Optional.ofNullable(t.getMessage()).orElse("(sin mensaje)");
            if (seen.add(msg)) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(msg);
            }
            if (t.getCause() != null) queue.add(t.getCause());
            queue.addAll(Arrays.asList(t.getSuppressed()));
        }
        Throwable sax = findCause(root, SAXParseException.class);
        if (sax instanceof SAXParseException) {
            SAXParseException spe = (SAXParseException) sax;
            sb.append(" | Línea ").append(spe.getLineNumber()).append(", columna ").append(spe.getColumnNumber());
        }
        return sb.toString();
    }

    private static Throwable findCause(Throwable root, Class<?> type) {
        Throwable t = root;
        while (t != null) {
            if (type.isInstance(t)) return t;
            t = t.getCause();
        }
        return null;
    }

    private static String sanitizeFilename(String filename) {
        String safe = filename == null ? "file.jrxml" : filename.replace('\\', '_').replace('/', '_');
        return safe.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void deleteQuietly(Path dir) {
        try {
            Files.walk(dir)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {
        }
    }

    private static final class GenerateRequest {
        final File mainFile;
        final File resourceDir;
        final List<File> subreports;
        final Map<String, Object> params;

        private GenerateRequest(File mainFile, File resourceDir, List<File> subreports, Map<String, Object> params) {
            this.mainFile = mainFile;
            this.resourceDir = resourceDir;
            this.subreports = subreports;
            this.params = params;
        }
    }

    private static final class Part {
        final String name;
        final String filename;
        final byte[] content;

        private Part(String name, String filename, byte[] content) {
            this.name = name;
            this.filename = filename;
            this.content = content;
        }
    }
}
