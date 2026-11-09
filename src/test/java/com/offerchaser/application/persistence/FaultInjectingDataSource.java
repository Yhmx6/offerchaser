package com.offerchaser.application.persistence;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * 故障注入数据源：在指定 SQL 片段的 {@code executeUpdate} 上抛 SQLException，
 * 用于验证事务化结算在「文字 / 预算聚合 / 事件」任一步写失败时会整体回滚。
 * <p>
 * 仅用于测试；其余调用原样委托给真实数据源。
 */
final class FaultInjectingDataSource implements DataSource {

    private final DataSource delegate;
    private volatile String failOnSqlContaining;

    FaultInjectingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    /** 让所有包含该 SQL 片段的 executeUpdate 失败；传 null 关闭故障注入。 */
    void failOn(String sqlFragment) {
        this.failOnSqlContaining = sqlFragment;
    }

    void stopFailing() {
        this.failOnSqlContaining = null;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrapConnection(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrapConnection(delegate.getConnection(username, password));
    }

    private Connection wrapConnection(Connection real) {
        return (Connection) Proxy.newProxyInstance(
                FaultInjectingDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(real, method, args);
                    if (result instanceof PreparedStatement preparedStatement
                            && "prepareStatement".equals(method.getName())
                            && args != null && args.length > 0 && args[0] instanceof String sql) {
                        return wrapPreparedStatement(preparedStatement, sql);
                    }
                    return result;
                });
    }

    private PreparedStatement wrapPreparedStatement(PreparedStatement real, String sql) {
        return (PreparedStatement) Proxy.newProxyInstance(
                FaultInjectingDataSource.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    String failFragment = failOnSqlContaining;
                    if (failFragment != null
                            && "executeUpdate".equals(method.getName())
                            && sql.contains(failFragment)) {
                        throw new SQLException("测试注入的数据库写入故障：" + failFragment);
                    }
                    return invoke(real, method, args);
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }
}
