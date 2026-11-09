package com.offerchaser.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerchaser.application.ai.AiMatchService;
import com.offerchaser.application.controller.BossAuditController;
import com.offerchaser.application.delivery.RuleSet;
import com.offerchaser.application.delivery.SalaryStrategy;
import com.offerchaser.application.persistence.BossQueryRepository;
import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DatabaseMaintenanceService;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.PageResult;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.offerchaser.verification.VerificationSupport.execute;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 C 收尾：运行记录绑定方向模板验收。
 * <ul>
 *   <li>BossJobService 预检时把生效模板写入 task_run.config_snapshot（template{id,name,direction}）；
 *       无模板不写；模板读取异常不影响启动。</li>
 *   <li>BossQueryRepository.listRuns 从 config_snapshot 解析 templateId/templateName/templateDirection；
 *       旧记录为 null；不泄漏原始 config_snapshot。</li>
 *   <li>BossAuditController /api/boss/runs 透传新字段。</li>
 * </ul>
 */
class RunTemplateBindingIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== BossJobService 启动快照 ====================

    private PlaywrightManager playwrightManager;
    private ConfigService configService;
    @SuppressWarnings("unchecked")
    private ObjectProvider<Boss> bossProvider;
    private AiService aiService;
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
    void setUpBossJobService() {
        playwrightManager = mock(PlaywrightManager.class);
        configService = mock(ConfigService.class);
        bossProvider = mock(ObjectProvider.class);
        aiService = mock(AiService.class);
        AiMatchService aiMatchService = mock(AiMatchService.class);
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

    @Test
    void runStartSnapshotBindsActiveTemplate() throws Exception {
        ProfileTemplateService.TemplateView view = new ProfileTemplateService.TemplateView(
                7L, "产品方向", "产品", "面向产品", true, "2026-11-06 10:00:00", "Java", 30, 50, true, null);
        when(profileTemplateService.active()).thenReturn(Optional.of(view));

        List<JobProgressMessage> messages = new ArrayList<>();
        service.executeDelivery(messages::add);

        assertEquals("COMPLETED", service.getStatus().get("state"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> snapshotCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskRunRepository).start(anyString(), eq("boss"), snapshotCaptor.capture());
        JsonNode snapshot = MAPPER.readTree(snapshotCaptor.getValue());
        JsonNode template = snapshot.path("template");
        assertTrue(template.isObject(), "运行快照应包含 template 对象：" + snapshotCaptor.getValue());
        assertEquals(7L, template.get("id").asLong());
        assertEquals("产品方向", template.get("name").asText());
        assertEquals("产品", template.get("direction").asText());
        assertTrue(snapshot.hasNonNull("rulesVersion"), "原快照字段不应丢失");
        assertTrue(snapshot.has("bossConfig"));
    }

    @Test
    void runStartSnapshotHasNoTemplateWhenNoneActive() throws Exception {
        when(profileTemplateService.active()).thenReturn(Optional.empty());

        List<JobProgressMessage> messages = new ArrayList<>();
        service.executeDelivery(messages::add);

        assertEquals("COMPLETED", service.getStatus().get("state"));
        ArgumentCaptor<String> snapshotCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskRunRepository).start(anyString(), eq("boss"), snapshotCaptor.capture());
        JsonNode snapshot = MAPPER.readTree(snapshotCaptor.getValue());
        assertFalse(snapshot.has("template"), "无生效模板时不应写 template");
        assertTrue(snapshot.hasNonNull("rulesVersion"));
    }

    @Test
    void templateReadExceptionDoesNotBlockTaskStart() throws Exception {
        when(profileTemplateService.active()).thenThrow(new IllegalStateException("模板库不可用"));

        List<JobProgressMessage> messages = new ArrayList<>();
        service.executeDelivery(messages::add);

        assertEquals("COMPLETED", service.getStatus().get("state"), "模板读取异常不得阻止任务启动");
        ArgumentCaptor<String> snapshotCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskRunRepository).start(anyString(), eq("boss"), snapshotCaptor.capture());
        JsonNode snapshot = MAPPER.readTree(snapshotCaptor.getValue());
        assertFalse(snapshot.has("template"));
        assertTrue(snapshot.has("bossConfig"), "异常时仍应保留原有运行快照");
    }

    // ==================== listRuns 解析 ====================

    @Test
    void listRunsReturnsParsedTemplateFieldsAndHidesRawSnapshot(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        SQLiteDataSource dataSource = migratedDataSource(tempDir);
        BossQueryRepository repository = new BossQueryRepository(dataSource);
        String snapshotWithTemplate = MAPPER.writeValueAsString(Map.of(
                "bossConfig", Map.of("keywords", "[\"Java\"]"),
                "rulesVersion", 2,
                "template", Map.of("id", 7, "name", "产品方向", "direction", "产品")));
        execute(dataSource, "INSERT INTO task_run(run_id, platform, state, config_snapshot, " +
                        "started_at, ended_at, updated_at) VALUES('run-with-template','boss','COMPLETED',?, " +
                        "'2026-11-06 10:00:00','2026-11-06 10:30:00','2026-11-06 10:30:00')",
                snapshotWithTemplate);
        execute(dataSource, "INSERT INTO task_run(run_id, platform, state, config_snapshot, " +
                        "started_at, updated_at) VALUES('run-old', 'boss', 'COMPLETED', NULL, " +
                        "'2026-11-05 10:00:00','2026-11-05 10:30:00')");
        execute(dataSource, "INSERT INTO task_run(run_id, platform, state, config_snapshot, " +
                        "started_at, updated_at) VALUES('run-legacy-snapshot', 'boss', 'COMPLETED', '{}', " +
                        "'2026-11-04 10:00:00','2026-11-04 10:30:00')");

        PageResult<Map<String, Object>> page = repository.listRuns(null, null, null, 1, 20);

        assertEquals(3, page.total());
        Map<String, Object> withTemplate = findRun(page.items(), "run-with-template");
        assertEquals(7, ((Number) withTemplate.get("templateId")).intValue());
        assertEquals("产品方向", withTemplate.get("templateName"));
        assertEquals("产品", withTemplate.get("templateDirection"));
        assertFalse(withTemplate.containsKey("config_snapshot"));
        assertFalse(withTemplate.containsKey("configSnapshot"), "不得返回原始 config_snapshot");

        Map<String, Object> old = findRun(page.items(), "run-old");
        assertNull(old.get("templateId"));
        assertNull(old.get("templateName"));
        assertNull(old.get("templateDirection"));
        Map<String, Object> legacy = findRun(page.items(), "run-legacy-snapshot");
        assertNull(legacy.get("templateName"));
    }

    @Test
    void runsApiExposesTemplateFields() {
        BossQueryRepository repository = mock(BossQueryRepository.class);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("runId", "run-1");
        item.put("state", "COMPLETED");
        item.put("templateId", 7L);
        item.put("templateName", "产品方向");
        item.put("templateDirection", "产品");
        when(repository.listRuns(null, null, null, 1, 20))
                .thenReturn(new PageResult<>(List.of(item), 1, 20, 1L));
        BossAuditController controller = new BossAuditController(repository,
                mock(com.offerchaser.application.persistence.AttemptResolutionService.class));

        var response = controller.listRuns(null, null, null, 1, 20);

        assertEquals(200, response.getStatusCode().value());
        PageResult<?> data = (PageResult<?>) response.getBody().get("data");
        assertEquals(1, data.items().size());
        @SuppressWarnings("unchecked")
        Map<String, Object> returned = (Map<String, Object>) data.items().get(0);
        assertEquals(7L, returned.get("templateId"));
        assertEquals("产品方向", returned.get("templateName"));
        assertEquals("产品", returned.get("templateDirection"));
    }

    // ==================== 工具 ====================

    private static Map<String, Object> findRun(List<Map<String, Object>> items, String runId) {
        return items.stream().filter(item -> runId.equals(item.get("runId"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unused")
    private static int count(SQLiteDataSource dataSource) {
        return scalar(dataSource, "SELECT COUNT(*) FROM task_run");
    }
}
