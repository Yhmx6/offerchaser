package com.offerchaser.verification;

import com.offerchaser.application.ai.AiMatchService;
import com.offerchaser.application.contacts.ContactStateService;
import com.offerchaser.application.contacts.FollowupService;
import com.offerchaser.application.delivery.JobSnapshot;
import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DeliveryKind;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.EvaluationRepository;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.BossResumeImageService;
import com.offerchaser.application.service.BossService;
import com.offerchaser.worker.boss.Boss;
import com.offerchaser.worker.boss.BossRunContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import static com.offerchaser.verification.VerificationSupport.budgetService;
import static com.offerchaser.verification.VerificationSupport.ledgerService;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static com.offerchaser.verification.VerificationSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 针对阶段 A 独立验收报告缺陷与 Lead 修复的回归测试（A+ 版，A-03/A-05）。
 * <p>
 * 覆盖：
 * <ol>
 *   <li>「未找到聊天按钮」的 attempt 必须落 NOT_ATTEMPTED 并释放预算，下一轮 reconcile 不得二次占额；</li>
 *   <li>真实崩溃遗留的 PENDING 必须被回收为 UNKNOWN 并保守占额（不能因为修复 1 而永不回收）；</li>
 *   <li>A-03：点击过沟通入口但未确认的 attempt，结算时必须升级为 UNKNOWN，不能释放预算。</li>
 * </ol>
 * 使用真实 SQLite + 真实 BudgetService/DeliveryLedgerService，通过反射调用 Boss 私有结算方法，
 * 用 {@code Boss$DeliveryDraft} 精确复刻各分支的中间状态。
 */
class ReconcileRegressionTest {

    private static final String RUN_ID = "run-regression";

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private BudgetService budgetService;
    private DeliveryLedgerService ledgerService;
    private Boss boss;

    @BeforeEach
    void setUp() {
        dataSource = migratedDataSource(tempDir);
        budgetService = budgetService(dataSource, true, 5);
        ledgerService = ledgerService(dataSource);
        boss = new Boss(
                mock(BossService.class),
                mock(AiService.class),
                mock(AiMatchService.class),
                mock(BossResumeImageService.class),
                budgetService,
                ledgerService,
                mock(EvaluationRepository.class),
                mock(ContactStateService.class),
                mock(FollowupService.class));
        boss.setRunContext(new BossRunContext(RUN_ID));
    }

