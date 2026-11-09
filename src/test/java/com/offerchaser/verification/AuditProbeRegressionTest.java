package com.offerchaser.verification;

import com.offerchaser.application.persistence.BudgetService;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import static com.offerchaser.verification.VerificationSupport.budgetService;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static com.offerchaser.verification.VerificationSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 复刻《阶段A独立验收报告》里的 4 个失败 probe 并验证全部转绿（A-01/A-02/A-05）。
 * <p>
 * 3 个聊天确认/身份 probe 通过反射调用 package-private 的 {@code ChatSendConfirmer}（只观测 public 契约行为）；
 * 1 个预算 probe 直接用真实 SQLite + {@link BudgetService} 的新逐笔预留 API。
 * 这些用例与实现方自己的测试互相独立，防止“测试跟着实现一起改”。
 */
class AuditProbeRegressionTest {

    @TempDir
    Path tempDir;

    // ==================== A-02 probe 1：基线缺失时不得假成功 ====================

    @Test
    void missingBaselineProbeMustNotConfirmLoadedHistoryAsNewMessage() throws Exception {
        Frame frame = mock(Frame.class);
        // 与原报告 probe 相同：第一次基线读不到（rootFound=false），随后历史消息才“加载”出来
        when(frame.evaluate(anyString())).thenReturn(
                "{\"rootFound\":false}",
                "{\"rootFound\":true,\"ownCount\":1,\"textCount\":1,\"texts\":[\"hello\"],\"tail\":"
                        + "[{\"text\":\"hello\",\"hasImage\":false,\"sending\":false,\"failed\":false}]}");
        Page page = mockPage(frame);

        Object result = clickAndConfirmText(newConfirmer(), page, "hello", 1);
        assertFalse(confirmed(result),
                "基线不可用时，迟到历史消息不能证明本次发送成功");
        assertTrue(uncertain(result), "证据不足应返回不确定（不记成功）");
    }

    // ==================== A-02 probe 2：匹配消息自身仍在发送中 ====================

    @Test
    void matchedStillSendingProbeMustNotBeConfirmedByUnrelatedLastMessage() throws Exception {
        Frame frame = mock(Frame.class);
        when(frame.evaluate(anyString())).thenReturn(
                "{\"rootFound\":true,\"ownCount\":0,\"textCount\":0,\"texts\":[],\"tail\":[]}",
                "{\"rootFound\":true,\"ownCount\":2,\"textCount\":2,\"lastSending\":false,"
                        + "\"texts\":[\"hello\",\"other\"],\"tail\":["
                        + "{\"text\":\"hello\",\"hasImage\":false,\"sending\":true,\"failed\":false},"
                        + "{\"text\":\"other\",\"hasImage\":false,\"sending\":false,\"failed\":false}]}");
        Page page = mockPage(frame);

        Object result = clickAndConfirmText(newConfirmer(), page, "hello", 1);
        assertFalse(confirmed(result),
                "需要核对匹配消息自身的 sending，而不是另一条最后消息");
        assertTrue(uncertain(result));
        assertTrue(clickAttempted(result), "点击已发生，调用方不得按“未发送”释放预算");
    }

    // ==================== A-01 probe 3：通用岗位名不能覆盖联系人冲突 ====================

    @Test
    void genericJobTitleProbeMustNotOverrideConflictingRecruiterIdentity() throws Exception {
        Frame frame = mock(Frame.class);
        when(frame.evaluate(anyString())).thenReturn(
                "{\"rootFound\":true,\"titleFound\":true,\"title\":\"另一公司 李女士 Java工程师\"}");
        Page page = mockPage(frame);
        Object confirmer = newConfirmer();

        Object result = verifyChatIdentity(confirmer, page, "目标公司", "Java工程师", "王先生", null, true);

        assertNotEquals("VERIFIED", identityOutcome(result),
                "同名岗位不是联系人唯一身份，不能覆盖公司及 HR 冲突");
        assertEquals("MISMATCH", identityOutcome(result),
                "标题出现其他联系人形态时必须是 MISMATCH（fail-closed）");
    }

    /** 同公司不同 HR：即使公司与岗位都命中，HR 冲突也不得 VERIFIED。 */
    @Test
    void sameCompanyDifferentHrMustNotBeVerified() throws Exception {
        Frame frame = mock(Frame.class);
        when(frame.evaluate(anyString())).thenReturn(
                "{\"rootFound\":true,\"titleFound\":true,\"title\":\"目标公司 李女士 Java工程师\"}");
        Page page = mockPage(frame);

        Object result = verifyChatIdentity(newConfirmer(), page, "目标公司", "Java工程师", "王先生", null, true);

        assertNotEquals("VERIFIED", identityOutcome(result),
                "同公司不同 HR 不得 VERIFIED（A+ task-10 裁决）");
        assertEquals("MISMATCH", identityOutcome(result));
    }

    /** 正向控制：只有 HR 为空时，公司+岗位同时命中才允许 VERIFIED。 */
    @Test
    void companyAndJobCanOnlyVerifyWhenHrIsBlank() throws Exception {
        Frame frame = mock(Frame.class);
        when(frame.evaluate(anyString())).thenReturn(
                "{\"rootFound\":true,\"titleFound\":true,\"title\":\"目标公司 Java工程师\"}");
        Page page = mockPage(frame);

        Object withoutHr = verifyChatIdentity(newConfirmer(), page, "目标公司", "Java工程师", null, null, true);
        assertEquals("VERIFIED", identityOutcome(withoutHr), "HR 为空时公司+岗位可作为中等证据");

        Object withHr = verifyChatIdentity(newConfirmer(), page, "目标公司", "Java工程师", "王先生", null, true);
        assertNotEquals("VERIFIED", identityOutcome(withHr), "HR 已提供但未命中不得 VERIFIED");
    }

