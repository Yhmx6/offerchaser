package com.offerchaser.application.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 任务运行记录仓库：把一轮投递任务的运行状态持久化到 {@code task_run} 表。
 * <p>
 * 内存中的 {@code BossRunContext} 仍然是运行时状态的唯一权威；这里只负责异步/事后可查的落库记录，
 * 落库失败只记日志，不改变任务本身的正确性。
 */
@Service
@Slf4j
public class TaskRunRepository {

    private static final String INSERT_OR_UPDATE_START_SQL =
            "INSERT INTO task_run(run_id, platform, state, config_snapshot, started_at, updated_at) " +
                    "VALUES(?, ?, 'STARTING', ?, ?, ?) " +
                    "ON CONFLICT(run_id) DO UPDATE SET platform = excluded.platform, " +
                    "config_snapshot = COALESCE(excluded.config_snapshot, task_run.config_snapshot), " +
                    "updated_at = excluded.updated_at";

    private static final String UPDATE_STATE_SQL =
            "UPDATE task_run SET state = ?, " +
                    "stop_reason = COALESCE(?, stop_reason), " +
                    "error = COALESCE(?, error), " +
                    "updated_at = ? " +
                    "WHERE run_id = ?";

    private static final String FINISH_SQL =
            "UPDATE task_run SET state = ?, stop_reason = ?, error = ?, ended_at = ?, updated_at = ? " +
                    "WHERE run_id = ?";

    private final DataSource dataSource;

    public TaskRunRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 创建（或幂等复用）一轮任务的运行记录。 */
    public void start(String runId, String platform, String configSnapshot) {
        if (PersistenceSupport.trimToNull(runId) == null) {
            log.warn("task_run.start 跳过：runId 为空");
            return;
        }
        String now = PersistenceSupport.now();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_OR_UPDATE_START_SQL)) {
            statement.setString(1, runId);
            statement.setString(2, PersistenceSupport.defaultIfBlank(platform, "unknown"));
            statement.setString(3, configSnapshot);
            statement.setString(4, now);
            statement.setString(5, now);
            statement.executeUpdate();
            log.info("任务运行记录已创建 | runId={} | platform={}", runId, platform);
        } catch (SQLException e) {
            log.error("写入 task_run 启动记录失败 | runId={}：{}", runId, e.getMessage(), e);
        }
    }

    /** 更新任务状态（stopReason / error 为 null 时保留旧值）。 */
    public void updateState(String runId, String state, String stopReason, String error) {
        if (PersistenceSupport.trimToNull(runId) == null) {
            log.warn("task_run.updateState 跳过：runId 为空");
            return;
        }
        if (PersistenceSupport.trimToNull(state) == null) {
            log.warn("task_run.updateState 跳过：state 为空 | runId={}", runId);
            return;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE_STATE_SQL)) {
            statement.setString(1, state);
            statement.setString(2, stopReason);
            statement.setString(3, error);
            statement.setString(4, PersistenceSupport.now());
            statement.setString(5, runId);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                log.warn("task_run 状态更新未命中，任务记录可能不存在 | runId={} | state={}", runId, state);
            } else {
                log.info("任务状态已更新 | runId={} | state={} | stopReason={}", runId, state, stopReason);
            }
        } catch (SQLException e) {
            log.error("更新 task_run 状态失败 | runId={} | state={}：{}", runId, state, e.getMessage(), e);
        }
    }

    /** 结束任务：写入最终状态、原因、错误与结束时间。 */
    public void finish(String runId, String state, String stopReason, String error) {
        if (PersistenceSupport.trimToNull(runId) == null) {
            log.warn("task_run.finish 跳过：runId 为空");
            return;
        }
        String finalState = PersistenceSupport.defaultIfBlank(state, "UNKNOWN");
        String now = PersistenceSupport.now();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FINISH_SQL)) {
            statement.setString(1, finalState);
            statement.setString(2, stopReason);
            statement.setString(3, error);
            statement.setString(4, now);
            statement.setString(5, now);
            statement.setString(6, runId);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                log.warn("task_run 结束记录未命中，任务记录可能不存在 | runId={} | state={}", runId, finalState);
            } else {
                log.info("任务运行记录已结束 | runId={} | state={} | stopReason={}", runId, finalState, stopReason);
            }
        } catch (SQLException e) {
            log.error("写入 task_run 结束状态失败 | runId={} | state={}：{}", runId, finalState, e.getMessage(), e);
        }
    }
}
