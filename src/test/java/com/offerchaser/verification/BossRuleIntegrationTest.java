package com.offerchaser.verification;

import com.offerchaser.application.ai.AiMatchService;
import com.offerchaser.application.contacts.ContactStateService;
import com.offerchaser.application.contacts.FollowupService;
import com.offerchaser.application.delivery.BlacklistMatchMode;
import com.offerchaser.application.delivery.BlacklistPolicy;
import com.offerchaser.application.delivery.JobRuleEngine;
import com.offerchaser.application.delivery.JobSnapshot;
import com.offerchaser.application.delivery.RuleDecision;
import com.offerchaser.application.delivery.RuleOutcome;
import com.offerchaser.application.delivery.RuleSet;
import com.offerchaser.application.delivery.SalaryStrategy;
import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.EvaluationEntry;
import com.offerchaser.application.persistence.EvaluationRepository;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.BossResumeImageService;
import com.offerchaser.application.service.BossService;
import com.offerchaser.worker.boss.Boss;
import com.offerchaser.worker.boss.BossConfig;
import com.offerchaser.worker.boss.BossRunContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2 Boss 运行集成验收：`BossJobService` 注入的 {@link RuleSet} 必须成为本轮固定规则快照，
 * 且 {@code Boss.recordEvaluation} 写出的 job_evaluation 带 rule_version@vN / ruleConfigVersion / rulesHash。
 * <p>
 * 不启动浏览器/Spring：反射检查 Boss 私有 ruleEngine，并用真实 JobRuleEngine 判定快照。
 */
class BossRuleIntegrationTest {

    private BossService bossService;
    private AiService aiService;
    private AiMatchService aiMatchService;
    private BossResumeImageService resumeImageService;
    private BudgetService budgetService;
    private DeliveryLedgerService deliveryLedgerService;
    private EvaluationRepository evaluationRepository;
    private ContactStateService contactStateService;
    private FollowupService followupService;
    private Boss boss;
    private BossConfig config;

    @BeforeEach
    void setUp() {
        bossService = mock(BossService.class);
        aiService = mock(AiService.class);
        aiMatchService = mock(AiMatchService.class);
        resumeImageService = mock(BossResumeImageService.class);
        budgetService = mock(BudgetService.class);
        deliveryLedgerService = mock(DeliveryLedgerService.class);
        evaluationRepository = mock(EvaluationRepository.class);
        contactStateService = mock(ContactStateService.class);
        followupService = mock(FollowupService.class);
        when(bossService.getBlackCompanies()).thenReturn(Set.of());
        when(bossService.getBlackRecruiters()).thenReturn(Set.of());
        when(bossService.getBlackJobs()).thenReturn(Set.of());

        config = new BossConfig();
        config.setKeywords(List.of("Java"));
        config.setCityCode(List.of("101020100"));
        config.setSayHi("您好，我有相关经验，希望进一步沟通。");
        config.setExpectedSalary(List.of(20, 40));

        boss = new Boss(bossService, aiService, aiMatchService, resumeImageService,
                budgetService, deliveryLedgerService, evaluationRepository,
                contactStateService, followupService);
        boss.setConfig(config);
        BossRunContext run = new BossRunContext("run-rule");
        run.markRunning();
        boss.setRunContext(run);
        boss.setProgressCallback((message, current, total) -> {
        });
    }

    @Test
    void prepareUsesInjectedRuleSetSnapshot() throws Exception {
        RuleSet ruleSet = new RuleSet(9, "hash-v9", "本科", SalaryStrategy.INTERSECT, 20, 40,
                List.of("Java"), List.of("上海"), List.of(),
                new BlacklistPolicy(BlacklistMatchMode.CONTAINS, List.of(), List.of(), List.of(), List.of()),
                false, List.of());
        boss.setRuleSet(ruleSet);

        boss.prepare();

        Field engineField = Boss.class.getDeclaredField("ruleEngine");
        engineField.setAccessible(true);
        JobRuleEngine engine = (JobRuleEngine) engineField.get(boss);
        assertNotNull(engine);
        assertEquals(9, engine.getRules().configVersion(), "本轮必须使用注入的规则版本快照");
        assertEquals("hash-v9", engine.getRules().sourceHash());

        // 用该规则判定：方向不符应 REJECT（证明注入的 RuleSet 生效，而不是回退 live 默认）
        JobSnapshot sales = JobSnapshot.builder()
                .encryptId("ENC-1")
                .jobName("销售专员")
                .city("上海")
                .degree("本科")
                .salary("20-30K")
                .jobDescription("负责客户开发与产品销售，完成销售目标。")
                .build();
        assertEquals(RuleOutcome.REJECT, engine.evaluate(sales).outcome());
    }

    @Test
    void prepareFallsBackToLiveDefaultsWhenNoRuleSetInjected() throws Exception {
        boss.prepare();

        Field engineField = Boss.class.getDeclaredField("ruleEngine");
        engineField.setAccessible(true);
        JobRuleEngine engine = (JobRuleEngine) engineField.get(boss);
        assertEquals(0, engine.getRules().configVersion(), "未注入时使用默认 RuleSet（version=0）");
    }

    @Test
    void recordEvaluationWritesRuleVersionAndHash() throws Exception {
        RuleSet ruleSet = new RuleSet(9, "hash-v9", "本科", SalaryStrategy.INTERSECT, 20, 40,
                List.of(), List.of(), List.of(), BlacklistPolicy.empty(), false, List.of());
        boss.setRuleSet(ruleSet);
        boss.prepare();

        Method recordEvaluation = Boss.class.getDeclaredMethod("recordEvaluation",
                JobSnapshot.class, String.class, RuleDecision.class,
                com.offerchaser.application.ai.AiMatchResult.class,
                com.offerchaser.application.persistence.DeliveryKind.class);
        recordEvaluation.setAccessible(true);
        JobSnapshot snapshot = JobSnapshot.builder()
                .encryptId("ENC-1")
                .encryptUserId("USER-1")
                .jobName("Java 开发")
                .companyName("示例科技")
                .salary("20-30K")
                .jobDescription("负责后端服务开发，熟悉 Java 与 Spring。")
                .build();
        recordEvaluation.invoke(boss, snapshot, "Java", RuleDecision.pass(), null, null);

        ArgumentCaptor<EvaluationEntry> captor = ArgumentCaptor.forClass(EvaluationEntry.class);
        verify(evaluationRepository).record(captor.capture());
        EvaluationEntry entry = captor.getValue();
        assertEquals("job-rules-v3@v9", entry.getRuleVersion(),
                "rule_version 必须带本轮规则版本后缀");
        assertEquals(9, entry.getRuleConfigVersion());
        assertEquals("hash-v9", entry.getRulesHash());
        assertEquals("ENC-1", entry.getEncryptId());
        assertEquals("PASS", entry.getRuleDecision());
    }

    @SuppressWarnings("unused")
    private static RuleOutcome noop(RuleOutcome outcome) {
        return outcome;
    }

    @SuppressWarnings("unused")
    private static boolean noopBoolean() {
        return Boolean.TRUE;
    }
}
