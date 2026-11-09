package com.offerchaser.verification;

import com.offerchaser.application.ai.AiMatchService;
import com.offerchaser.application.delivery.RuleSet;
import com.offerchaser.application.delivery.SalaryStrategy;
import com.offerchaser.application.persistence.BossQueryRepository;
import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DatabaseMaintenanceService;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.SchemaMigrationService;
import com.offerchaser.application.persistence.TaskRunRepository;
import com.offerchaser.application.rules.RuleConfigService;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.ConfigService;
import com.offerchaser.application.templates.ProfileTemplateService;
import com.offerchaser.worker.boss.Boss;
import com.offerchaser.worker.boss.BossConfig;
import com.offerchaser.worker.dto.JobProgressMessage;
import com.offerchaser.worker.manager.PlaywrightManager;
import com.offerchaser.worker.service.BossJobService;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1 运行侧验收：任务启动时把「生效模板」的采集策略写入本轮 BossConfig；
 * 无模板/无策略回退全局；读取异常不阻止启动；每日首次沟通上限仍为全局共享（不模板化）。
 * 另验证 Boss 的 effectiveCollect* 优先级（模板值 &gt; 全局默认；no-new=0/负 回退全局）。
 */
class TemplateDeliveryPolicyRuntimeTest {

    private PlaywrightManager playwrightManager;
    private ConfigService configService;
    @SuppressWarnings("unchecked")
    private ObjectProvider<Boss> bossProvider;
    private AiService aiService;
    private AiMatchService aiMatchService;
    private BudgetService budgetService;
    private DeliveryLedgerService deliveryLedgerService;
    private TaskRunRepository taskRunRepository;
    private SchemaMigrationService schemaMigrationService;
    private DatabaseMaintenanceService databaseMaintenanceService;
    private BossQueryRepository bossQueryRepository;
    private RuleConfigService ruleConfigService;
    private ProfileTemplateService profileTemplateService;
    private BossJobService service;
    private BossConfig config;

    @BeforeEach
    void setUp() {
        playwrightManager = mock(PlaywrightManager.class);
        configService = mock(ConfigService.class);
        bossProvider = mock(ObjectProvider.class);
        aiService = mock(AiService.class);
        aiMatchService = mock(AiMatchService.class);
        budgetService = mock(BudgetService.class);
        deliveryLedgerService = mock(DeliveryLedgerService.class);
        taskRunRepository = mock(TaskRunRepository.class);
        schemaMigrationService = mock(SchemaMigrationService.class);
        databaseMaintenanceService = mock(DatabaseMaintenanceService.class);
        bossQueryRepository = mock(BossQueryRepository.class);
        ruleConfigService = mock(RuleConfigService.class);
        profileTemplateService = mock(ProfileTemplateService.class);

        config = new BossConfig();
        config.setKeywords(List.of("Java"));
        config.setCityCode(List.of("101020100"));
        config.setSayHi("您好，我有相关经验，希望进一步沟通。");
        config.setDebugger(false);
        config.setEnableAI(false);

        when(schemaMigrationService.isReady()).thenReturn(true);
        when(configService.getBossConfig()).thenReturn(config);
        when(databaseMaintenanceService.backup(anyString())).thenReturn(null);
        when(budgetService.reconcileStaleReservations(anyString())).thenReturn(0);
        when(budgetService.isFirstContactBudgetDepleted()).thenReturn(false);
        when(playwrightManager.getBossPage()).thenReturn(mock(Page.class));
        when(playwrightManager.isLoggedIn("boss")).thenReturn(true);
        when(bossProvider.getObject()).thenReturn(mock(Boss.class));
        doReturn(0).when(playwrightManager).callOnPlaywright(any());
        when(bossQueryRepository.countPendingResolutions()).thenReturn(0);
        when(ruleConfigService.getActiveRuleSet()).thenReturn(
                RuleSet.defaultFrom(config, Set.of(), Set.of(), Set.of(), SalaryStrategy.INTERSECT));
        when(ruleConfigService.currentVersion()).thenReturn(1);
        when(profileTemplateService.ensureActiveApplied()).thenReturn(null);
        when(profileTemplateService.active()).thenReturn(Optional.empty());

        service = new BossJobService(playwrightManager, configService, bossProvider, aiService,
                aiMatchService, budgetService, deliveryLedgerService, taskRunRepository,
                schemaMigrationService, databaseMaintenanceService, bossQueryRepository,
                ruleConfigService, profileTemplateService);
    }

    // ==================== 启动策略覆盖 ====================

    @Test
    void activeTemplatePolicyOverridesRunConfig() {
        when(profileTemplateService.active()).thenReturn(Optional.of(template(
                new ProfileTemplateService.DeliveryPolicy(12, 4, 9))));

        List<JobProgressMessage> messages = new ArrayList<>();
        service.executeDelivery(messages::add);

        assertEquals("COMPLETED", service.getStatus().get("state"));
        assertEquals(12, config.getCollectMaxPerKeyword(), "每关键词候选上限应取生效模板值");
        assertEquals(4, config.getCollectMaxDurationMinutes(), "单关键词采集时长应取生效模板值");
        assertEquals(9, config.getCollectNoNewWindowSeconds(), "无新增窗口应取生效模板值");
        // 每日首次沟通预算仍由全局 BudgetService 控制（模板不参与）
        verify(budgetService).reconcileStaleReservations(anyString());
        verify(budgetService).isFirstContactBudgetDepleted();
        assertEquals(3, ProfileTemplateService.DeliveryPolicy.class.getRecordComponents().length,
                "模板策略只含采集三项：每日首次沟通上限全局共享，不进入模板");
    }

