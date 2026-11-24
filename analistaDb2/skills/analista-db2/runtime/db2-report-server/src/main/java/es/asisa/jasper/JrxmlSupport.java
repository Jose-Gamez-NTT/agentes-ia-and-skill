package es.asisa.jasper;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilidades JRXML necesarias para el servidor standalone DB2.
 */
public final class JrxmlSupport {

    private static final Set<String> JASPER_BUILTINS = new HashSet<>(Arrays.asList(
            "REPORT_CONNECTION", "REPORT_DATA_SOURCE", "REPORT_MAX_COUNT",
            "REPORT_SCRIPTLET", "REPORT_PARAMETERS_MAP", "REPORT_LOCALE",
            "REPORT_RESOURCE_BUNDLE", "REPORT_TIME_ZONE", "REPORT_FORMAT_FACTORY",
            "REPORT_CLASS_LOADER", "REPORT_URL_HANDLER_FACTORY",
            "REPORT_FILE_RESOLVER", "REPORT_TEMPLATES", "SUBREPORT_DIR"
    ));

    private JrxmlSupport() {
    }

    public static List<ParamDef> extractParams(File jrxmlFile) throws Exception {
        Document doc = parseXml(jrxmlFile);
        NodeList params = doc.getElementsByTagName("parameter");
        List<ParamDef> result = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (int i = 0; i < params.getLength(); i++) {
            Element el = (Element) params.item(i);
            String name = el.getAttribute("name");
            String cls = el.getAttribute("class");
            String forPrompting = el.getAttribute("isForPrompting");

            if (JASPER_BUILTINS.contains(name)) continue;
            if ("false".equalsIgnoreCase(forPrompting)) continue;

            String suggested = extractLiteralDefault(el);
            if (suggested == null) {
                suggested = suggestMock(name, cls, today);
            }
            result.add(new ParamDef(name, cls, suggested));
        }
        return result;
    }

    public static List<File> detectSubreports(File jrxmlFile) throws Exception {
        String content = Files.readString(jrxmlFile.toPath(), StandardCharsets.UTF_8);
        File baseDir = jrxmlFile.getParentFile();
        List<File> found = new ArrayList<>();

        Matcher matcher = Pattern.compile("<subreportExpression[^>]*>.*?\"([^\"]+\\.jasper)\".*?</subreportExpression>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(content);
        while (matcher.find()) {
            String jasperName = matcher.group(1).trim();
            if (jasperName.contains("$") || jasperName.contains("+")) continue;
            String jrxmlName = jasperName.replaceAll("(?i)\\.jasper$", ".jrxml");
            File sub = new File(baseDir, jrxmlName);
            if (sub.isFile()) found.add(sub);
        }
        return found;
    }

    public static List<String> createMissingImagePlaceholders(File jrxmlFile, File baseDir) throws IOException {
        String content = Files.readString(jrxmlFile.toPath(), StandardCharsets.UTF_8);
        Set<String> images = new LinkedHashSet<>();
        Matcher m = Pattern.compile("<imageExpression[^>]*>\\s*(?:<!\\[CDATA\\[)?\\s*\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE).matcher(content);
        while (m.find()) {
            String path = m.group(1).trim();
            if (!path.contains("$") && !path.contains("+")) {
                images.add(path);
            }
        }

        List<String> created = new ArrayList<>();
        for (String img : images) {
            File f = new File(baseDir, img);
            if (!f.exists()) {
                if (f.getParentFile() != null) Files.createDirectories(f.getParentFile().toPath());
                Files.write(f.toPath(), createTransparentPng());
                f.deleteOnExit();
                created.add(img);
            }
        }
        return created;
    }

    private static Document parseXml(File f) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(null);
        builder.setEntityResolver((pub, sys) -> new org.xml.sax.InputSource(new StringReader("")));
        return builder.parse(f);
    }

    private static String extractLiteralDefault(Element paramEl) {
        NodeList children = paramEl.getElementsByTagName("defaultValueExpression");
        if (children.getLength() == 0) return null;
        String text = children.item(0).getTextContent().trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            return text.substring(1, text.length() - 1);
        }
        if (text.startsWith("'") && text.endsWith("'") && text.length() >= 2) {
            return text.substring(1, text.length() - 1);
        }
        try {
            Integer.parseInt(text);
            return text;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static String suggestMock(String name, String cls, LocalDate today) {
        String up = name.toUpperCase();
        boolean isInt = cls != null && (cls.contains("Integer") || cls.contains("Long")
                || cls.contains("Short") || "int".equals(cls));

        if (up.contains("LOGIN") || up.contains("USUARIO") || up.endsWith("USER")) return "TEST_USER";
        if (up.contains("MES_ANNO") || up.contains("ANNO_MES") || up.contains("PERIODO") || up.contains("MES_ANN")) {
            if (up.contains("ANTERIOR") || up.contains("PREV")) {
                return String.format("%04d%02d", today.getYear() - 2, today.getMonthValue());
            }
            return String.format("%04d%02d", today.getYear() - 1, today.getMonthValue());
        }
        if (up.equals("ANIO") || up.equals("ANNO") || up.equals("YEAR") || up.contains("ANIO") || up.contains("ANNO")) {
            if (up.contains("ANTERIOR") || up.contains("PREV")) return String.valueOf(today.getYear() - 2);
            return String.valueOf(today.getYear() - 1);
        }
        if (up.equals("MES") || up.contains("_MES") || up.endsWith("_MES")) {
            if (up.contains("DESDE") || up.contains("FROM")) return "01";
            return String.format("%02d", today.getMonthValue());
        }
        if ((up.contains("DEL") || up.contains("DELEGAC")) && up.length() <= 20) return "01";
        if (up.contains("ESPECIALIDAD") || up.contains("ESPECIALID")) {
            if (up.contains("IND")) return "N";
            return "000100";
        }
        if (up.startsWith("IND") || up.contains("_IND_")) return "N";
        if (up.contains("WHERE") || up.contains("FILTRO")) return "";
        if (up.contains("COD_TIPO")) return "0001";
        if (up.contains("COD_")) return "01";
        if (up.contains("DESC") || up.contains("NOMBRE") || up.contains("DEST") || up.contains("ENTRADA")) return "PRUEBA";
        if (isInt) return "1";
        return "";
    }

    private static byte[] createTransparentPng() throws IOException {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0x00000000);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    public static final class ParamDef {
        public final String name;
        public final String javaClass;
        public final String suggestedValue;

        public ParamDef(String name, String javaClass, String suggestedValue) {
            this.name = name;
            this.javaClass = javaClass != null ? javaClass : "java.lang.String";
            this.suggestedValue = suggestedValue != null ? suggestedValue : "";
        }
    }
}
