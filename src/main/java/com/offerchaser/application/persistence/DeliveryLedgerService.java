package com.offerchaser.application.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 投递台账服务：记录每一次投递尝试（文字 + 图片的独立确认状态）、关键事件与预算结算。
 * <p>
 * A-05 起新增 {@link #settleAttempt(SettlementRequest)}：在<b>单个数据库事务</b>中完成
 * 「attempt 文字/图片状态 → 投递事件 → 预算预留状态转移 + daily_budget 聚合更新」，
 * 任一步失败整体回滚并返回 false，由下次任务启动的 reconcile 恢复，避免出现「半结算」。
 * <p>
 * 原则：有证据才记 CONFIRMED；超时/崩溃等不确定结果记 UNKNOWN，绝不把不确定当成功。
 */
@Service
@Slf4j
public class DeliveryLedgerService {

    /** 已开始的投递尝试：数据库主键 + 业务 attemptId。 */
    public record AttemptStarted(long rowId, String attemptId) {
    }

    /**
     * 一次投递结算时对预算预留执行的动作。
     * <p>
     * 与 {@code text_status} 相互独立：扫描/身份不符等「未真正发送」场景通常是 RELEASE，
     * 已产生沟通动作但结果未知的场景是 UNKNOWN（保守占额），有明确证据才 CONFIRM。
     */
    public enum BudgetSettlement {
        /** 确认已发送：RESERVED → CONFIRMED，reserved-1 / consumed+1 */
        CONFIRM,
        /** 结果未知：RESERVED → UNKNOWN，reserved-1 / uncertain+1（保守占额） */
        UNKNOWN,
        /** 释放预留：RESERVED → RELEASED，reserved-1 */
        RELEASE,
        /** 不涉及预算（例如调试/无预留场景） */
        NONE
    }

    /**
     * 一次投递尝试的原子结算请求。
     * <p>
     * 由 Boss 主流程在发送结束后组装，交给 {@link #settleAttempt(SettlementRequest)} 在单个数据库事务中落库：
     * 文字/图片状态、投递事件、预算预留转移与 daily_budget 聚合更新要么全部成功，要么全部回滚。
     *
     * @param attemptRowId      delivery_attempt 行 id
     * @param textStatus        本次文字发送的最终判定；null 视为 NOT_ATTEMPTED，PENDING 会被拒绝
     * @param textEvidenceLevel 文字证据等级（如 DOM/TIMEOUT）
     * @param textDetail        文字证据说明
     * @param textErrorCode     文字发送错误码（可空）
     * @param imageStatus       图片发送结果，NOT_ATTEMPTED/null 表示未尝试
     * @param imageEvidenceLevel 图片证据等级
     * @param imageDetail       图片证据说明
     * @param reservationId     本次使用的逐笔预算预留 id；budgetAction=NONE 时可为 null
     * @param budgetAction      预算结算动作
     * @param finalErrorCode    写入 attempt 的最终错误码
     * @param finalDetail       写入 attempt 的最终错误说明
     */
    public record SettlementRequest(long attemptRowId, TextSendStatus textStatus,
                                    String textEvidenceLevel, String textDetail, String textErrorCode,
                                    ImageSendStatus imageStatus, String imageEvidenceLevel, String imageDetail,
                                    String reservationId, BudgetSettlement budgetAction,
                                    String finalErrorCode, String finalDetail) {
    }

    private static final String INSERT_ATTEMPT_SQL =
            "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, encrypt_id, encrypt_user_id, " +
                    "job_name, company_name, job_url, snapshot_hash, message_hash, resume_hash, " +
                    "text_status, image_status, budget_id, contact_key, parent_attempt_id, started_at, created_at) " +
                    "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String INSERT_EVENT_SQL =
            "INSERT INTO delivery_event(attempt_id, run_id, event_type, evidence_level, detail, occurred_at) " +
                    "VALUES(?, ?, ?, ?, ?, ?)";

    private static final String TODAY_SUMMARY_SQL =
            "SELECT COUNT(*), " +
                    "COALESCE(SUM(CASE WHEN text_status = 'PENDING' THEN 1 ELSE 0 END), 0), " +
                    "COALESCE(SUM(CASE WHEN text_status = 'CONFIRMED' THEN 1 ELSE 0 END), 0), " +
                    "COALESCE(SUM(CASE WHEN text_status = 'FAILED' THEN 1 ELSE 0 END), 0), " +
                    "COALESCE(SUM(CASE WHEN text_status = 'UNKNOWN' THEN 1 ELSE 0 END), 0), " +
                    "COALESCE(SUM(CASE WHEN image_status = 'CONFIRMED' THEN 1 ELSE 0 END), 0), " +
                    "COALESCE(SUM(CASE WHEN image_status = 'FAILED' THEN 1 ELSE 0 END), 0), " +
                    "COALESCE(SUM(CASE WHEN image_status = 'UNKNOWN' THEN 1 ELSE 0 END), 0), " +
                    "COALESCE(SUM(CASE WHEN text_status = 'CONFIRMED' AND kind = 'FIRST_CONTACT' THEN 1 ELSE 0 END), 0), " +
                    "COALESCE(SUM(CASE WHEN text_status = 'CONFIRMED' AND kind = 'FOLLOWUP' THEN 1 ELSE 0 END), 0) " +
                    "FROM delivery_attempt WHERE account = ? AND started_at >= ?";

    private final DataSource dataSource;
    private final String account;
    private final ZoneId zone;

    public DeliveryLedgerService(DataSource dataSource,
                                 @Value("${offerchaser.boss.budget-account:local}") String account,
                                 @Value("${offerchaser.boss.budget-timezone:Asia/Shanghai}") String timezone) {
        this.dataSource = dataSource;
        this.account = PersistenceSupport.defaultIfBlank(account, "local");
        this.zone = PersistenceSupport.zone(timezone);
    }

    /**
     * 开始一次投递尝试（A-05 新契约）：同一事务内插入 delivery_attempt(PENDING) + ATTEMPT_CREATED 事件，
     * 并回填 {@code budget_reservation.attempt_id}。
     * <p>
     * 写入失败（含预留不存在）会抛异常，调用方不得在台账未落库的情况下继续发送。
     *
     * @param reservationId 逐笔预算预留 id；为 null/空表示本次不涉及预算
     */
    public AttemptStarted beginAttempt(String runId, DeliveryKind kind, String encryptId, String encryptUserId,
                                       String jobName, String companyName, String jobUrl, String snapshotHash,
                                       String messageHash, String resumeHash, String reservationId) {
        return beginAttempt(runId, kind, encryptId, encryptUserId, jobName, companyName, jobUrl,
                snapshotHash, messageHash, resumeHash, reservationId, null, null);
    }

    /**
     * 开始一次投递尝试（B3 扩展）：额外写入联系人关联字段。
     *
     * @param contactKey      联系人键（{@code "uid:<encryptUserId>"}），可空
     * @param parentAttemptId 图片补发时指向原 attempt，可空（普通投递为空）
     */
    public AttemptStarted beginAttempt(String runId, DeliveryKind kind, String encryptId, String encryptUserId,
                                       String jobName, String companyName, String jobUrl, String snapshotHash,
                                       String messageHash, String resumeHash, String reservationId,
                                       String contactKey, String parentAttemptId) {
        return beginAttemptInternal(runId, kind, encryptId, encryptUserId, jobName, companyName, jobUrl,
                snapshotHash, messageHash, resumeHash, PersistenceSupport.trimToNull(reservationId),
                PersistenceSupport.trimToNull(contactKey), PersistenceSupport.trimToNull(parentAttemptId), null);
    }

    /**
     * @deprecated 旧版以 daily_budget 行 id 传入的签名，仅保留给旧测试/旧调用；Boss 已改用
     * {@link #beginAttempt(String, DeliveryKind, String, String, String, String, String, String, String, String, String)}。
     */
    @Deprecated
    public AttemptStarted beginAttempt(String runId, DeliveryKind kind, String encryptId, String encryptUserId,
                                       String jobName, String companyName, String jobUrl, String snapshotHash,
                                       String messageHash, String resumeHash, long budgetReservationId) {
        return beginAttemptInternal(runId, kind, encryptId, encryptUserId, jobName, companyName, jobUrl,
                snapshotHash, messageHash, resumeHash, null, null, null,
                budgetReservationId > 0 ? budgetReservationId : null);
    }

    private AttemptStarted beginAttemptInternal(String runId, DeliveryKind kind, String encryptId,
                                                String encryptUserId, String jobName, String companyName,
                                                String jobUrl, String snapshotHash, String messageHash,
                                                String resumeHash, String reservationId, String contactKey,
                                                String parentAttemptId, Long legacyBudgetRowId) {
        String attemptId = UUID.randomUUID().toString();
        String effectiveRunId = PersistenceSupport.defaultIfBlank(runId, "unknown");
        String effectiveKind = (kind == null ? DeliveryKind.FIRST_CONTACT : kind).name();
        String now = PersistenceSupport.now();
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Long budgetId = null;
                Long reservationRowId = null;
                if (reservationId != null) {
                    ReservationSettlement.ReservationRow reservation =
                            ReservationSettlement.find(connection, reservationId);
                    if (reservation == null) {
                        throw new SQLException("预算预留不存在，拒绝创建投递尝试：reservationId=" + reservationId);
                    }
                    reservationRowId = reservation.id();
                    // delivery_attempt.budget_id 在 A-05 后保存 budget_reservation 行 id，便于追溯
                    budgetId = reservation.id();
                } else if (legacyBudgetRowId != null) {
                    budgetId = legacyBudgetRowId;
                }
                long rowId;
                try (PreparedStatement statement =
                             connection.prepareStatement(INSERT_ATTEMPT_SQL, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, attemptId);
                    statement.setString(2, effectiveRunId);
                    statement.setString(3, account);
                    statement.setString(4, effectiveKind);
                    statement.setString(5, encryptId);
                    statement.setString(6, encryptUserId);
                    statement.setString(7, jobName);
                    statement.setString(8, companyName);
                    statement.setString(9, jobUrl);
                    statement.setString(10, snapshotHash);
                    statement.setString(11, messageHash);
                    statement.setString(12, resumeHash);
                    statement.setString(13, TextSendStatus.PENDING.name());
                    statement.setString(14, ImageSendStatus.NOT_ATTEMPTED.name());
                    statement.setObject(15, budgetId);
                    statement.setString(16, contactKey);
                    statement.setString(17, parentAttemptId);
                    statement.setString(18, now);
                    statement.setString(19, now);
                    statement.executeUpdate();
                    rowId = readGeneratedKey(statement, connection);
                }
                insertEvent(connection, attemptId, effectiveRunId,
                        "ATTEMPT_CREATED", "LOCAL", "创建投递尝试 | kind=" + effectiveKind + " | job=" + jobName, now);
                if (reservationRowId != null) {
                    executeUpdate(connection,
                            "UPDATE budget_reservation SET attempt_id = ? WHERE reservation_id = ?",
                            attemptId, reservationId);
                } else if (legacyBudgetRowId != null) {
                    // 旧接口兜底：按 daily_budget 行 id 关联到仍未结算的逐笔预留，便于 reconcile 正确判断 running
                    executeUpdate(connection,
                            "UPDATE budget_reservation SET attempt_id = ? " +
                                    "WHERE budget_id = ? AND state = 'RESERVED' AND attempt_id IS NULL",
                            attemptId, legacyBudgetRowId);
                }
                connection.commit();
                log.info("投递尝试已创建 | attemptId={} | kind={} | encryptId={} | job={} | reservationId={}",
                        attemptId, effectiveKind, encryptId, jobName, reservationId);
                return new AttemptStarted(rowId, attemptId);
            } catch (SQLException e) {
                rollbackQuietly(connection);
                throw e;
            } catch (RuntimeException e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }
        } catch (SQLException e) {
            log.error("创建投递尝试失败 | runId={} | encryptId={}：{}", runId, encryptId, e.getMessage(), e);
            throw new IllegalStateException("创建投递尝试失败：" + e.getMessage(), e);
        }
    }

    /**
     * 原子结算一次投递尝试（A-05 新契约）。
     * <p>
     * 单个连接、单个事务依次执行：
     * <ol>
     *   <li>更新 attempt 的 text_status / text_evidence / error_code / finished_at（终态才写 finished_at）；</li>
     *   <li>更新 image_status / image_evidence（未尝试时不写）；</li>
     *   <li>写 delivery_event（TEXT_* / JOB_SKIPPED / IMAGE_* / BUDGET_*）；</li>
     *   <li>预算预留状态转移 + daily_budget 聚合更新（条件转移，仅 RESERVED 生效一次）；</li>
     *   <li>commit。</li>
     * </ol>
     * 任一步异常 → rollback 并返回 false，调用方记录 ERROR，等待下次启动 reconcile 恢复。
     * 已结算过的 attempt 重试时直接返回 true（幂等 no-op）。
     *
     * @return true=结算成功或已结算过；false=事务回滚/参数非法，调用方不得把本次当成功
     */
    public boolean settleAttempt(SettlementRequest request) {
        if (request == null || request.attemptRowId() <= 0) {
            log.warn("settleAttempt 参数无效，已忽略");
            return false;
        }
        TextSendStatus textStatus = request.textStatus() == null
                ? TextSendStatus.NOT_ATTEMPTED : request.textStatus();
        if (textStatus == TextSendStatus.PENDING) {
            log.warn("settleAttempt 拒绝把 attempt 结算为 PENDING（那不是终态）| attemptRowId={}",
                    request.attemptRowId());
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                AttemptSnapshot attempt = findAttempt(connection, request.attemptRowId());
                if (attempt == null) {
                    log.error("settleAttempt 找不到投递尝试，无法结算 | attemptRowId={}", request.attemptRowId());
                    rollbackQuietly(connection);
                    return false;
                }
                if (attempt.finishedAt() != null) {
                    // 单事务保证 attempt 终态与预算结算同时提交，这里说明上次已完整结算成功
                    log.info("投递尝试已结算，幂等跳过 | attemptRowId={} | attemptId={}",
                            request.attemptRowId(), attempt.attemptId());
                    rollbackQuietly(connection);
                    return true;
                }
                String now = PersistenceSupport.now();
                String textEvidence = PersistenceSupport.evidence(
                        request.textEvidenceLevel(), request.textDetail());
                // 1) 文字终态 + 最终错误信息
                // finalErrorCode 优先于 textErrorCode：二者语义不同，前者是本次尝试的最终错误码，
                // 后者只用于文字步骤（例如文字成功但图片失败时，最终错误码应保留图片错误）。
                String attemptErrorCode = request.finalErrorCode() != null
                        ? request.finalErrorCode() : request.textErrorCode();
                executeUpdate(connection,
                        "UPDATE delivery_attempt SET text_status = ?, text_evidence = ?, " +
                                "error_code = COALESCE(?, error_code), error_detail = COALESCE(?, error_detail), " +
                                "finished_at = ? WHERE id = ?",
                        textStatus.name(), textEvidence,
                        PersistenceSupport.trimToNull(attemptErrorCode),
                        PersistenceSupport.trimToNull(request.finalDetail()),
                        now, request.attemptRowId());
                // 2) 图片状态（与文字独立）
                if (request.imageStatus() != null && request.imageStatus() != ImageSendStatus.NOT_ATTEMPTED) {
                    executeUpdate(connection,
                            "UPDATE delivery_attempt SET image_status = ?, image_evidence = ? WHERE id = ?",
                            request.imageStatus().name(),
                            PersistenceSupport.evidence(request.imageEvidenceLevel(), request.imageDetail()),
                            request.attemptRowId());
                }
                // 3) 事件
                appendTextEvent(connection, attempt, textStatus, request, now);
                if (request.imageStatus() != null && request.imageStatus() != ImageSendStatus.NOT_ATTEMPTED) {
                    appendImageEvent(connection, attempt, request, now);
                }
                // 4) 预算预留结算 + 聚合（失败则整体回滚）
                if (!applyBudgetSettlement(connection, attempt, request, now)) {
                    throw new SQLException("预算预留结算失败");
                }
                connection.commit();
                log.info("投递尝试已原子结算 | attemptRowId={} | attemptId={} | text={} | image={} | budget={}",
                        request.attemptRowId(), attempt.attemptId(), textStatus,
                        request.imageStatus(), request.budgetAction());
                return true;
            } catch (SQLException e) {
                rollbackQuietly(connection);
                log.error("投递结算失败，已回滚（等待下次启动 reconcile 恢复）| attemptRowId={}：{}",
                        request.attemptRowId(), e.getMessage(), e);
                return false;
            } catch (RuntimeException e) {
                rollbackQuietly(connection);
                log.error("投递结算异常，已回滚 | attemptRowId={}", request.attemptRowId(), e);
                return false;
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }
        } catch (SQLException e) {
            log.error("投递结算无法获取数据库连接 | attemptRowId={}：{}",
                    request.attemptRowId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * 更新文字发送状态（旧接口，Boss 已改用 {@link #settleAttempt}）。
     */
    public void markTextSent(long attemptRowId, TextSendStatus status, String evidenceLevel,
                             String detail, String errorCode) {
        if (attemptRowId <= 0 || status == null) {
            log.warn("markTextSent 参数无效，已忽略 | attemptRowId={} | status={}", attemptRowId, status);
            return;
        }
        boolean finalStatus = status == TextSendStatus.CONFIRMED
                || status == TextSendStatus.FAILED
                || status == TextSendStatus.UNKNOWN;
        String sql = finalStatus
                ? "UPDATE delivery_attempt SET text_status = ?, text_evidence = ?, " +
                "error_code = COALESCE(?, error_code), finished_at = ? WHERE id = ?"
                : "UPDATE delivery_attempt SET text_status = ?, text_evidence = ?, " +
                "error_code = COALESCE(?, error_code) WHERE id = ?";
        String evidence = PersistenceSupport.evidence(evidenceLevel, detail);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setString(2, evidence);
            statement.setString(3, PersistenceSupport.trimToNull(errorCode));
            int index = 4;
            if (finalStatus) {
                statement.setString(index++, PersistenceSupport.now());
            }
            statement.setLong(index, attemptRowId);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                log.warn("文字发送状态更新未命中 | attemptRowId={} | status={}", attemptRowId, status);
                return;
            }
            log.info("文字发送状态已更新 | attemptRowId={} | status={} | evidence={}",
                    attemptRowId, status, evidence);
        } catch (SQLException e) {
            log.error("更新文字发送状态失败 | attemptRowId={} | status={}：{}",
                    attemptRowId, status, e.getMessage(), e);
        }
    }

    /** 更新图片发送状态（旧接口，Boss 已改用 {@link #settleAttempt}）。 */
    public void markImageSent(long attemptRowId, ImageSendStatus status, String evidenceLevel, String detail) {
        if (attemptRowId <= 0 || status == null) {
            log.warn("markImageSent 参数无效，已忽略 | attemptRowId={} | status={}", attemptRowId, status);
            return;
        }
        String sql = "UPDATE delivery_attempt SET image_status = ?, image_evidence = ? WHERE id = ?";
        String evidence = PersistenceSupport.evidence(evidenceLevel, detail);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setString(2, evidence);
            statement.setLong(3, attemptRowId);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                log.warn("图片发送状态更新未命中 | attemptRowId={} | status={}", attemptRowId, status);
                return;
            }
            log.info("图片发送状态已更新 | attemptRowId={} | status={} | evidence={}",
                    attemptRowId, status, evidence);
        } catch (SQLException e) {
            log.error("更新图片发送状态失败 | attemptRowId={} | status={}：{}",
                    attemptRowId, status, e.getMessage(), e);
        }
    }

    /** 追加一条投递事件；attemptRowId 可传 0 表示与具体尝试无关的事件。 */
    public void appendEvent(long attemptRowId, String runId, String eventType,
                            String evidenceLevel, String detail) {
        if (PersistenceSupport.trimToNull(eventType) == null) {
            log.warn("appendEvent 跳过：eventType 为空");
            return;
        }
        String attemptId = null;
        String effectiveRunId = runId;
        try (Connection connection = dataSource.getConnection()) {
            if (attemptRowId > 0) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT attempt_id, run_id FROM delivery_attempt WHERE id = ?")) {
                    statement.setLong(1, attemptRowId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            attemptId = resultSet.getString("attempt_id");
                            if (PersistenceSupport.trimToNull(effectiveRunId) == null) {
                                effectiveRunId = resultSet.getString("run_id");
                            }
                        }
                    }
                }
            }
            insertEvent(connection, attemptId, effectiveRunId,
                    eventType, evidenceLevel, detail, PersistenceSupport.now());
        } catch (SQLException e) {
            log.error("追加投递事件失败 | attemptRowId={} | eventType={}：{}",
                    attemptRowId, eventType, e.getMessage(), e);
        }
    }

    /** 结束一次投递尝试（旧接口，Boss 已改用 {@link #settleAttempt}）。 */
    public void finishAttempt(long attemptRowId, String errorCode, String errorDetail) {
        if (attemptRowId <= 0) {
            return;
        }
        String sql = "UPDATE delivery_attempt SET finished_at = ?, " +
                "error_code = COALESCE(?, error_code), error_detail = COALESCE(?, error_detail) WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, PersistenceSupport.now());
            statement.setString(2, PersistenceSupport.trimToNull(errorCode));
            statement.setString(3, PersistenceSupport.trimToNull(errorDetail));
            statement.setLong(4, attemptRowId);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                log.warn("结束投递尝试未命中 | attemptRowId={}", attemptRowId);
            }
        } catch (SQLException e) {
            log.error("结束投递尝试失败 | attemptRowId={}：{}", attemptRowId, e.getMessage(), e);
        }
    }

    /** 该岗位是否已有确认成功的首次沟通（用于去重与跟进决策）。 */
    public boolean hasConfirmedFirstContact(String encryptId) {
        return countConfirmedFirstContact(" AND encrypt_id = ?", encryptId) > 0;
    }

    /** 今日确认成功的首次沟通数量。 */
    public int confirmedFirstContactCountToday() {
        String sql = "SELECT COUNT(*) FROM delivery_attempt WHERE account = ? " +
                "AND kind = 'FIRST_CONTACT' AND text_status = 'CONFIRMED' AND started_at >= ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account);
            statement.setString(2, PersistenceSupport.todayStart(zone));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error("统计今日首次沟通数量失败：{}", e.getMessage(), e);
            return 0;
        }
    }

    /** 该岗位已确认成功的首次沟通时间（最近一次），没有返回 null。 */
    public LocalDateTime lastConfirmedFirstContactAt(String encryptId) {
        if (PersistenceSupport.trimToNull(encryptId) == null) {
            return null;
        }
        String sql = "SELECT started_at FROM delivery_attempt " +
                "WHERE kind = 'FIRST_CONTACT' AND text_status = 'CONFIRMED' AND encrypt_id = ? " +
                "ORDER BY id DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, encryptId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? PersistenceSupport.parseDateTime(resultSet.getString(1))
                        : null;
            }
        } catch (SQLException e) {
            log.error("读取岗位最近确认首次沟通时间失败 | encryptId={}：{}", encryptId, e.getMessage(), e);
            return null;
        }
    }

    /** 该岗位已记录的跟进次数。 */
    public int followupCount(String encryptId) {
        String sql = "SELECT followup_count FROM contact_followup WHERE account = ? AND encrypt_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account);
            statement.setString(2, encryptId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt("followup_count") : 0;
            }
        } catch (SQLException e) {
            log.error("读取跟进次数失败 | encryptId={}：{}", encryptId, e.getMessage(), e);
            return 0;
        }
    }

    /** 该岗位最近一次跟进时间，没有记录返回 null。 */
    public LocalDateTime lastFollowupAt(String encryptId) {
        String sql = "SELECT last_followup_at FROM contact_followup WHERE account = ? AND encrypt_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account);
            statement.setString(2, encryptId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? PersistenceSupport.parseDateTime(resultSet.getString("last_followup_at"))
                        : null;
            }
        } catch (SQLException e) {
            log.error("读取最近跟进时间失败 | encryptId={}：{}", encryptId, e.getMessage(), e);
            return null;
        }
    }

    /** 记录一次跟进：跟进次数 +1，并刷新最近跟进时间。 */
    public void recordFollowup(String encryptId, String encryptUserId, String jobName) {
        if (PersistenceSupport.trimToNull(encryptId) == null) {
            log.warn("recordFollowup 跳过：encryptId 为空");
            return;
        }
        String now = PersistenceSupport.now();
        String sql = "INSERT INTO contact_followup" +
                "(account, encrypt_id, encrypt_user_id, job_name, followup_count, last_followup_at, " +
                "manual_takeover, updated_at) VALUES(?, ?, ?, ?, 1, ?, 0, ?) " +
                "ON CONFLICT(account, encrypt_id) DO UPDATE SET " +
                "followup_count = contact_followup.followup_count + 1, " +
                "last_followup_at = excluded.last_followup_at, " +
                "updated_at = excluded.updated_at, " +
                "encrypt_user_id = COALESCE(excluded.encrypt_user_id, contact_followup.encrypt_user_id), " +
                "job_name = COALESCE(excluded.job_name, contact_followup.job_name)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account);
            statement.setString(2, encryptId);
            statement.setString(3, PersistenceSupport.trimToNull(encryptUserId));
            statement.setString(4, PersistenceSupport.trimToNull(jobName));
            statement.setString(5, now);
            statement.setString(6, now);
            statement.executeUpdate();
            log.info("已记录跟进 | encryptId={} | job={} | 时间={}", encryptId, jobName, now);
        } catch (SQLException e) {
            log.error("记录跟进失败 | encryptId={}：{}", encryptId, e.getMessage(), e);
        }
    }

    /** 今日投递台账汇总（按配置时区的今日 00:00 起统计）。 */
    public Map<String, Object> todaySummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        String todayStart = PersistenceSupport.todayStart(zone);
        summary.put("date", PersistenceSupport.today(zone));
        summary.put("account", account);
        summary.put("todayStart", todayStart);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(TODAY_SUMMARY_SQL)) {
            statement.setString(1, account);
            statement.setString(2, todayStart);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    summary.put("attempts", resultSet.getInt(1));
                    summary.put("textPending", resultSet.getInt(2));
                    summary.put("textConfirmed", resultSet.getInt(3));
                    summary.put("textFailed", resultSet.getInt(4));
                    summary.put("textUnknown", resultSet.getInt(5));
                    summary.put("imageConfirmed", resultSet.getInt(6));
                    summary.put("imageFailed", resultSet.getInt(7));
                    summary.put("imageUnknown", resultSet.getInt(8));
                    summary.put("firstContactConfirmed", resultSet.getInt(9));
                    summary.put("followupConfirmed", resultSet.getInt(10));
                }
            }
        } catch (SQLException e) {
            log.error("读取今日投递台账汇总失败：{}", e.getMessage(), e);
            summary.put("error", "投递台账读取失败：" + e.getMessage());
        }
        summary.putIfAbsent("attempts", 0);
        return summary;
    }

    // ==================== 结算内部实现 ====================

    private void appendTextEvent(Connection connection, AttemptSnapshot attempt, TextSendStatus textStatus,
                                 SettlementRequest request, String now) throws SQLException {
        String eventType = textEvent(textStatus);
        String detail = request.textDetail();
        String evidenceLevel = request.textEvidenceLevel();
        insertEvent(connection, attempt.attemptId(), attempt.runId(), eventType, evidenceLevel, detail, now);
    }

    private void appendImageEvent(Connection connection, AttemptSnapshot attempt, SettlementRequest request,
                                  String now) throws SQLException {
        String eventType = imageEvent(request.imageStatus());
        // 图片流程在发送阶段可能已经写过 IMAGE_SKIPPED / IMAGE_UNKNOWN 事件，这里避免重复记录
        if (eventAlreadyRecorded(connection, attempt.attemptId(), eventType)) {
            return;
        }
        insertEvent(connection, attempt.attemptId(), attempt.runId(), eventType,
                request.imageEvidenceLevel(), request.imageDetail(), now);
    }

    private boolean eventAlreadyRecorded(Connection connection, String attemptId, String eventType)
            throws SQLException {
        if (attemptId == null) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM delivery_event WHERE attempt_id = ? AND event_type = ?")) {
            statement.setString(1, attemptId);
            statement.setString(2, eventType);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    /**
     * 预算预留状态转移 + daily_budget 聚合更新（条件更新：同一 reservationId 只会生效一次）。
     *
     * @return false 表示结算失败，调用方必须抛异常回滚整个事务
     */
    private boolean applyBudgetSettlement(Connection connection, AttemptSnapshot attempt,
                                          SettlementRequest request, String now) throws SQLException {
        BudgetSettlement action = request.budgetAction();
        if (action == null || action == BudgetSettlement.NONE) {
            return true;
        }
        String reservationId = PersistenceSupport.trimToNull(request.reservationId());
        if (reservationId == null) {
            log.error("预算动作 {} 缺少 reservationId，无法结算 | attemptId={}", action, attempt.attemptId());
            return false;
        }
        ReservationSettlement.ReservationRow reservation =
                ReservationSettlement.find(connection, reservationId);
        if (reservation == null) {
            log.error("预算预留不存在，无法结算 | reservationId={} | attemptId={}",
                    reservationId, attempt.attemptId());
            return false;
        }
        String targetState = switch (action) {
            case CONFIRM -> ReservationSettlement.STATE_CONFIRMED;
            case UNKNOWN -> ReservationSettlement.STATE_UNKNOWN;
            case RELEASE -> ReservationSettlement.STATE_RELEASED;
            case NONE -> throw new IllegalStateException("NONE 不应走到预算结算");
        };
        int transitioned = ReservationSettlement.transition(connection, reservationId, targetState, now);
        if (transitioned != 1) {
            ReservationSettlement.ReservationRow current =
                    ReservationSettlement.find(connection, reservationId);
            if (current != null && targetState.equals(current.state())) {
                log.info("预算预留已是目标状态，幂等跳过 | reservationId={} | state={}",
                        reservationId, targetState);
                return true;
            }
            log.error("预算预留状态转移失败 | reservationId={} | target={} | current={}",
                    reservationId, targetState, current == null ? null : current.state());
            return false;
        }
        if (!ReservationSettlement.applyAggregate(
                connection, reservation.budgetRowId(), targetState, now)) {
            log.error("daily_budget 聚合更新失败（reserved<=0），回滚本次结算 | reservationId={} | target={}",
                    reservationId, targetState);
            return false;
        }
        String eventType = switch (action) {
            case CONFIRM -> "BUDGET_CONFIRMED";
            case UNKNOWN -> "BUDGET_UNCERTAIN";
            case RELEASE -> "BUDGET_RELEASED";
            case NONE -> "BUDGET_NONE";
        };
        insertEvent(connection, attempt.attemptId(), attempt.runId(), eventType, "RESERVATION",
                "预算预留结算：" + action.name() + " | reservationId=" + reservationId, now);
        return true;
    }

    private static String textEvent(TextSendStatus status) {
        return switch (status) {
            case CONFIRMED -> "TEXT_CONFIRMED";
            case FAILED -> "TEXT_FAILED";
            case UNKNOWN -> "TEXT_UNKNOWN";
            case NOT_ATTEMPTED -> "JOB_SKIPPED";
            case PENDING -> "TEXT_SEND_STARTED";
        };
    }

    private static String imageEvent(ImageSendStatus status) {
        return switch (status) {
            case CONFIRMED -> "IMAGE_CONFIRMED";
            case FAILED -> "IMAGE_FAILED";
            case UNKNOWN -> "IMAGE_UNKNOWN";
            case SKIPPED -> "IMAGE_SKIPPED";
            case NOT_ATTEMPTED -> "IMAGE_NOT_ATTEMPTED";
        };
    }

    private AttemptSnapshot findAttempt(Connection connection, long attemptRowId) throws SQLException {
        String sql = "SELECT id, attempt_id, run_id, text_status, finished_at FROM delivery_attempt WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, attemptRowId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new AttemptSnapshot(
                        resultSet.getLong("id"),
                        resultSet.getString("attempt_id"),
                        resultSet.getString("run_id"),
                        resultSet.getString("text_status"),
                        resultSet.getString("finished_at"));
            }
        }
    }

    // ==================== 内部工具 ====================

    private int countConfirmedFirstContact(String suffix, String encryptId) {
        String sql = "SELECT COUNT(*) FROM delivery_attempt WHERE account = ? " +
                "AND kind = 'FIRST_CONTACT' AND text_status = 'CONFIRMED'" + suffix;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account);
            if (encryptId != null) {
                statement.setString(2, encryptId);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error("统计确认首次沟通失败 | encryptId={}：{}", encryptId, e.getMessage(), e);
            return 0;
        }
    }

    private long readGeneratedKey(PreparedStatement statement, Connection connection) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (keys != null && keys.next()) {
                return keys.getLong(1);
            }
        }
        try (Statement fallback = connection.createStatement();
             ResultSet resultSet = fallback.executeQuery("SELECT last_insert_rowid()")) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    private void insertEvent(Connection connection, String attemptId, String runId,
                             String eventType, String evidenceLevel, String detail, String occurredAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_EVENT_SQL)) {
            statement.setString(1, attemptId);
            statement.setString(2, runId);
            statement.setString(3, eventType);
            statement.setString(4, PersistenceSupport.trimToNull(evidenceLevel));
            statement.setString(5, detail);
            statement.setString(6, occurredAt);
            statement.executeUpdate();
        }
    }

    private int executeUpdate(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        }
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("事务回滚失败：{}", e.getMessage());
        }
    }

    private void restoreAutoCommit(Connection connection, boolean originalAutoCommit) {
        try {
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException ignore) {
            // 连接关闭时由连接池处理
        }
    }

    private record AttemptSnapshot(long rowId, String attemptId, String runId,
                                   String textStatus, String finishedAt) {
    }
}
