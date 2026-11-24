package es.asisa.jasper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Envuelve objetos JDBC y bloquea cualquier intento de escritura.
 */
public final class ReadOnlyJdbc {

    private static final Pattern LEADING_COMMENTS = Pattern.compile(
            "^(?:\\s|--.*?(?:\\R|$)|/\\*.*?\\*/)+",
            Pattern.DOTALL | Pattern.MULTILINE);

    private ReadOnlyJdbc() {
    }

    public static Connection wrap(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                delegate.getClass().getClassLoader(),
                new Class[]{Connection.class},
                new ConnectionHandler(delegate));
    }

    static boolean isReadOnlySql(String sql) {
        if (sql == null) {
            return false;
        }
        String normalized = LEADING_COMMENTS.matcher(sql).replaceFirst("").trim();
        if (normalized.isEmpty()) {
            return false;
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        return upper.startsWith("SELECT")
                || upper.startsWith("WITH")
                || upper.startsWith("VALUES")
                || upper.startsWith("(");
    }

    private static SQLException readOnlyViolation(String sql) {
        return new SQLException("Operación bloqueada: solo se permiten consultas de lectura en DB2. SQL=" + sql);
    }

    private static Object invokeTarget(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    private static final class ConnectionHandler implements InvocationHandler {
        private final Connection delegate;

        private ConnectionHandler(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("setReadOnly".equals(name)) {
                return null;
            }
            if ("isReadOnly".equals(name)) {
                return true;
            }
            if ("prepareCall".equals(name)) {
                throw readOnlyViolation("CALLABLE_STATEMENT");
            }
            if ("createStatement".equals(name)) {
                Statement st = (Statement) invokeTarget(method, delegate, args);
                return wrapStatement(st);
            }
            if ("prepareStatement".equals(name) && args != null && args.length > 0 && args[0] instanceof String) {
                String sql = (String) args[0];
                if (!isReadOnlySql(sql)) {
                    throw readOnlyViolation(sql);
                }
                PreparedStatement ps = (PreparedStatement) invokeTarget(method, delegate, args);
                return wrapPreparedStatement(ps, sql);
            }
            return invokeTarget(method, delegate, args);
        }
    }

    private static Statement wrapStatement(Statement delegate) {
        return (Statement) Proxy.newProxyInstance(
                delegate.getClass().getClassLoader(),
                new Class[]{Statement.class},
                new StatementHandler(delegate));
    }

    private static PreparedStatement wrapPreparedStatement(PreparedStatement delegate, String sql) {
        Class<?>[] interfaces = delegate instanceof CallableStatement
                ? new Class[]{PreparedStatement.class, CallableStatement.class}
                : new Class[]{PreparedStatement.class};
        return (PreparedStatement) Proxy.newProxyInstance(
                delegate.getClass().getClassLoader(),
                interfaces,
                new PreparedStatementHandler(delegate, sql));
    }

    private static class StatementHandler implements InvocationHandler {
        protected final Statement delegate;

        private StatementHandler(Statement delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (("execute".equals(name) || "executeQuery".equals(name))
                    && args != null && args.length > 0 && args[0] instanceof String) {
                String sql = (String) args[0];
                if (!isReadOnlySql(sql)) {
                    throw readOnlyViolation(sql);
                }
            }
            if (name.startsWith("executeUpdate")
                    || name.startsWith("executeLargeUpdate")
                    || "addBatch".equals(name)
                    || "executeBatch".equals(name)
                    || "executeLargeBatch".equals(name)) {
                throw readOnlyViolation(args != null && args.length > 0 ? String.valueOf(args[0]) : name);
            }
            return invokeTarget(method, delegate, args);
        }
    }

    private static final class PreparedStatementHandler extends StatementHandler {
        private final String sql;

        private PreparedStatementHandler(PreparedStatement delegate, String sql) {
            super(delegate);
            this.sql = sql;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.startsWith("executeUpdate")
                    || name.startsWith("executeLargeUpdate")
                    || "addBatch".equals(name)
                    || "executeBatch".equals(name)
                    || "executeLargeBatch".equals(name)) {
                throw readOnlyViolation(sql);
            }
            if (("execute".equals(name) || "executeQuery".equals(name)) && !isReadOnlySql(sql)) {
                throw readOnlyViolation(sql);
            }
            return super.invoke(proxy, method, args);
        }
    }
}