    /** 标题缺失（只有 document.title 风格信息）必须 UNKNOWN，不允许把无证据当通过。 */
    @Test
    void missingChatTitleMustBeUnknown() throws Exception {
        Frame frame = mock(Frame.class);
        when(frame.evaluate(anyString())).thenReturn(
                "{\"rootFound\":true,\"titleFound\":false,\"title\":\"\"}");
        Page page = mockPage(frame);

        Object result = verifyChatIdentity(newConfirmer(), page, "目标公司", "Java工程师", "王先生", null, true);
        assertEquals("UNKNOWN", identityOutcome(result));
    }

    // ==================== A-05 probe 4：重复 release 不得影响其他预留 ====================

    @Test
    void retryingReleaseOfOneReservationMustNotReleaseAnotherReservation() {
        SQLiteDataSource dataSource = migratedDataSource(tempDir);
        BudgetService budget = budgetService(dataSource, true, 2);

        BudgetService.Reservation first = budget.reserveFirstContact("run-a", "job-a", "A");
        BudgetService.Reservation second = budget.reserveFirstContact("run-b", "job-b", "B");
        assertTrue(first.granted() && second.granted());
        assertEquals(2, scalar(dataSource, "SELECT reserved FROM daily_budget"),
                "两笔预留后聚合 reserved 应为 2");

        assertTrue(budget.release(first.reservationId(), "A取消"), "第一次释放应成功");
        assertFalse(budget.release(first.reservationId(), "A取消请求重试"),
                "同一 reservationId 重复释放必须幂等 no-op（返回 false）");

        assertEquals(1, scalar(dataSource, "SELECT reserved FROM daily_budget"),
                "同一预留重复释放，不能把 B 的预留也释放掉（验收报告 probe）");
        assertEquals(0, scalar(dataSource, "SELECT consumed FROM daily_budget"));
        assertEquals(0, scalar(dataSource, "SELECT uncertain FROM daily_budget"));
        assertEquals("RELEASED", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", first.reservationId()));
        assertEquals("RESERVED", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", second.reservationId()),
                "另一笔预留必须仍然 RESERVED");

        // 释放 B 后才归零，且 A 的状态不会被覆盖
        assertTrue(budget.release(second.reservationId(), "B取消"));
        assertEquals(0, scalar(dataSource, "SELECT reserved FROM daily_budget"));
        assertEquals("RELEASED", scalarString(dataSource,
                "SELECT state FROM budget_reservation WHERE reservation_id = ?", first.reservationId()));
    }

    // ==================== 反射工具 ====================

    private static Page mockPage(Frame frame) {
        Page page = mock(Page.class);
        when(page.frames()).thenReturn(List.of(frame));
        when(page.isClosed()).thenReturn(false);
        return page;
    }

    private static Object newConfirmer() throws Exception {
        Class<?> clazz = Class.forName("com.offerchaser.worker.boss.ChatSendConfirmer");
        Constructor<?> constructor = clazz.getDeclaredConstructor(LongConsumer.class);
        constructor.setAccessible(true);
        return constructor.newInstance((LongConsumer) seconds -> {
            // 测试注入空休眠，避免真实等待；轮询逻辑仍是生产实现
        });
    }

    private static Object clickAndConfirmText(Object confirmer, Page page, String expected, int timeoutSeconds)
            throws Exception {
        Locator sendButton = mock(Locator.class);
        Method method = confirmer.getClass().getDeclaredMethod("clickAndConfirmText",
                Page.class, Locator.class, String.class, int.class, Supplier.class);
        method.setAccessible(true);
        return method.invoke(confirmer, page, sendButton, expected, timeoutSeconds,
                (Supplier<Boolean>) () -> false);
    }

    private static Object verifyChatIdentity(Object confirmer, Page page, String company, String job,
                                             String hr, String expectedChatId, boolean strict) throws Exception {
        Method method = confirmer.getClass().getDeclaredMethod("verifyChatIdentity",
                Page.class, String.class, String.class, String.class, String.class, boolean.class);
        method.setAccessible(true);
        return method.invoke(confirmer, page, company, job, hr, expectedChatId, strict);
    }

    private static boolean confirmed(Object textResult) throws Exception {
        return (Boolean) resultAccessor(textResult, "confirmed");
    }

    private static boolean uncertain(Object textResult) throws Exception {
        return (Boolean) resultAccessor(textResult, "uncertain");
    }

    private static boolean clickAttempted(Object textResult) throws Exception {
        return (Boolean) resultAccessor(textResult, "clickAttempted");
    }

    private static String identityOutcome(Object identityResult) throws Exception {
        Object outcome = resultAccessor(identityResult, "outcome");
        assertTrue(outcome instanceof Enum<?>, "IdentityResult.outcome 应是枚举");
        return ((Enum<?>) outcome).name();
    }

    private static Object resultAccessor(Object result, String accessor) throws Exception {
        Method method = result.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(result);
    }
}
