package com.offerchaser.application.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 投递状态同步仓库（D2）：记录每次「投递状态同步」的运行结果（RUNNING/COMPLETED/FAILED）
 * 与逐条聊天快照（AUTO 自动投递 / MANUAL 手动投递 / HR_INITIATED HR 主动招呼）。
 * <p>
 * 运行表 {@code delivery_status_sync} 保存三类计数（total/read/replied/unread/unknown），
 * 快照表 {@code delivery_status_snapshot} 保存每条联系人的原始聊天状态证据；两表通过 sync_id 关联。
 */
@Service
@Slf4j
public class DeliveryStatusRepository {

    /** 一类来源的统计计数。 */
    public record Counts(int total, int read, int replied, int unread, int unknown) {

        public static Counts zero() {
            return new Counts(0, 0, 0, 0, 0);
        }
    }

    /**
     * 一次状态同步运行记录（与 delivery_status_sync 列一一对应；共 23 列：
     * 8 个运行信息字段 + 15 个统计字段）。
     */
    public record StatusSyncRun(String syncId, String triggerType, String scope, String targetDate,
                                String status, String startedAt, String finishedAt,
                                int autoTotal, int autoRead, int autoReplied, int autoUnread, int autoUnknown,
                                int manualTotal, int manualRead, int manualReplied, int manualUnread,
                                int manualUnknown,
                                int hrTotal, int hrRead, int hrReplied, int hrUnread, int hrUnknown,
                                String error) {

        public Counts autoCounts() {
            return new Counts(autoTotal, autoRead, autoReplied, autoUnread, autoUnknown);
        }

        public Counts manualCounts() {
            return new Counts(manualTotal, manualRead, manualReplied, manualUnread, manualUnknown);
        }

        public Counts hrCounts() {
            return new Counts(hrTotal, hrRead, hrReplied, hrUnread, hrUnknown);
        }
    }

    /** 逐条投递状态快照（与 delivery_status_snapshot 列一一对应；id 由数据库生成）。 */
    public record DeliveryStatusSnapshot(long id, String syncId, String source, String contactKey,
                                         String encryptUserId, String hrName, String companyName, String jobName,
                                         String deliveredStatus, String readStatus, String replyStatus,
                                         String initiator, String lastMessage, String chatTime, String evidence,
                                         String createdAt) {
    }

    private static final String SYNC_COLUMNS =
            "sync_id, trigger_type, scope, target_date, status, started_at, finished_at, " +
                    "auto_total, auto_read, auto_replied, auto_unread, auto_unknown, " +
                    "manual_total, manual_read, manual_replied, manual_unread, manual_unknown, " +
                    "hr_total, hr_read, hr_replied, hr_unread, hr_unknown, error";

    private static final String SNAPSHOT_COLUMNS =
            "id, sync_id, source, contact_key, encrypt_user_id, hr_name, company_name, job_name, " +
                    "delivered_status, read_status, reply_status, initiator, last_message, chat_time, " +
                    "evidence, created_at";

    private static final String DEFAULT_STATUS = "RUNNING";
    private static final int DEFAULT_RECENT_LIMIT = 10;
    private static final int MAX_RECENT_LIMIT = 100;

    private final DataSource dataSource;

    public DeliveryStatusRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ==================== 运行记录 ====================

