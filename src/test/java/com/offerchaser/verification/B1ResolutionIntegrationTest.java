package com.offerchaser.verification;

import com.offerchaser.application.persistence.AttemptNotFoundException;
import com.offerchaser.application.persistence.AttemptResolutionService;
import com.offerchaser.application.persistence.AlreadyConfirmedException;
import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DeliveryKind;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.ImageSendStatus;
import com.offerchaser.application.persistence.InvalidResolutionException;
import com.offerchaser.application.persistence.TextSendStatus;
import com.offerchaser.application.persistence.VersionConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import static com.offerchaser.verification.VerificationSupport.budgetService;
import static com.offerchaser.verification.VerificationSupport.ledgerService;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static com.offerchaser.verification.VerificationSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 数据/核对回归：人工核对 {@link AttemptResolutionService} 的真实 SQLite 集成验收。
 * <p>
 * 覆盖契约第 2.2 节语义：版本保护（409 VERSION_CONFLICT）、已确认不可回退（409 ALREADY_CONFIRMED）、
 * 幂等键重放、SENT/NOT_SENT 的预算补偿、STILL_UNKNOWN 只记录不改状态、manual_action 审计行。
 */
class B1ResolutionIntegrationTest {

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private BudgetService budget;
    private DeliveryLedgerService ledger;
    private AttemptResolutionService resolutionService;

    @BeforeEach
    void setUp() {
        dataSource = migratedDataSource(tempDir);
        budget = budgetService(dataSource, true, 10);
        ledger = ledgerService(dataSource);
        resolutionService = new AttemptResolutionService(dataSource);
    }

    @Test
    void resolveSentConfirmsAttemptUpdatesBudgetAndWritesAudit() {
        AttemptFixture fixture = unknownAttempt("JOB-1");
        assertEquals(1, firstContactBudget("uncertain"), "前置：预留已按 UNKNOWN 保守占额");

        AttemptResolutionService.ResolutionResult result = resolutionService.resolve(
                fixture.attemptId, "SENT", "电话确认已发送", 0, "key-sent-1");

        assertEquals(fixture.attemptId, result.attemptId());
        assertEquals("CONFIRMED", result.textStatus());
        assertEquals("NOT_ATTEMPTED", result.imageStatus());
        assertEquals(1, result.version(), "一次成功核对 version+1");
        assertEquals("SENT", result.manualResolution());
        assertNotNull(result.resolvedAt());
        assertTrue(result.changed());
        assertFalse(result.idempotent());

        assertEquals("CONFIRMED", attemptTextStatus(fixture.attemptId));
        assertEquals(1, attemptVersion(fixture.attemptId));
        assertEquals("CONFIRMED", reservationState(fixture.reservationId));
        assertEquals(0, firstContactBudget("uncertain"), "UNKNOWN→CONFIRMED 应释放 uncertain");
        assertEquals(1, firstContactBudget("consumed"), "UNKNOWN→CONFIRMED 应增加 consumed");
        assertEquals(0, firstContactBudget("reserved"));

        Map<String, Object> audit = manualAction(fixture.attemptId);
        assertEquals("SENT", audit.get("action"));
        assertEquals("UNKNOWN", audit.get("before_state"));
        assertEquals("CONFIRMED", audit.get("after_state"));
        assertEquals("电话确认已发送", audit.get("reason"));
        assertEquals("key-sent-1", audit.get("idempotency_key"));
    }

    @Test
    void duplicateIdempotencyKeyReplaysFirstResultWithoutChangingAnything() {
        AttemptFixture fixture = unknownAttempt("JOB-2");
        AttemptResolutionService.ResolutionResult first =
                resolutionService.resolve(fixture.attemptId, "SENT", "已确认", 0, "key-replay");
        int auditCount = scalar(dataSource,
                "SELECT COUNT(*) FROM manual_action WHERE object_id = ?", fixture.attemptId);

        AttemptResolutionService.ResolutionResult replay =
                resolutionService.resolve(fixture.attemptId, "SENT", "重复提交（不同理由）", 99, "key-replay");

        assertFalse(replay.changed(), "幂等重放 changed=false");
        assertTrue(replay.idempotent(), "幂等重放 idempotent=true");
        assertEquals(first.version(), replay.version(), "幂等重放不得增加版本");
        assertEquals(first.textStatus(), replay.textStatus());
        assertEquals(auditCount, scalar(dataSource,
                        "SELECT COUNT(*) FROM manual_action WHERE object_id = ?", fixture.attemptId),
                "幂等重放不得新增审计行");
        assertEquals(1, firstContactBudget("consumed"), "幂等重放不得重复调整预算");
        assertEquals(0, firstContactBudget("uncertain"));
    }

