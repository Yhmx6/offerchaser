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
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配置中心整合：任务启动预检的模板优先级顺序验收：
 * <ul>
 *   <li>{@code profileTemplateService.ensureActiveApplied()} 必须在读取 BOSS 配置（configService.getBossConfig）
 *       之前调用，并按模板校正当前配置；</li>
 *   <li>模板校正异常只 warn，不阻止任务启动（任务仍能完成）。</li>
 * </ul>
 */
class BossJobServiceTemplatePreflightTest {

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

        Boss bossStub = mock(Boss.class);
        when(schemaMigrationService.isReady()).thenReturn(true);
        when(configService.getBossConfig()).thenReturn(config);
        when(databaseMaintenanceService.backup(anyString())).thenReturn(null);
        when(budgetService.reconcileStaleReservations(anyString())).thenReturn(0);
        when(budgetService.isFirstContactBudgetDepleted()).thenReturn(false);
        when(playwrightManager.getBossPage()).thenReturn(mock(Page.class));
        when(playwrightManager.isLoggedIn("boss")).thenReturn(true);
        when(bossProvider.getObject()).thenReturn(bossStub);
        doReturn(0).when(playwrightManager).callOnPlaywright(any());
        when(bossQueryRepository.countPendingResolutions()).thenReturn(0);
        when(ruleConfigService.getActiveRuleSet()).thenReturn(
                RuleSet.defaultFrom(config, Set.of(), Set.of(), Set.of(), SalaryStrategy.INTERSECT));
        when(ruleConfigService.currentVersion()).thenReturn(1);

        service = new BossJobService(playwrightManager, configService, bossProvider, aiService,
                aiMatchService, budgetService, deliveryLedgerService, taskRunRepository,
                schemaMigrationService, databaseMaintenanceService, bossQueryRepository,
                ruleConfigService, profileTemplateService);
    }

    @Test
    void ensureActiveAppliedRunsBeforeReadingBossConfig() {
        ProfileTemplateService.TemplateView view = new ProfileTemplateService.TemplateView(
                1L, "产品方向", "产品", "说明", true, "2026-11-06 10:00:00", "Java", 30, 50, true, null);
        when(profileTemplateService.ensureActiveApplied()).thenReturn(
                new ProfileTemplateService.ActivateResult(view, List.of("启动前已按生效模板恢复筛选规则")));

        List<JobProgressMessage> messages = new ArrayList<>();
        service.executeDelivery(messages::add);

        assertEquals("COMPLETED", service.getStatus().get("state"));
        InOrder inOrder = inOrder(profileTemplateService, configService);
        inOrder.verify(profileTemplateService).ensureActiveApplied();
        inOrder.verify(configService).getBossConfig();
        assertTrue(messages.stream().anyMatch(message -> message.getMessage() != null
                        && message.getMessage().contains("已按生效模板")),
                "任务进度应提示已按生效模板校正当前配置");
    }

    @Test
    void ensureActiveAppliedExceptionOnlyWarnsAndTaskStillStarts() {
        when(profileTemplateService.ensureActiveApplied())
                .thenThrow(new IllegalStateException("模板服务暂不可用"));

        List<JobProgressMessage> messages = new ArrayList<>();
        service.executeDelivery(messages::add);

        assertEquals("COMPLETED", service.getStatus().get("state"),
                "模板校正异常不得阻止任务启动/完成");
        verify(configService).getBossConfig();
        assertFalse(messages.stream().anyMatch(message -> message.getMessage() != null
                        && message.getMessage().contains("已按生效模板")),
                "模板异常时不应发出“已按模板校正”提示");
    }
}