    /**
     * 插入一条 RUNNING 状态同步记录；计数为 null 的项按 0 处理。
     * syncId/triggerType/scope/targetDate 必填，status 缺省 RUNNING，startedAt 缺省当前时间。
     */
    public void start(StatusSyncRun run) {
        if (run == null) {
            throw new IllegalArgumentException("statusSyncRun 不能为空");
        }
        String syncId = requireText(run.syncId(), "syncId");
        String triggerType = requireText(run.triggerType(), "triggerType");
        String scope = requireText(run.scope(), "scope");
        String targetDate = requireText(run.targetDate(), "targetDate");
        String status = PersistenceSupport.defaultIfBlank(run.status(), DEFAULT_STATUS);
        String startedAt = PersistenceSupport.defaultIfBlank(run.startedAt(), PersistenceSupport.now());
        String sql = "INSERT INTO delivery_status_sync(sync_id, trigger_type, scope, target_date, status, " +
                "started_at, finished_at, auto_total, auto_read, auto_replied, auto_unread, auto_unknown, " +
                "manual_total, manual_read, manual_replied, manual_unread, manual_unknown, " +
                "hr_total, hr_read, hr_replied, hr_unread, hr_unknown, error) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindSyncRun(statement, syncId, triggerType, scope, targetDate, status, startedAt,
                    PersistenceSupport.trimToNull(run.finishedAt()), run);
            statement.executeUpdate();
            log.info("状态同步开始 | syncId={} | trigger={} | scope={} | targetDate={}",
                    syncId, triggerType, scope, targetDate);
        } catch (SQLException e) {
            log.error("创建状态同步记录失败 | syncId={}：{}", syncId, e.getMessage(), e);
            throw new IllegalStateException("创建状态同步记录失败：" + e.getMessage(), e);
        }
    }

    /**
     * 结束一次状态同步：写入 finished_at（当前时间）、最终 status、三类计数与 error。
     * 计数为 null 的组按 0 处理。
     *
     * @throws IllegalArgumentException syncId 不存在
     */
    public void finish(String syncId, String status, Counts auto, Counts manual, Counts hr, String error) {
        String validSyncId = requireText(syncId, "syncId");
        String validStatus = requireText(status, "status");
        Counts autoCounts = normalize(auto);
        Counts manualCounts = normalize(manual);
        Counts hrCounts = normalize(hr);
        String sql = "UPDATE delivery_status_sync SET finished_at = ?, status = ?, " +
                "auto_total = ?, auto_read = ?, auto_replied = ?, auto_unread = ?, auto_unknown = ?, " +
                "manual_total = ?, manual_read = ?, manual_replied = ?, manual_unread = ?, manual_unknown = ?, " +
                "hr_total = ?, hr_read = ?, hr_replied = ?, hr_unread = ?, hr_unknown = ?, error = ? " +
                "WHERE sync_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, PersistenceSupport.now());
            statement.setString(2, validStatus);
            int index = 3;
            index = bindCounts(statement, index, autoCounts);
            index = bindCounts(statement, index, manualCounts);
            index = bindCounts(statement, index, hrCounts);
            statement.setString(index++, PersistenceSupport.trimToNull(error));
            statement.setString(index, validSyncId);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                throw new IllegalArgumentException("状态同步记录不存在：syncId=" + validSyncId);
            }
            log.info("状态同步结束 | syncId={} | status={}", validSyncId, validStatus);
        } catch (SQLException e) {
            log.error("更新状态同步记录失败 | syncId={}：{}", validSyncId, e.getMessage(), e);
            throw new IllegalStateException("更新状态同步记录失败：" + e.getMessage(), e);
        }
    }

    /** 按 syncId 查询运行记录；不存在返回 null。 */
    public StatusSyncRun findBySyncId(String syncId) {
        if (PersistenceSupport.trimToNull(syncId) == null) {
            return null;
        }
        String sql = "SELECT " + SYNC_COLUMNS + " FROM delivery_status_sync WHERE sync_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, syncId.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapSyncRun(resultSet) : null;
            }
        } catch (SQLException e) {
            log.error("查询状态同步记录失败 | syncId={}：{}", syncId, e.getMessage(), e);
            throw new IllegalStateException("查询状态同步记录失败：" + e.getMessage(), e);
        }
    }

    /** 最近一次状态同步（按 started_at DESC）；无记录返回 null。 */
    public StatusSyncRun findLatest() {
        String sql = "SELECT " + SYNC_COLUMNS + " FROM delivery_status_sync " +
                "ORDER BY started_at DESC, rowid DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? mapSyncRun(resultSet) : null;
        } catch (SQLException e) {
            log.error("查询最近状态同步失败：{}", e.getMessage(), e);
            throw new IllegalStateException("查询最近状态同步失败：" + e.getMessage(), e);
        }
    }

    /**
     * 查询指定日期的已完成启动同步（trigger_type=STARTUP, status=COMPLETED）；
     * targetDate 为空时返回任意日期最近一次完成的启动同步。无记录返回 null。
     */
    public StatusSyncRun findCompletedStartup(String targetDate) {
        boolean hasDate = PersistenceSupport.trimToNull(targetDate) != null;
        String sql = "SELECT " + SYNC_COLUMNS + " FROM delivery_status_sync " +
                "WHERE trigger_type = 'STARTUP' AND status = 'COMPLETED'" +
                (hasDate ? " AND target_date = ?" : "") +
                " ORDER BY started_at DESC, rowid DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (hasDate) {
                statement.setString(1, targetDate.trim());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapSyncRun(resultSet) : null;
            }
        } catch (SQLException e) {
            log.error("查询已完成启动同步失败 | targetDate={}：{}", targetDate, e.getMessage(), e);
            throw new IllegalStateException("查询已完成启动同步失败：" + e.getMessage(), e);
        }
    }

    /** 最近的同步运行记录（started_at DESC），limit<=0 时取默认 10，最多 100。 */
    public List<StatusSyncRun> findRecent(int limit) {
        int safeLimit = limit <= 0 ? DEFAULT_RECENT_LIMIT : Math.min(limit, MAX_RECENT_LIMIT);
        String sql = "SELECT " + SYNC_COLUMNS + " FROM delivery_status_sync " +
                "ORDER BY started_at DESC, rowid DESC LIMIT ?";
        List<StatusSyncRun> runs = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    runs.add(mapSyncRun(resultSet));
                }
            }
            return runs;
        } catch (SQLException e) {
            log.error("查询最近状态同步列表失败：{}", e.getMessage(), e);
            throw new IllegalStateException("查询最近状态同步列表失败：" + e.getMessage(), e);
        }
    }

    // ==================== 快照明细 ====================

    /**
     * 批量插入状态同步快照（单事务）：空列表/nulls 为 no-op；任一条 syncId/source 为空则整体回滚，
     * 不产生半写入。createdAt 缺省当前时间；id 由数据库生成。
     */
    public void insertSnapshots(List<DeliveryStatusSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO delivery_status_snapshot(sync_id, source, contact_key, encrypt_user_id, " +
                "hr_name, company_name, job_name, delivered_status, read_status, reply_status, initiator, " +
                "last_message, chat_time, evidence, created_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int row = 0;
                for (DeliveryStatusSnapshot snapshot : snapshots) {
                    row++;
                    if (snapshot == null) {
                        throw new IllegalArgumentException("第 " + row + " 条快照不能为空");
                    }
                    String syncId = requireText(snapshot.syncId(), "snapshot.syncId（第 " + row + " 条）");
                    String source = requireText(snapshot.source(), "snapshot.source（第 " + row + " 条）");
                    statement.setString(1, syncId);
                    statement.setString(2, source);
                    statement.setString(3, PersistenceSupport.trimToNull(snapshot.contactKey()));
                    statement.setString(4, PersistenceSupport.trimToNull(snapshot.encryptUserId()));
                    statement.setString(5, PersistenceSupport.trimToNull(snapshot.hrName()));
                    statement.setString(6, PersistenceSupport.trimToNull(snapshot.companyName()));
                    statement.setString(7, PersistenceSupport.trimToNull(snapshot.jobName()));
                    statement.setString(8, PersistenceSupport.trimToNull(snapshot.deliveredStatus()));
                    statement.setString(9, PersistenceSupport.trimToNull(snapshot.readStatus()));
                    statement.setString(10, PersistenceSupport.trimToNull(snapshot.replyStatus()));
                    statement.setString(11, PersistenceSupport.trimToNull(snapshot.initiator()));
                    statement.setString(12, PersistenceSupport.trimToNull(snapshot.lastMessage()));
                    statement.setString(13, PersistenceSupport.trimToNull(snapshot.chatTime()));
                    statement.setString(14, PersistenceSupport.trimToNull(snapshot.evidence()));
                    statement.setString(15, PersistenceSupport.defaultIfBlank(
                            snapshot.createdAt(), PersistenceSupport.now()));
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
                log.info("状态同步快照已写入 | count={} | syncId={}", snapshots.size(),
                        snapshots.get(0) == null ? null : snapshots.get(0).syncId());
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (SQLException ignore) {
                    // 连接关闭时由连接池处理
                }
            }
        } catch (SQLException e) {
            log.error("写入状态同步快照失败：{}", e.getMessage(), e);
            throw new IllegalStateException("写入状态同步快照失败：" + e.getMessage(), e);
        }
    }

    /** 某次同步的逐条快照（按 id ASC）；无记录返回空列表。 */
    public List<DeliveryStatusSnapshot> findSnapshots(String syncId) {
        if (PersistenceSupport.trimToNull(syncId) == null) {
            return List.of();
        }
        String sql = "SELECT " + SNAPSHOT_COLUMNS + " FROM delivery_status_snapshot " +
                "WHERE sync_id = ? ORDER BY id ASC";
        List<DeliveryStatusSnapshot> snapshots = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, syncId.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    snapshots.add(mapSnapshot(resultSet));
                }
            }
            return snapshots;
        } catch (SQLException e) {
            log.error("查询状态同步快照失败 | syncId={}：{}", syncId, e.getMessage(), e);
            throw new IllegalStateException("查询状态同步快照失败：" + e.getMessage(), e);
        }
    }

    // ==================== 内部工具 ====================

    private void bindSyncRun(PreparedStatement statement, String syncId, String triggerType, String scope,
                             String targetDate, String status, String startedAt, String finishedAt,
                             StatusSyncRun run) throws SQLException {
        statement.setString(1, syncId);
        statement.setString(2, triggerType);
        statement.setString(3, scope);
        statement.setString(4, targetDate);
        statement.setString(5, status);
        statement.setString(6, startedAt);
        statement.setString(7, finishedAt);
        int index = 8;
        index = bindCounts(statement, index, normalize(run.autoCounts()));
        index = bindCounts(statement, index, normalize(run.manualCounts()));
        index = bindCounts(statement, index, normalize(run.hrCounts()));
        statement.setString(index, PersistenceSupport.trimToNull(run.error()));
    }

    private int bindCounts(PreparedStatement statement, int startIndex, Counts counts) throws SQLException {
        Counts safe = normalize(counts);
        statement.setInt(startIndex, safe.total());
        statement.setInt(startIndex + 1, safe.read());
        statement.setInt(startIndex + 2, safe.replied());
        statement.setInt(startIndex + 3, safe.unread());
        statement.setInt(startIndex + 4, safe.unknown());
        return startIndex + 5;
    }

    private Counts normalize(Counts counts) {
        return counts == null ? Counts.zero() : counts;
    }

    private StatusSyncRun mapSyncRun(ResultSet resultSet) throws SQLException {
        return new StatusSyncRun(
                resultSet.getString("sync_id"),
                resultSet.getString("trigger_type"),
                resultSet.getString("scope"),
                resultSet.getString("target_date"),
                resultSet.getString("status"),
                resultSet.getString("started_at"),
                resultSet.getString("finished_at"),
                resultSet.getInt("auto_total"),
                resultSet.getInt("auto_read"),
                resultSet.getInt("auto_replied"),
                resultSet.getInt("auto_unread"),
                resultSet.getInt("auto_unknown"),
                resultSet.getInt("manual_total"),
                resultSet.getInt("manual_read"),
                resultSet.getInt("manual_replied"),
                resultSet.getInt("manual_unread"),
                resultSet.getInt("manual_unknown"),
                resultSet.getInt("hr_total"),
                resultSet.getInt("hr_read"),
                resultSet.getInt("hr_replied"),
                resultSet.getInt("hr_unread"),
                resultSet.getInt("hr_unknown"),
                resultSet.getString("error"));
    }

    private DeliveryStatusSnapshot mapSnapshot(ResultSet resultSet) throws SQLException {
        return new DeliveryStatusSnapshot(
                resultSet.getLong("id"),
                resultSet.getString("sync_id"),
                resultSet.getString("source"),
                resultSet.getString("contact_key"),
                resultSet.getString("encrypt_user_id"),
                resultSet.getString("hr_name"),
                resultSet.getString("company_name"),
                resultSet.getString("job_name"),
                resultSet.getString("delivered_status"),
                resultSet.getString("read_status"),
                resultSet.getString("reply_status"),
                resultSet.getString("initiator"),
                resultSet.getString("last_message"),
                resultSet.getString("chat_time"),
                resultSet.getString("evidence"),
                resultSet.getString("created_at"));
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("状态同步快照事务回滚失败：{}", e.getMessage());
        }
    }

    private String requireText(String value, String fieldName) {
        String trimmed = PersistenceSupport.trimToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return trimmed;
    }
}
