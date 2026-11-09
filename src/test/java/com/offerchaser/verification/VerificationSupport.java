package com.offerchaser.verification;

import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DatabaseMaintenanceService;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.SchemaMigrationService;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立验收（T6）公共工具。
 * <p>
 * 所有临时库都创建在 {@code @TempDir} 下；真实库 {@code db/offerchaser.db} 只允许复制后操作副本，
 * 本类不提供任何直接写真实库的方法。
 */
final class VerificationSupport {

    static final String ACCOUNT = "local";
    static final String TIMEZONE = "Asia/Shanghai";

    private VerificationSupport() {
    }

    static SQLiteDataSource dataSource(Path databaseFile) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + databaseFile.toAbsolutePath());
        return dataSource;
    }

    /** 对给定数据源执行迁移并断言成功，返回迁移服务。 */
    static SchemaMigrationService migrate(SQLiteDataSource dataSource) {
        DatabaseMaintenanceService maintenance = new DatabaseMaintenanceService(dataSource);
        SchemaMigrationService migration = new SchemaMigrationService(dataSource, maintenance);
        migration.migrate();
        return migration;
    }

    /** 在临时目录下创建一份已完成迁移的数据源。 */
    static SQLiteDataSource migratedDataSource(Path directory) {
        SQLiteDataSource dataSource = dataSource(directory.resolve("verification.db"));
        SchemaMigrationService migration = migrate(dataSource);
        if (!migration.isReady()) {
            throw new IllegalStateException("临时数据库迁移失败，无法继续验收测试");
        }
        return dataSource;
    }

    /** 预算服务：直接传 @Value 参数值（构造器注入不依赖 Spring 容器）。 */
    static BudgetService budgetService(SQLiteDataSource dataSource, boolean enabled, int dailyLimit) {
        return new BudgetService(dataSource, enabled, dailyLimit, 30, TIMEZONE, ACCOUNT);
    }

    static DeliveryLedgerService ledgerService(SQLiteDataSource dataSource) {
        return new DeliveryLedgerService(dataSource, ACCOUNT, TIMEZONE);
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

    static int scalar(SQLiteDataSource dataSource, String sql, Object... parameters) {
        return (int) scalarLong(dataSource, sql, parameters);
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

    /** 所有用户表名（排除 sqlite_ 内部表）。 */
    static List<String> tableNames(SQLiteDataSource dataSource) {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取表名失败", e);
        }
        return names;
    }

    /** 每张用户表的行数，用于迁移前后比对（不得丢失任何旧表数据）。 */
    static Map<String, Integer> tableCounts(SQLiteDataSource dataSource) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : tableNames(dataSource)) {
            counts.put(table, scalar(dataSource, "SELECT COUNT(*) FROM \"" + table.replace("\"", "\"\"") + "\""));
        }
        return counts;
    }

    static boolean indexExists(SQLiteDataSource dataSource, String indexName) {
        return scalar(dataSource, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?", indexName) > 0;
    }

    /** 逐字节计算文件 SHA-256（小写十六进制）。 */
    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("计算 SHA-256 失败：" + file, e);
        }
    }

    /** 统计目录下匹配前缀/后缀的文件数；目录不存在返回 0。 */
    static int countFiles(Path directory, String prefix, String suffix) {
        if (directory == null || !Files.isDirectory(directory)) {
            return 0;
        }
        try (var stream = Files.list(directory)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .count();
        } catch (IOException e) {
            throw new IllegalStateException("读取目录失败：" + directory, e);
        }
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws Exception {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }
}