    @Test
    void versionConflictCarriesCurrentFacts() {
        AttemptFixture fixture = unknownAttempt("JOB-3");

        VersionConflictException conflict = assertThrows(VersionConflictException.class,
                () -> resolutionService.resolve(fixture.attemptId, "SENT", "版本过期", 5, null));

        assertEquals(fixture.attemptId, conflict.getAttemptId());
        assertEquals(0, conflict.getCurrentVersion());
        assertEquals("UNKNOWN", conflict.getCurrentStatus());
        assertEquals("UNKNOWN", attemptTextStatus(fixture.attemptId), "冲突不得修改状态");
    }

    @Test
    void confirmedAttemptCannotBeRolledBackButSentIsIdempotent() {
        AttemptFixture fixture = unknownAttempt("JOB-4");
        resolutionService.resolve(fixture.attemptId, "SENT", "确认发送", 0, null);

        AlreadyConfirmedException blocked = assertThrows(AlreadyConfirmedException.class,
                () -> resolutionService.resolve(fixture.attemptId, "NOT_SENT", "误标未发", 1, null));
        assertEquals(fixture.attemptId, blocked.getAttemptId());
        assertEquals("CONFIRMED", attemptTextStatus(fixture.attemptId), "已确认记录不得被回退");

        AttemptResolutionService.ResolutionResult again =
                resolutionService.resolve(fixture.attemptId, "SENT", "重复确认", 1, null);
        assertFalse(again.changed());
        assertTrue(again.idempotent(), "已 CONFIRMED 再提交 SENT 是幂等成功");
        assertEquals(1, again.version(), "幂等成功不改变版本");
    }

    @Test
    void resolveNotSentMarksFailedAndReleasesUncertainBudget() {
        AttemptFixture fixture = unknownAttempt("JOB-5");
        assertEquals(1, firstContactBudget("uncertain"));

        AttemptResolutionService.ResolutionResult result =
                resolutionService.resolve(fixture.attemptId, "NOT_SENT", "核对确认未发送", 0, null);

        assertEquals("FAILED", result.textStatus());
        assertEquals(1, result.version());
        assertEquals("FAILED", attemptTextStatus(fixture.attemptId));
        assertEquals("RELEASED", reservationState(fixture.reservationId));
        assertEquals(0, firstContactBudget("uncertain"), "NOT_SENT 应释放 uncertain");
        assertEquals(0, firstContactBudget("consumed"));
        Map<String, Object> audit = manualAction(fixture.attemptId);
        assertEquals("NOT_SENT", audit.get("action"));
        assertEquals("FAILED", audit.get("after_state"));
    }

    @Test
    void resolveStillUnknownOnlyRecordsAndKeepsStatusBudget() {
        AttemptFixture fixture = unknownAttempt("JOB-6");

        AttemptResolutionService.ResolutionResult result =
                resolutionService.resolve(fixture.attemptId, "STILL_UNKNOWN", "已核对但仍无法确认", 0, null);

        assertEquals("UNKNOWN", result.textStatus(), "STILL_UNKNOWN 不改 text_status");
        assertEquals(1, result.version(), "STILL_UNKNOWN 也要 version+1");
        assertEquals("STILL_UNKNOWN", result.manualResolution());
        assertEquals("UNKNOWN", attemptTextStatus(fixture.attemptId));
        assertEquals(1, firstContactBudget("uncertain"), "STILL_UNKNOWN 不动预算");
        assertEquals(0, firstContactBudget("consumed"));
        assertEquals("UNKNOWN", reservationState(fixture.reservationId));
    }

