package com.offerchaser.application.persistence;

import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 持久化层单元测试公共工具。
 * <p>
 * 所有测试库都创建在 {@code @TempDir} 下，绝不触碰 {@code db/offerchaser.db}
 * 与浏览器数据库。
 */
final class PersistenceTestSupport {

    static final String ACCOUNT = "local";
    static final String TIMEZONE = "Asia/Shanghai";

    private PersistenceTestSupport() {
    }

    static SQLiteDataSource dataSource(Path databaseFile) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + databaseFile.toAbsolutePath());
        return dataSource;
    }

    /** 执行迁移并断言成功，返回迁移服务实例。 */
    static SchemaMigrationService migrate(SQLiteDataSource dataSource) {
        DatabaseMaintenanceService maintenance = new DatabaseMaintenanceService(dataSource);
        SchemaMigrationService migration = new SchemaMigrationService(dataSource, maintenance);
        migration.migrate();
        assertTrue(migration.isReady(), "数据库迁移应成功");
        assertEquals(SchemaMigrationService.TARGET_VERSION, migration.currentVersion(), "迁移后版本应为目标版本");
        return migration;
    }

    /** 创建一份位于临时目录、已完成迁移的 SQLite 数据源。 */
    static SQLiteDataSource migratedDataSource(Path directory) {
        SQLiteDataSource dataSource = dataSource(directory.resolve("offerchaser-test.db"));
        migrate(dataSource);
        return dataSource;
    }

    static int scalar(SQLiteDataSource dataSource, String sql, Object... parameters) {
        return (int) scalarLong(dataSource, sql, parameters);
    }

    static long scalarLong(SQLiteDataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        } catch (Exception e) {
            throw new IllegalStateException("查询失败：" + sql, e);
        }
    }

    static String scalarString(SQLiteDataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("查询失败：" + sql, e);
        }
    }

    static void execute(SQLiteDataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("执行失败：" + sql, e);
        }
    }

    static void executeScript(SQLiteDataSource dataSource, String... statements) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (Exception e) {
            throw new IllegalStateException("执行 SQL 脚本失败", e);
        }
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws Exception {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }
}