    @Test
    void noActiveTemplateKeepsGlobalDefaults() {
        when(profileTemplateService.active()).thenReturn(Optional.empty());
        service.executeDelivery(messages -> {
        });
        assertEquals("COMPLETED", service.getStatus().get("state"));
        assertNull(config.getCollectMaxPerKeyword(), "无生效模板时不得覆写全局采集配置");
        assertNull(config.getCollectMaxDurationMinutes());
        assertNull(config.getCollectNoNewWindowSeconds());
    }

    @Test
    void activeTemplateWithoutPolicyKeepsGlobalDefaults() {
        when(profileTemplateService.active()).thenReturn(Optional.of(template(null)));
        service.executeDelivery(messages -> {
        });
        assertEquals("COMPLETED", service.getStatus().get("state"));
        assertNull(config.getCollectMaxPerKeyword());
        assertNull(config.getCollectMaxDurationMinutes());
        assertNull(config.getCollectNoNewWindowSeconds());
    }

    @Test
    void partialPolicyOnlyOverridesPresentFields() {
        when(profileTemplateService.active()).thenReturn(Optional.of(template(
                new ProfileTemplateService.DeliveryPolicy(7, null, null))));
        service.executeDelivery(messages -> {
        });
        assertEquals(7, config.getCollectMaxPerKeyword());
        assertNull(config.getCollectMaxDurationMinutes(), "策略未提供时长时保持全局默认（null）");
        assertNull(config.getCollectNoNewWindowSeconds());
    }

    @Test
    void templateReadExceptionDoesNotBlockStartAndKeepsGlobalDefaults() {
        when(profileTemplateService.active()).thenThrow(new IllegalStateException("模板库不可用"));
        List<JobProgressMessage> messages = new ArrayList<>();
        service.executeDelivery(messages::add);
        assertEquals("COMPLETED", service.getStatus().get("state"), "模板读取异常不得阻止任务启动");
        assertNull(config.getCollectMaxPerKeyword(), "异常时保持全局采集配置");
    }

    // ==================== Boss effectiveCollect* ====================

    @Test
    void effectiveCollectPrefersTemplateValuesAndFallsBack() throws Exception {
        Boss boss = allocateBoss();
        set(boss, "collectMaxPerKeyword", 60);
        set(boss, "collectMaxDurationMinutes", 15);
        set(boss, "collectNoNewWindowSeconds", 30);

        // config 为空 → 全部回退全局默认
        set(boss, "config", null);
        assertEquals(60, invokeInt(boss, "effectiveCollectMaxPerKeyword"));
        assertEquals(15, invokeInt(boss, "effectiveCollectMaxDurationMinutes"));
        assertEquals(30, invokeInt(boss, "effectiveCollectNoNewWindowSeconds"));

        // 模板值优先
        BossConfig configWithPolicy = new BossConfig();
        configWithPolicy.setCollectMaxPerKeyword(12);
        configWithPolicy.setCollectMaxDurationMinutes(4);
        configWithPolicy.setCollectNoNewWindowSeconds(9);
        set(boss, "config", configWithPolicy);
        assertEquals(12, invokeInt(boss, "effectiveCollectMaxPerKeyword"));
        assertEquals(4, invokeInt(boss, "effectiveCollectMaxDurationMinutes"));
        assertEquals(9, invokeInt(boss, "effectiveCollectNoNewWindowSeconds"));

        // 字段 null / no-new 0 或负 → 回退全局；perKeyword 0 表示不限制（保留 0）
        BossConfig partial = new BossConfig();
        partial.setCollectMaxPerKeyword(0);
        partial.setCollectMaxDurationMinutes(null);
        partial.setCollectNoNewWindowSeconds(0);
        set(boss, "config", partial);
        assertEquals(0, invokeInt(boss, "effectiveCollectMaxPerKeyword"), "0=不限制，应保留");
        assertEquals(15, invokeInt(boss, "effectiveCollectMaxDurationMinutes"), "null 回退全局");
        assertEquals(30, invokeInt(boss, "effectiveCollectNoNewWindowSeconds"), "0 回退全局");

        partial.setCollectNoNewWindowSeconds(-3);
        assertEquals(30, invokeInt(boss, "effectiveCollectNoNewWindowSeconds"), "负数回退全局");
    }

    // ==================== 工具 ====================

    private static ProfileTemplateService.TemplateView template(
            ProfileTemplateService.DeliveryPolicy policy) {
        return new ProfileTemplateService.TemplateView(
                1L, "产品方向", "产品", null, true, "2026-11-06 10:00:00", "Java", 30, 50, true, policy);
    }

    @SuppressWarnings("unchecked")
    private static Boss allocateBoss() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        return (Boss) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, Boss.class);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static int invokeInt(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return (int) method.invoke(target);
    }

    @SuppressWarnings("unused")
    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value);
    }
}