package com.offerchaser.verification;

import com.offerchaser.application.ai.AiMatchService;
import com.offerchaser.application.contacts.ContactStateService;
import com.offerchaser.application.contacts.FollowupService;
import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.EvaluationRepository;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.BossResumeImageService;
import com.offerchaser.application.service.BossService;
import com.offerchaser.worker.boss.Boss;
import com.offerchaser.worker.boss.BossRunContext;
import com.offerchaser.worker.boss.RunCancelledException;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A-06 验收：人工验证暂停/恢复不得忙循环，且停止/强制复位能立即退出。
 * <p>
 * 用 Mockito 的 Page 让 {@code page.url()} 始终返回验证 URL（不打开浏览器），通过反射调用
 * 生产 {@code Boss.waitForSliderVerify}。恢复后若页面仍在验证页，实现必须重新进入 PAUSED 并
 * 至少做 1 秒有界等待——若退化为忙循环，{@code page.url()} 调用次数会爆炸，本测试会失败。
 */
class VerificationBoundedWaitTest {

    private static final String VERIFY_URL = "https://www.zhipin.com/web/passport/zp/verify?seed=a6";

    @Test
    void resumedButStillOnVerifyPageMustWaitBoundedlyInsteadOfBusyLooping() throws Exception {
        Fixture fixture = new Fixture();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Object> waiting = executor.submit(fixture::runWaitForSliderVerify);
            fixture.awaitPaused(3_000L);

            // 模拟管理台点一次“继续任务”，但页面仍然停在验证页
            assertTrue(fixture.context.resumeVerification());
            int callsBefore = fixture.urlCalls.get();
            Thread.sleep(1_300L);
            int callsAfterResume = fixture.urlCalls.get() - callsBefore;

            assertTrue(callsAfterResume < 50,
                    "恢复后仍处于验证页时必须有界等待，不允许忙循环；1.3 秒内 page.url() 调用次数="
                            + callsAfterResume);
            // 验证未完成 → 必须重新进入暂停，而不是停在 RUNNING 空转
            fixture.awaitPaused(2_000L);

            fixture.context.requestStop("测试停止");
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> waiting.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof RunCancelledException,
                    "停止指令必须让验证等待立即以取消异常退出");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void forceResetDuringVerificationPauseExitsImmediately() throws Exception {
        Fixture fixture = new Fixture();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Object> waiting = executor.submit(fixture::runWaitForSliderVerify);
            fixture.awaitPaused(3_000L);

            long startedAt = System.currentTimeMillis();
            fixture.context.abandon("管理员强制复位");
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> waiting.get(3, TimeUnit.SECONDS));
            long elapsed = System.currentTimeMillis() - startedAt;

            assertTrue(failure.getCause() instanceof RunCancelledException,
                    "强制复位后等待人工验证的线程必须立即以取消异常退出，不能继续空转");
            assertTrue(elapsed < 2_500L, "强制复位后旧线程应快速退出，实际耗时 " + elapsed + "ms");
            assertEquals(BossRunContext.State.FAILED, fixture.context.getState());
            assertTrue(fixture.context.isStopRequested(), "A-04：abandon 必须同时置取消标记");
        } finally {
            executor.shutdownNow();
        }
    }

    /** 生产 Boss 实例 + 只 mock Page 的最小夹具。 */
    private static final class Fixture {
        final BossRunContext context = new BossRunContext("run-a6");
        final Page page = mock(Page.class);
        final AtomicInteger urlCalls = new AtomicInteger();
        final Boss boss;

        Fixture() {
            context.markRunning();
            when(page.url()).thenAnswer(invocation -> {
                urlCalls.incrementAndGet();
                return VERIFY_URL;
            });
            boss = new Boss(
                    mock(BossService.class),
                    mock(AiService.class),
                    mock(AiMatchService.class),
                    mock(BossResumeImageService.class),
                    mock(BudgetService.class),
                    mock(DeliveryLedgerService.class),
                    mock(EvaluationRepository.class),
                    mock(ContactStateService.class),
                    mock(FollowupService.class));
            boss.setRunContext(context);
            boss.setProgressCallback((message, current, total) -> {
            });
        }

        Object runWaitForSliderVerify() throws Exception {
            Method method = Boss.class.getDeclaredMethod("waitForSliderVerify", Page.class);
            method.setAccessible(true);
            try {
                return method.invoke(boss, page);
            } catch (InvocationTargetException e) {
                throw e.getCause() instanceof Exception checked
                        ? checked
                        : new IllegalStateException(e.getCause());
            }
        }

        void awaitPaused(long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (context.isVerificationPaused()) {
                    return;
                }
                Thread.sleep(20L);
            }
            throw new AssertionError("等待进入 PAUSED_VERIFICATION 超时");
        }
    }
}
