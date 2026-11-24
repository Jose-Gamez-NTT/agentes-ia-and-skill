package es.asisa.jasper;

import net.sf.jasperreports.engine.JRField;
import net.sf.jasperreports.engine.JRPrintElement;
import net.sf.jasperreports.engine.JRPrintFrame;
import net.sf.jasperreports.engine.JRPrintPage;
import net.sf.jasperreports.engine.JRPrintText;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.SimpleJasperReportsContext;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;
import net.sf.jasperreports.engine.export.JRPdfExporter;
import net.sf.jasperreports.engine.util.JRLoader;
import net.sf.jasperreports.engine.util.JRSaver;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.repo.FileRepositoryPersistenceServiceFactory;
import net.sf.jasperreports.repo.FileRepositoryService;
import net.sf.jasperreports.repo.PersistenceServiceFactory;
import net.sf.jasperreports.repo.RepositoryService;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runner Jasper limitado a DB2 en solo lectura.
 */
public class JasperDb2Runner {

    private final Db2Config db2Config;
    private Connection connection;
    private Connection physicalConnection;

    public JasperDb2Runner(Db2Config db2Config) {
        this.db2Config = db2Config;
    }

    public void start() throws Exception {
        db2Config.validate();
        Db2DriverLoader.ensureLoaded(db2Config.effectiveDriverClass(), db2Config.resolveDriverDir());
        Connection raw = DriverManager.getConnection(db2Config.effectiveJdbcUrl(), db2Config.toJdbcProperties());
        raw.setAutoCommit(true);
        raw.setReadOnly(true);
        this.physicalConnection = raw;
        this.connection = ReadOnlyJdbc.wrap(raw);
    }

    public void stop() throws SQLException {
        if (physicalConnection != null && !physicalConnection.isClosed()) {
            physicalConnection.close();
            return;
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public String describeDataSource() {
        return "DB2 (solo lectura) → " + db2Config.safeSummary();
    }

    public Path compileReportFromFile(File jrxmlFile, Path outputDir) throws Exception {
        return compileReportFromFile(jrxmlFile, outputDir, jrxmlFile.getName().replace(".jrxml", ".jasper"));
    }

    public Path compileReportFromFile(File jrxmlFile, Path outputDir, String outputJasperName) throws Exception {
        Files.createDirectories(outputDir);
        JasperReport jasperReport = JasperCompileManager.compileReport(jrxmlFile.getAbsolutePath());
        Path outputPath = outputDir.resolve(outputJasperName);
        JRSaver.saveObject(jasperReport, outputPath.toFile());
        return outputPath;
    }

    public void generatePdf(Path jasperFile, Map<String, Object> params, Path outputPdf, File resourceBaseDir) throws Exception {
        JasperReport report = (JasperReport) JRLoader.loadObject(jasperFile.toFile());
        JasperPrint print;

        if (resourceBaseDir != null) {
            SimpleJasperReportsContext ctx = new SimpleJasperReportsContext();
            FileRepositoryService fileRepo = new FileRepositoryService(ctx, resourceBaseDir.getAbsolutePath(), false);
            ctx.setExtensions(RepositoryService.class, Collections.singletonList(fileRepo));
            ctx.setExtensions(PersistenceServiceFactory.class,
                    Collections.singletonList(FileRepositoryPersistenceServiceFactory.getInstance()));

            print = JasperFillManager.getInstance(ctx).fill(report, params, connection);
            if (isBlankPrint(print)) {
                JRMapCollectionDataSource fallbackDs = createFallbackDataSource(report);
                print = JasperFillManager.getInstance(ctx).fill(report, params, fallbackDs);
            }
        } else {
            print = JasperFillManager.fillReport(report, params, connection);
            if (isBlankPrint(print)) {
                JRMapCollectionDataSource fallbackDs = createFallbackDataSource(report);
                print = JasperFillManager.fillReport(report, params, fallbackDs);
            }
        }

        logFontsUsed(print, jasperFile);
        Files.createDirectories(outputPdf.getParent());
        JRPdfExporter exporter = new JRPdfExporter();
        exporter.setExporterInput(new SimpleExporterInput(print));
        exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(outputPdf.toFile()));
        exporter.exportReport();
    }

    private boolean isBlankPrint(JasperPrint print) {
        if (print == null || print.getPages() == null || print.getPages().isEmpty()) {
            return true;
        }
        for (JRPrintPage page : print.getPages()) {
            if (page != null && page.getElements() != null && !page.getElements().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private JRMapCollectionDataSource createFallbackDataSource(JasperReport report) {
        Map<String, Object> row = new HashMap<>();
        for (JRField field : report.getFields()) {
            row.put(field.getName(), defaultValueForField(field));
        }
        return new JRMapCollectionDataSource(Collections.singletonList(row));
    }

    private Object defaultValueForField(JRField field) {
        String className = field.getValueClassName();
        if (className == null) {
            return "1";
        }
        switch (className) {
            case "java.lang.String":
                return "1";
            case "java.lang.Integer":
            case "java.lang.Short":
            case "java.lang.Long":
            case "java.lang.Byte":
                return 1;
            case "java.lang.Double":
            case "java.lang.Float":
                return 1.0;
            case "java.math.BigDecimal":
                return new java.math.BigDecimal("1");
            case "java.lang.Boolean":
                return Boolean.TRUE;
            case "java.sql.Date":
                return java.sql.Date.valueOf("2000-01-01");
            case "java.sql.Time":
                return java.sql.Time.valueOf("00:00:00");
            case "java.sql.Timestamp":
                return java.sql.Timestamp.valueOf("2000-01-01 00:00:00");
            case "java.util.Date":
                return new java.util.Date(946684800000L);
            default:
                return "1";
        }
    }

    private void logFontsUsed(JasperPrint print, Path jasperFile) {
        if (print == null || print.getPages() == null || print.getPages().isEmpty()) {
            return;
        }
        Set<String> unique = new LinkedHashSet<>();
        for (JRPrintPage page : print.getPages()) {
            if (page.getElements() != null) {
                collectFonts(page.getElements(), unique);
            }
        }
        if (unique.isEmpty()) {
            return;
        }
        System.out.println("[Font] " + jasperFile.getFileName() + " — fuentes detectadas en el PDF:");
        for (String font : unique) {
            System.out.println("[Font]   " + font);
        }
    }

    private void collectFonts(List<JRPrintElement> elements, Set<String> out) {
        for (JRPrintElement elem : elements) {
            if (elem instanceof JRPrintText) {
                JRPrintText text = (JRPrintText) elem;
                String desc = text.getFontName() + " " + (int) text.getFontsize() + "pt"
                        + (text.isBold() ? " [negrita]" : "")
                        + (text.isItalic() ? " [cursiva]" : "");
                out.add(desc);
            } else if (elem instanceof JRPrintFrame) {
                collectFonts(((JRPrintFrame) elem).getElements(), out);
            }
        }
    }
}
