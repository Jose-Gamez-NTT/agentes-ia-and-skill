package es.asisa.jasper;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Carga el driver JDBC de DB2 desde la carpeta local de drivers del proyecto.
 */
public final class Db2DriverLoader {

    private static volatile boolean loaded;

    private Db2DriverLoader() {
    }

    public static synchronized void ensureLoaded(String driverClass, Path driverDir) throws Exception {
        if (loaded || isDriverAlreadyAvailable(driverClass)) {
            loaded = true;
            return;
        }

        if (driverDir == null || !Files.isDirectory(driverDir)) {
            throw new IllegalStateException("No existe la carpeta de drivers DB2: " + driverDir);
        }

        List<URL> jarUrls;
        try (Stream<Path> paths = Files.list(driverDir)) {
            jarUrls = paths
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(Db2DriverLoader::toUrl)
                    .collect(Collectors.toList());
        }

        if (jarUrls.isEmpty()) {
            throw new IllegalStateException("No se han encontrado jars de DB2 en: " + driverDir);
        }

        URLClassLoader loader = new URLClassLoader(
                jarUrls.toArray(new URL[0]),
                Db2DriverLoader.class.getClassLoader());
        Driver driver = (Driver) Class.forName(driverClass, true, loader)
                .getDeclaredConstructor()
                .newInstance();
        DriverManager.registerDriver(new DriverShim(driver));
        loaded = true;
    }

    private static boolean isDriverAlreadyAvailable(String driverClass) {
        try {
            Class.forName(driverClass);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo resolver la URL del jar: " + path, ex);
        }
    }

    private static final class DriverShim implements Driver {

        private final Driver delegate;

        private DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.sql.Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}
