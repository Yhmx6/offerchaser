package com.offerchaser.application.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.sqlite.SQLiteConfig;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 数据库维护服务：备份（VACUUM INTO）、备份保留策略、完整性校验与数据库状态查询（B5）。
 * <p>
 * 备份目录分离（互不挤占）：
 * <ul>
 *   <li>{@code db/backups/pre-upgrade/}：迁移/升级前备份，保留最近 5 份；</li>
 *   <li>{@code db/backups/pre-run/}：任务开始前/手动备份，保留最近 10 份；</li>
 *   <li>{@code db/backups/*.db}：旧版扁平备份，仅兼容读取，不再新增、不由本服务删除。</li>
 * </ul>
 * 文件名统一 {@code offerchaser-<yyyyMMdd-HHmmss>-<reason>.db}。
 */
@Service
@Slf4j
public class DatabaseMaintenanceService {

    /** 旧扁平备份保留份数（兼容旧测试/旧接口语义） */
    private static final int MAX_BACKUP_FILES = 10;
    private static final int PRE_UPGRADE_RETENTION = 5;
    private static final int PRE_RUN_RETENTION = 10;

    private static final String BACKUP_DIR_NAME = "backups";
    private static final String PRE_UPGRADE_DIR = "pre-upgrade";
    private static final String PRE_RUN_DIR = "pre-run";
    private static final String KIND_PRE_UPGRADE = "pre-upgrade";
    private static final String KIND_PRE_RUN = "pre-run";
    private static final String KIND_LEGACY = "legacy";
    private static final String KIND_ALL = "all";
    private static final String BACKUP_PREFIX = "offerchaser-";
    private static final String BACKUP_SUFFIX = ".db";
    private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DISPLAY_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_LIST_LIMIT = 20;
    private static final int MAX_LIST_LIMIT = 200;

    private static final String ROLLBACK_WARNING_TEMPLATE =
            "最新升级前备份时间（%s）早于最近一次发送完成时间（%s），旧库可能不含最近已发送记录；"
                    + "恢复后不要直接恢复自动投递，先核对聊天记录与投递台账";

    private final DataSource dataSource;

    public DatabaseMaintenanceService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ==================== 备份 ====================

    /**
     * 使用 SQLite 的 {@code VACUUM INTO} 生成一份一致性备份快照。
     * <p>
     * reason 为 {@code pre-migrate}/{@code pre-upgrade} 时写入 {@code pre-upgrade/}（保留 5 份），
     * 其他原因写入 {@code pre-run/}（保留 10 份）。
     *
     * @return 备份文件路径；数据库不是文件型 SQLite（例如内存库）时返回 null
     */
    public Path backup(String reason) {
        Path databaseFile = locateDatabaseFile();
        if (databaseFile == null) {
            log.warn("当前数据库无法解析为文件路径（可能是内存库），跳过备份；reason={}", reason);
            return null;
        }
        boolean upgrade = isUpgradeReason(reason);
        String kind = upgrade ? KIND_PRE_UPGRADE : KIND_PRE_RUN;
        int retention = upgrade ? PRE_UPGRADE_RETENTION : PRE_RUN_RETENTION;
        Path backupDirectory = resolveKindDirectory(databaseFile, kind);
        String safeReason = sanitizeReason(reason);
        String timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP_FORMATTER);
        try {
            Files.createDirectories(backupDirectory);
            Path target = uniqueTarget(backupDirectory, BACKUP_PREFIX + timestamp + "-" + safeReason);
            String sql = "VACUUM INTO '" + escapeSqlLiteral(target.toAbsolutePath().toString()) + "'";
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            pruneOldBackups(backupDirectory, retention);
            log.info("数据库备份完成：{}（原因：{}，类型：{}，保留 {} 份）",
                    target, safeReason, kind, retention);
            return target.toAbsolutePath();
        } catch (Exception e) {
            throw new IllegalStateException("数据库备份失败：" + e.getMessage(), e);
        }
    }

    // ==================== 备份状态（B5） ====================

    /**
     * 备份门禁状态：
     * {@code preUpgrade:{count,latest,latestVerified,dir}}、{@code preRun:{count,latest,dir}}、
     * {@code legacyFlat:{count,latest}}、{@code lastBackup}、{@code latestSendFactAt}、{@code rollbackWarning}。
     */
    public Map<String, Object> backupStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        Path databaseFile = locateDatabaseFile();
        if (databaseFile == null) {
            result.put("preUpgrade", emptyKindStatus(null));
            result.put("preRun", emptyKindStatus(null));
            result.put("legacyFlat", emptyKindStatus(null));
            result.put("lastBackup", null);
            result.put("latestSendFactAt", null);
            result.put("rollbackWarning", null);
            return result;
        }
        Path preUpgradeDir = resolveKindDirectory(databaseFile, KIND_PRE_UPGRADE);
        Path preRunDir = resolveKindDirectory(databaseFile, KIND_PRE_RUN);
        List<Path> preUpgrade = listBackupsInDirectory(preUpgradeDir);
        List<Path> preRun = listBackupsInDirectory(preRunDir);
        List<Path> legacyFlat = listBackupsInDirectory(resolveBackupRoot(databaseFile));

        Map<String, Object> preUpgradeStatus = new LinkedHashMap<>();
        preUpgradeStatus.put("count", preUpgrade.size());
        Path latestUpgrade = preUpgrade.isEmpty() ? null : preUpgrade.get(0);
        preUpgradeStatus.put("latest", latestUpgrade == null ? null : latestUpgrade.toString());
        preUpgradeStatus.put("latestVerified", latestUpgrade == null ? null
                : verifyBackup(latestUpgrade).get("verified"));
        preUpgradeStatus.put("dir", preUpgradeDir.toString());
        result.put("preUpgrade", preUpgradeStatus);

        Map<String, Object> preRunStatus = new LinkedHashMap<>();
        preRunStatus.put("count", preRun.size());
        Path latestRun = preRun.isEmpty() ? null : preRun.get(0);
        preRunStatus.put("latest", latestRun == null ? null : latestRun.toString());
        preRunStatus.put("dir", preRunDir.toString());
        result.put("preRun", preRunStatus);

        Map<String, Object> legacyStatus = new LinkedHashMap<>();
        legacyStatus.put("count", legacyFlat.size());
        legacyStatus.put("latest", legacyFlat.isEmpty() ? null : legacyFlat.get(0).toString());
        result.put("legacyFlat", legacyStatus);

        // 最近备份 = 全部来源里 mtime 最大的
        List<Path> all = new ArrayList<>();
        all.addAll(preUpgrade);
        all.addAll(preRun);
        all.addAll(legacyFlat);
        all.sort(Comparator.comparingLong(this::lastModifiedMillis).reversed());
        result.put("lastBackup", all.isEmpty() ? null : all.get(0).toString());

        String latestSendFactAt = readLatestSendFactAt();
        result.put("latestSendFactAt", latestSendFactAt);
        // 回滚提示：最新升级前备份（含旧扁平 pre-migrate 备份）早于最近发送事实时间
        Path latestUpgradeIncludingLegacy = latestUpgrade;
        for (Path legacy : legacyFlat) {
            if (latestUpgradeIncludingLegacy == null
                    || lastModifiedMillis(legacy) > lastModifiedMillis(latestUpgradeIncludingLegacy)) {
                latestUpgradeIncludingLegacy = legacy;
            }
        }
        result.put("rollbackWarning", buildRollbackWarning(latestUpgradeIncludingLegacy, latestSendFactAt));
        return result;
    }

    // ==================== 完整性校验 ====================

    /**
     * 只读打开备份并校验可恢复性（{@code PRAGMA integrity_check} 必须为 ok）。
     * <p>
     * 损坏/不可读/文件不存在时返回 {@code verified=false + reason}，不抛异常。
     *
     * @return path/exists/sizeBytes/verified/integrity/tableCount/schemaVersion/verifiedAt[/reason]
     */
    public Map<String, Object> verifyBackup(Path backupPath) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", backupPath == null ? null : backupPath.toString());
        result.put("exists", backupPath != null && Files.isRegularFile(backupPath));
        result.put("sizeBytes", backupSize(backupPath));
        result.put("verified", false);
        result.put("integrity", null);
        result.put("tableCount", 0);
        result.put("schemaVersion", 0);
        result.put("verifiedAt", LocalDateTime.now().format(DISPLAY_TIMESTAMP_FORMATTER));
        if (backupPath == null || !Files.isRegularFile(backupPath)) {
            result.put("reason", "备份文件不存在");
            return result;
        }
        try {
            SQLiteConfig config = new SQLiteConfig();
            config.setReadOnly(true);
            String url = "jdbc:sqlite:" + backupPath.toAbsolutePath();
            try (Connection connection = DriverManager.getConnection(url, config.toProperties())) {
                String integrity = readIntegrityCheck(connection);
                result.put("integrity", integrity);
                if (!"ok".equalsIgnoreCase(integrity)) {
                    result.put("reason", "完整性检查未通过：" + integrity);
                    return result;
                }
                result.put("tableCount", countTables(connection));
                result.put("schemaVersion", readUserVersion(connection));
                result.put("verified", true);
                return result;
            }
        } catch (Exception e) {
            log.warn("备份完整性校验失败 | path={}：{}", backupPath, e.getMessage());
            result.put("reason", "无法打开或校验备份：" + e.getMessage());
            return result;
        }
    }

    // ==================== 备份列表 ====================

    /**
     * 备份列表（按 mtime DESC）。
     *
     * @param kind pre-run / pre-upgrade / all（pre-upgrade 含新目录；all 额外包含旧扁平文件）
     * @return [{path,kind,sizeBytes,mtime,mtimeMs}]
     */
    public List<Map<String, Object>> listBackups(String kind, int limit) {
        String normalizedKind = normalizeKind(kind);
        int safeLimit = limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
        Path databaseFile = locateDatabaseFile();
        if (databaseFile == null) {
            return List.of();
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        if (KIND_ALL.equals(normalizedKind) || KIND_PRE_UPGRADE.equals(normalizedKind)) {
            addEntries(entries, listBackupsInDirectory(
                    resolveKindDirectory(databaseFile, KIND_PRE_UPGRADE)), KIND_PRE_UPGRADE);
        }
        if (KIND_ALL.equals(normalizedKind) || KIND_PRE_RUN.equals(normalizedKind)) {
            addEntries(entries, listBackupsInDirectory(
                    resolveKindDirectory(databaseFile, KIND_PRE_RUN)), KIND_PRE_RUN);
        }
        if (KIND_ALL.equals(normalizedKind)) {
            addEntries(entries, listBackupsInDirectory(resolveBackupRoot(databaseFile)), KIND_LEGACY);
        }
        entries.sort(Comparator.comparingLong(
                (Map<String, Object> entry) -> ((Number) entry.get("mtimeMs")).longValue()).reversed());
        return entries.size() <= safeLimit ? entries : new ArrayList<>(entries.subList(0, safeLimit));
    }

    // ==================== 数据库状态（兼容旧接口） ====================

    /**
     * 数据库状态：数据库路径、大小、迁移版本、备份目录/总数与最近备份等（旧接口保持兼容）。
     */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        Path databaseFile = locateDatabaseFile();
        status.put("databasePath", databaseFile == null ? null : databaseFile.toString());
        status.put("databaseExists", databaseFile != null && Files.isRegularFile(databaseFile));
        status.put("databaseSizeBytes", databaseSize(databaseFile));
        if (databaseFile == null) {
            status.put("backupDir", null);
            status.put("backupCount", 0);
            status.put("lastBackup", null);
        } else {
            Path backupRoot = resolveBackupRoot(databaseFile);
            List<Path> preUpgrade = listBackupsInDirectory(
                    resolveKindDirectory(databaseFile, KIND_PRE_UPGRADE));
            List<Path> preRun = listBackupsInDirectory(
                    resolveKindDirectory(databaseFile, KIND_PRE_RUN));
            List<Path> legacy = listBackupsInDirectory(backupRoot);
            List<Path> all = new ArrayList<>();
            all.addAll(preUpgrade);
            all.addAll(preRun);
            all.addAll(legacy);
            all.sort(Comparator.comparingLong(this::lastModifiedMillis).reversed());
            status.put("backupDir", backupRoot.toString());
            status.put("backupCount", all.size());
            status.put("lastBackup", all.isEmpty() ? null : all.get(0).toString());
            status.put("preUpgradeBackupCount", preUpgrade.size());
            status.put("preRunBackupCount", preRun.size());
            status.put("legacyFlatBackupCount", legacy.size());
        }
        status.put("backupRetention", MAX_BACKUP_FILES);
        status.put("preUpgradeRetention", PRE_UPGRADE_RETENTION);
        status.put("preRunRetention", PRE_RUN_RETENTION);
        status.put("schemaVersion", readSchemaVersion());
        status.put("tableCount", readTableCount());
        return status;
    }

    // ==================== 路径与保留 ====================

    /** 解析当前连接指向的 SQLite 数据库文件；内存库或无法解析时返回 null。 */
    Path locateDatabaseFile() {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            if (url == null) {
                return null;
            }
            String path = url;
            int prefixIndex = path.indexOf("jdbc:sqlite:");
            if (prefixIndex >= 0) {
                path = path.substring(prefixIndex + "jdbc:sqlite:".length());
            }
            int queryIndex = path.indexOf('?');
            if (queryIndex >= 0) {
                path = path.substring(0, queryIndex);
            }
            if (path.startsWith("file:")) {
                path = path.substring("file:".length());
            }
            if (path.isBlank() || path.equals(":memory:") || path.contains("mode=memory")) {
                return null;
            }
            Path resolved = Paths.get(path);
            return resolved.isAbsolute() ? resolved.normalize() : resolved.toAbsolutePath().normalize();
        } catch (Exception e) {
            log.warn("解析 SQLite 数据库文件路径失败：{}", e.getMessage());
            return null;
        }
    }

    private Path resolveBackupRoot(Path databaseFile) {
        Path parent = databaseFile.getParent();
        return parent == null
                ? Paths.get(BACKUP_DIR_NAME).toAbsolutePath().normalize()
                : parent.resolve(BACKUP_DIR_NAME).toAbsolutePath().normalize();
    }

    private Path resolveKindDirectory(Path databaseFile, String kind) {
        return resolveBackupRoot(databaseFile).resolve(kind);
    }

    private boolean isUpgradeReason(String reason) {
        String value = PersistenceSupport.trimToNull(reason);
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.equals("pre-migrate") || lower.equals("pre-upgrade")
                || lower.startsWith("pre-migrate") || lower.startsWith("pre-upgrade");
    }

    private Path uniqueTarget(Path backupDirectory, String baseName) {
        Path target = backupDirectory.resolve(baseName + BACKUP_SUFFIX);
        int index = 2;
        while (Files.exists(target)) {
            target = backupDirectory.resolve(baseName + "-" + index + BACKUP_SUFFIX);
            index++;
        }
        return target;
    }

    private void pruneOldBackups(Path backupDirectory, int retention) {
        List<Path> backups = listBackupsInDirectory(backupDirectory);
        if (backups.size() <= retention) {
            return;
        }
        int removed = 0;
        for (Path stale : backups.subList(retention, backups.size())) {
            try {
                if (Files.deleteIfExists(stale)) {
                    removed++;
                    log.debug("已删除过期数据库备份：{}", stale);
                }
            } catch (Exception e) {
                log.warn("删除过期数据库备份失败：{}：{}", stale, e.getMessage());
            }
        }
        if (removed > 0) {
            log.info("备份清理完成：{} 保留最近 {} 份，删除 {} 份", backupDirectory, retention, removed);
        }
    }

    /** 列出目录内的备份文件（不含子目录），最新的在前。 */
    private List<Path> listBackupsInDirectory(Path backupDirectory) {
        if (backupDirectory == null || !Files.isDirectory(backupDirectory)) {
            return new ArrayList<>();
        }
        try (Stream<Path> stream = Files.list(backupDirectory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(this::isBackupFile)
                    .sorted(Comparator.comparingLong(this::lastModifiedMillis).reversed()
                            .thenComparing(path -> path.getFileName().toString(), Comparator.reverseOrder()))
                    .toList();
        } catch (Exception e) {
            log.warn("读取备份目录失败：{}：{}", backupDirectory, e.getMessage());
            return new ArrayList<>();
        }
    }

    private boolean isBackupFile(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(BACKUP_PREFIX) && name.endsWith(BACKUP_SUFFIX);
    }

    private void addEntries(List<Map<String, Object>> entries, List<Path> paths, String kind) {
        for (Path path : paths) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", path.toString());
            entry.put("kind", kind);
            entry.put("sizeBytes", backupSize(path));
            long mtime = lastModifiedMillis(path);
            entry.put("mtime", formatMtime(mtime));
            entry.put("mtimeMs", mtime);
            entries.add(entry);
        }
    }

    private String normalizeKind(String kind) {
        String value = PersistenceSupport.trimToNull(kind);
        if (value == null) {
            return KIND_ALL;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (KIND_ALL.equals(lower) || KIND_PRE_RUN.equals(lower) || KIND_PRE_UPGRADE.equals(lower)) {
            return lower;
        }
        throw new IllegalArgumentException("INVALID_BACKUP_KIND：" + kind);
    }

    // ==================== 发送事实 / 回滚提示 ====================

    /** delivery_attempt 最近一次发送完成时间（MAX(finished_at)）；无表/无数据返回 null。 */
    private String readLatestSendFactAt() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT MAX(finished_at) FROM delivery_attempt")) {
            return resultSet.next() ? PersistenceSupport.trimToNull(resultSet.getString(1)) : null;
        } catch (Exception e) {
            log.debug("读取最近发送事实时间失败（可能旧库无 delivery_attempt 表）：{}", e.getMessage());
            return null;
        }
    }

    private String buildRollbackWarning(Path latestPreUpgradeBackup, String latestSendFactAt) {
        if (latestPreUpgradeBackup == null || latestSendFactAt == null) {
            return null;
        }
        LocalDateTime sendFactAt = PersistenceSupport.parseDateTime(latestSendFactAt);
        if (sendFactAt == null) {
            return null;
        }
        long backupMillis = lastModifiedMillis(latestPreUpgradeBackup);
        LocalDateTime backupAt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(backupMillis), ZoneId.systemDefault());
        if (backupAt.isBefore(sendFactAt)) {
            return String.format(ROLLBACK_WARNING_TEMPLATE,
                    backupAt.format(DISPLAY_TIMESTAMP_FORMATTER),
                    sendFactAt.format(DISPLAY_TIMESTAMP_FORMATTER));
        }
        return null;
    }

    // ==================== 内部工具 ====================

    private Map<String, Object> emptyKindStatus(String dir) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("count", 0);
        status.put("latest", null);
        status.put("dir", dir);
        return status;
    }

    private String readIntegrityCheck(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA integrity_check")) {
            StringBuilder integrity = new StringBuilder();
            while (resultSet.next()) {
                if (integrity.length() > 0) {
                    integrity.append("; ");
                }
                integrity.append(resultSet.getString(1));
            }
            return integrity.toString();
        }
    }

    private int countTables(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private int readUserVersion(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    private String formatMtime(long millis) {
        if (millis <= 0) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                .format(DISPLAY_TIMESTAMP_FORMATTER);
    }

    private long backupSize(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return 0L;
        }
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }

    private long databaseSize(Path databaseFile) {
        return backupSize(databaseFile);
    }

    private int readSchemaVersion() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        } catch (Exception e) {
            log.debug("读取数据库架构版本失败：{}", e.getMessage());
            return 0;
        }
    }

    private int readTableCount() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        } catch (Exception e) {
            log.debug("读取数据库表数量失败：{}", e.getMessage());
            return 0;
        }
    }

    private String sanitizeReason(String reason) {
        String value = PersistenceSupport.trimToNull(reason);
        if (value == null) {
            return "manual";
        }
        String sanitized = value.replaceAll("[^a-zA-Z0-9_-]", "-");
        return sanitized.isBlank() ? "manual" : sanitized;
    }

    private String escapeSqlLiteral(String value) {
        return value.replace("'", "''");
    }
}
