package es.asisa.jasper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Properties;

/**
 * Configuración de conexión DB2 para ejecución estrictamente en solo lectura.
 */
public final class Db2Config {

    public static final String DEFAULT_DRIVER_CLASS = "com.ibm.db2.jcc.DB2Driver";
    public static final String DEFAULT_DRIVER_DIR = "Drivers_win";
    public static final String DEFAULT_PORT = "50000";
    public static final String DEFAULT_CONFIG_FILE = "db2.properties";

    public final String driverClass;
    public final String jdbcUrl;
    public final String host;
    public final String port;
    public final String database;
    public final String user;
    public final String password;
    public final String schema;
    public final String driverDir;

    public Db2Config(String driverClass,
                     String jdbcUrl,
                     String host,
                     String port,
                     String database,
                     String user,
                     String password,
                     String schema,
                     String driverDir) {
        this.driverClass = trimToEmpty(driverClass);
        this.jdbcUrl = trimToEmpty(jdbcUrl);
        this.host = trimToEmpty(host);
        this.port = trimToEmpty(port);
        this.database = trimToEmpty(database);
        this.user = trimToEmpty(user);
        this.password = password == null ? "" : password;
        this.schema = trimToEmpty(schema);
        this.driverDir = trimToEmpty(driverDir);
    }

    public static Db2Config fromSystemProperties() {
        Properties fileProps = loadPropertiesFile();
        return new Db2Config(
                System.getProperty("db2.driverClass", fileProps.getProperty("db2.driverClass", DEFAULT_DRIVER_CLASS)),
                System.getProperty("db2.jdbcUrl", fileProps.getProperty("db2.jdbcUrl", "")),
                System.getProperty("db2.host", fileProps.getProperty("db2.host", "")),
                System.getProperty("db2.port", fileProps.getProperty("db2.port", DEFAULT_PORT)),
                System.getProperty("db2.database", fileProps.getProperty("db2.database", "")),
                System.getProperty("db2.user", fileProps.getProperty("db2.user", "")),
                System.getProperty("db2.password", fileProps.getProperty("db2.password", "")),
                System.getProperty("db2.schema", fileProps.getProperty("db2.schema", "")),
                System.getProperty("db2.driverDir", fileProps.getProperty("db2.driverDir", DEFAULT_DRIVER_DIR))
        );
    }

    public void validate() {
        if (effectiveJdbcUrl().isEmpty()) {
            throw new IllegalArgumentException(
                    "Configuración DB2 incompleta: indica JDBC URL o bien host/puerto/base de datos.");
        }
        if (user.isEmpty()) {
            throw new IllegalArgumentException("Configuración DB2 incompleta: falta el usuario.");
        }
    }

    public String effectiveJdbcUrl() {
        if (!jdbcUrl.isEmpty()) {
            return jdbcUrl;
        }
        if (host.isEmpty() || database.isEmpty()) {
            return "";
        }
        String effectivePort = port.isEmpty() ? DEFAULT_PORT : port;
        return "jdbc:db2://" + host + ":" + effectivePort + "/" + database;
    }

    public Properties toJdbcProperties() {
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        if (!schema.isEmpty()) {
            props.setProperty("currentSchema", schema);
        }
        return props;
    }

    public Path resolveDriverDir() {
        return Paths.get(driverDir.isEmpty() ? DEFAULT_DRIVER_DIR : driverDir);
    }

    public String effectiveDriverClass() {
        return driverClass.isEmpty() ? DEFAULT_DRIVER_CLASS : driverClass;
    }

    public String safeSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(effectiveJdbcUrl());
        if (!schema.isEmpty()) {
            sb.append(" [schema=").append(schema).append(']');
        }
        sb.append(" [user=").append(user).append(']');
        return sb.toString();
    }

    private static String trimToEmpty(String value) {
        return Objects.toString(value, "").trim();
    }

    private static Properties loadPropertiesFile() {
        Properties props = new Properties();
        Path path = Paths.get(DEFAULT_CONFIG_FILE);
        if (!Files.isRegularFile(path)) {
            return props;
        }
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer la configuración DB2 de " + path.toAbsolutePath(), ex);
        }
        return props;
    }
}