    @Test
    void invalidRequestsAreRejectedWithoutSideEffects() {
        AttemptFixture fixture = unknownAttempt("JOB-7");

        assertThrows(InvalidResolutionException.class, () -> resolutionService.resolve(
                fixture.attemptId, "DONE", "非法枚举", 0, null));
        assertThrows(InvalidResolutionException.class, () -> resolutionService.resolve(
                fixture.attemptId, "SENT", "   ", 0, null));
        assertThrows(InvalidResolutionException.class, () -> resolutionService.resolve(
                fixture.attemptId, "SENT", "x".repeat(501), 0, null));
        assertThrows(InvalidResolutionException.class, () -> resolutionService.resolve(
                fixture.attemptId, "SENT", "负数版本", -1, null));
        assertThrows(AttemptNotFoundException.class, () -> resolutionService.resolve(
                "NOT-EXISTS", "SENT", "不存在", 0, null));

        assertEquals("UNKNOWN", attemptTextStatus(fixture.attemptId), "参数非法不得修改记录");
        assertEquals(0, attemptVersion(fixture.attemptId));
        assertEquals(0, scalar(dataSource,
                "SELECT COUNT(*) FROM manual_action WHERE object_id = ?", fixture.attemptId));
        assertEquals(1, firstContactBudget("uncertain"));
    }

    // ==================== 辅助：构造一条 text_status=UNKNOWN 且预算 UNKNOWN 的尝试 ====================

    private AttemptFixture unknownAttempt(String jobName) {
        String encryptId = "ENC-" + jobName;
        BudgetService.Reservation reservation =
                budget.reserveFirstContact("run-b1", encryptId, jobName);
        assertTrue(reservation.granted(), "前置预算预留应成功");
        DeliveryLedgerService.AttemptStarted attempt = ledger.beginAttempt(
                "run-b1", DeliveryKind.FIRST_CONTACT, encryptId, "USER-" + jobName,
                jobName, "示例公司", "https://example.invalid/job/" + jobName,
                "snap-" + jobName, "msg-" + jobName, null, reservation.reservationId());
        boolean settled = ledger.settleAttempt(new DeliveryLedgerService.SettlementRequest(
                attempt.rowId(), TextSendStatus.UNKNOWN, "DOM", "确认超时，结果未知",
                "TEXT_SEND_UNCONFIRMED",
                ImageSendStatus.NOT_ATTEMPTED, null, null,
                reservation.reservationId(), DeliveryLedgerService.BudgetSettlement.UNKNOWN,
                "TEXT_SEND_UNCONFIRMED", "确认超时，结果未知"));
        assertTrue(settled, "前置结算 UNKNOWN 应成功");
        return new AttemptFixture(attempt.attemptId(), attempt.rowId(), reservation.reservationId());
    }

    private String attemptTextStatus(String attemptId) {
        return scalarString(dataSource,
                "SELECT text_status FROM delivery_attempt WHERE attempt_id = ?", attemptId);
    }

    private int attemptVersion(String attemptId) {
        return scalar(dataSource,
                "SELECT version FROM delivery_attempt WHERE attempt_id = ?", attemptId);
    }

    private String reservationState(String reservationId) {
        return scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", reservationId);
    }

    @SuppressWarnings("unchecked")
    private int firstContactBudget(String key) {
        Map<String, Object> firstContact =
                (Map<String, Object>) budget.todaySummary().get("firstContact");
        return (Integer) Objects.requireNonNull(firstContact.get(key));
    }

    private Map<String, Object> manualAction(String attemptId) {
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT action, before_state, after_state, reason, idempotency_key " +
                             "FROM manual_action WHERE object_id = ? ORDER BY id DESC LIMIT 1")) {
            statement.setString(1, attemptId);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "应存在 manual_action 审计行");
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("action", resultSet.getString("action"));
                row.put("before_state", resultSet.getString("before_state"));
                row.put("after_state", resultSet.getString("after_state"));
                row.put("reason", resultSet.getString("reason"));
                row.put("idempotency_key", resultSet.getString("idempotency_key"));
                return row;
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("读取 manual_action 失败", e);
        }
    }

    private record AttemptFixture(String attemptId, long rowId, String reservationId) {
    }
}