    @Test
    void notAttemptedChatButtonMissingIsSettledAndReconcileLeavesBudgetUntouched() throws Exception {
        BudgetService.Reservation reservation =
                budgetService.reserveFirstContact(RUN_ID, "JOB-1", "Java 开发");
        assertTrue(reservation.granted());
        assertNotNull(reservation.reservationId());
        assertEquals(1, firstContactBudget("reserved"), "预留成功后应有 1 个 reserved");

        DeliveryLedgerService.AttemptStarted attempt = ledgerService.beginAttempt(
                RUN_ID, DeliveryKind.FIRST_CONTACT, "JOB-1", "USER-1",
                "Java 开发", "示例科技", "https://www.zhipin.com/job_detail/1.html",
                "snapshot-hash", "message-hash", null, reservation.reservationId());
        assertEquals("PENDING", attemptStatus(attempt.rowId()), "beginAttempt 后应为 PENDING");

        // 复刻 deliverJobInternal 的「未找到可用的沟通按钮」分支：
        // textStatus 保持 NOT_ATTEMPTED、没有点击过沟通入口、budgetAction=RELEASE。
        Object draft = newDraft();
        setField(draft, "detail", "未找到可用的沟通按钮");
        setField(draft, "errorCode", "CHAT_BUTTON_NOT_FOUND");
        invokeSettleAttempt(draft, attempt.rowId(), reservation.reservationId());

        // 1) 未发送的 attempt 必须落终态 NOT_ATTEMPTED，而不是留在 PENDING
        assertEquals("NOT_ATTEMPTED", attemptStatus(attempt.rowId()),
                "未发起发送的 attempt 必须写入 NOT_ATTEMPTED；若仍是 PENDING 说明缺陷回归");
        assertEquals("CHAT_BUTTON_NOT_FOUND", scalarString(dataSource,
                "SELECT error_code FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertNotNull(attemptFinishedAt(attempt.rowId()), "settleAttempt 应写入 finished_at");
        assertEquals("RELEASED", reservationState(reservation.reservationId()));

        // 2) 预算预留已被释放，没有被转成不确定占用
        assertEquals(0, firstContactBudget("reserved"));
        assertEquals(0, firstContactBudget("uncertain"));
        assertEquals(0, firstContactBudget("consumed"));

        // 3) 模拟重启：reconcileStaleReservations 不应再碰这条已结算记录
        BudgetService afterRestart = budgetService(dataSource, true, 5);
        int processed = afterRestart.reconcileStaleReservations("run-new");
        assertEquals(0, processed,
                "NOT_ATTEMPTED 记录不应被当成崩溃遗留回收（回归点：跳过的岗位被二次占用额度）");
        assertEquals(0, firstContactBudget("uncertain"), "跳过的岗位不得占用结果未知额度");
        assertEquals(0, firstContactBudget("reserved"), "跳过的岗位不得残留预留");
    }

    @Test
    void genuinelyPendingAttemptFromCrashIsReconciledAsUncertain() {
        BudgetService.Reservation reservation =
                budgetService.reserveFirstContact(RUN_ID, "JOB-2", "后端开发");
        assertTrue(reservation.granted());
        DeliveryLedgerService.AttemptStarted attempt = ledgerService.beginAttempt(
                RUN_ID, DeliveryKind.FIRST_CONTACT, "JOB-2", "USER-2",
                "后端开发", "示例科技", "https://www.zhipin.com/job_detail/2.html",
                "snapshot-hash", "message-hash", null, reservation.reservationId());
        // 模拟进程在此崩溃：attempt 停在 PENDING，预留停在 RESERVED，没有任何结算

        BudgetService afterRestart = budgetService(dataSource, true, 5);
        int processed = afterRestart.reconcileStaleReservations("run-new");
        assertEquals(1, processed, "真正的崩溃遗留 PENDING 必须被回收");
        assertEquals("UNKNOWN", attemptStatus(attempt.rowId()));
        assertEquals("CRASH_RECOVERY", scalarString(dataSource,
                "SELECT error_code FROM delivery_attempt WHERE id = ?", attempt.rowId()));
        assertEquals("UNKNOWN", reservationState(reservation.reservationId()));
        assertEquals(1, firstContactBudget("uncertain"), "崩溃遗留按结果未知保守占额");
        assertEquals(0, firstContactBudget("reserved"), "遗留预留应从 reserved 转出");
        assertEquals(0, afterRestart.reconcileStaleReservations("run-newer"),
                "回收必须幂等，不能重复占额");
    }

    @Test
    void clickedChatEntryWithoutConfirmationMustNotReleaseBudget() throws Exception {
        BudgetService.Reservation reservation =
                budgetService.reserveFirstContact(RUN_ID, "JOB-3", "Java 开发");
        assertTrue(reservation.granted());
        DeliveryLedgerService.AttemptStarted attempt = ledgerService.beginAttempt(
                RUN_ID, DeliveryKind.FIRST_CONTACT, "JOB-3", "USER-3",
                "Java 开发", "示例科技", "https://www.zhipin.com/job_detail/3.html",
                "snapshot-hash", "message-hash", null, reservation.reservationId());

        // A-03：已经点击过沟通入口（clicked unknown / 找不到发送按钮），但文字未发送。
        // 调用方设置 RELEASE，settleAttempt 必须升级为 UNKNOWN（已产生外部动作）。
        Object draft = newDraft();
        setField(draft, "chatRequested", true);
        setField(draft, "detail", "沟通入口已点击但聊天页未确认");
        setField(draft, "errorCode", "CHAT_CLICK_UNKNOWN");
        invokeSettleAttempt(draft, attempt.rowId(), reservation.reservationId());

        assertEquals("UNKNOWN", attemptStatus(attempt.rowId()),
                "点击过沟通入口后未发送，文字状态应为 UNKNOWN（不是 NOT_ATTEMPTED/成功）");
        assertEquals("UNKNOWN", reservationState(reservation.reservationId()),
                "已点击沟通入口不得释放预算，必须保守占用额度");
        assertEquals(1, firstContactBudget("uncertain"));
        assertEquals(0, firstContactBudget("reserved"));
        assertEquals(0, firstContactBudget("consumed"), "未确认发送不能记 consumed");
        assertEquals(1, scalar(dataSource, "SELECT COUNT(*) FROM delivery_event " +
                        "WHERE attempt_id = ? AND event_type = 'BUDGET_UNCERTAIN'",
                attempt.attemptId()), "预算结算事件应落库");
    }

    // ==================== 反射工具：调用私有 settleAttempt / DeliveryDraft ====================

    private void invokeSettleAttempt(Object draft, long rowId, String reservationId) throws Exception {
        Class<?> draftClass = Class.forName("com.offerchaser.worker.boss.Boss$DeliveryDraft");
        Method settle = Boss.class.getDeclaredMethod("settleAttempt",
                JobSnapshot.class, long.class, String.class, draftClass);
        settle.setAccessible(true);
        JobSnapshot snapshot = JobSnapshot.builder()
                .encryptId("JOB-1")
                .encryptUserId("USER-1")
                .jobName("Java 开发")
                .companyName("示例科技")
                .build();
        settle.invoke(boss, snapshot, rowId, reservationId, draft);
    }

    private static Object newDraft() throws Exception {
        Class<?> draftClass = Class.forName("com.offerchaser.worker.boss.Boss$DeliveryDraft");
        Constructor<?> constructor = draftClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private int firstContactBudget(String key) {
        @SuppressWarnings("unchecked")
        Map<String, Object> firstContact =
                (Map<String, Object>) budgetService.todaySummary().get("firstContact");
        return (Integer) Objects.requireNonNull(firstContact.get(key));
    }

    private String attemptStatus(long rowId) {
        return scalarString(dataSource, "SELECT text_status FROM delivery_attempt WHERE id = ?", rowId);
    }

    private String attemptFinishedAt(long rowId) {
        return scalarString(dataSource, "SELECT finished_at FROM delivery_attempt WHERE id = ?", rowId);
    }

    private String reservationState(String reservationId) {
        return scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", reservationId);
    }
}
