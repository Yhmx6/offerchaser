package com.offerchaser.application.onboarding;

import com.offerchaser.application.rules.RuleConfigService;
import com.offerchaser.application.rules.RulePayload;
import com.offerchaser.application.templates.ProfileTemplateService.DeliveryPolicy;
import com.offerchaser.application.templates.ProfileTemplateService.TemplateConfigRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TemplateDraftService} 单元测试：草稿映射、城市/行业/学历/经验/规模/融资阶段、
 * 强度三档、字段归一与参数校验。服务不依赖 AI/数据库，测试构造零依赖即可证明
 * “草稿生成不调用 AI、不落库”。
 */
class TemplateDraftServiceTest {

    private final TemplateDraftService service = new TemplateDraftService();

    private static final ResumeProfile PROFILE = new ResumeProfile(
            3, List.of("产品经理"), List.of("SQL", "Axure"), "硕士", "计算机科学与技术", 2021,
            List.of("上海"), 20, 30, List.of("互联网", "企业服务"), List.of("数据驱动"),
            List.of(), java.util.Map.of(), 0.8);

    @Test
    void buildsTemplateConfigFromConfirmedProfileAndAnswers() {
        AiDraftResult result = service.generate(fullRequest(
                PROFILE, "AI 产品经理", List.of("AI 产品经理", "大模型产品"),
                List.of("上海", "杭州"), List.of("互联网/AI", "人工智能"),
                25, 40, List.of("外包", "销售"), "normal", true, "AI 产品 模板"));

        assertEquals("AI 产品 模板", result.name());
        assertEquals("AI 产品经理", result.direction());
        assertTrue(result.description().contains("AI 产品经理"));

        TemplateConfigRequest config = result.config();
        assertEquals(List.of("AI 产品经理", "大模型产品"), config.keywords());
        assertEquals("上海", config.cityCode(), "第一个城市作为模板城市");
        assertEquals(List.of("互联网/AI", "人工智能"), config.industry());
        assertEquals(List.of("硕士"), config.degree(), "学历取画像最高学历");
        assertEquals(List.of("3-5年"), config.experience(), "3 年经验映射到 3-5年");
        assertEquals(TemplateDraftService.DEFAULT_SCALES, config.scale(), "默认 20 人以上规模");
        assertTrue(config.stage().isEmpty(), "融资阶段默认不限（空列表）");
        assertNull(config.expectedSalaryMin());
        assertNull(config.expectedSalaryMax());
        assertEquals(Boolean.TRUE, config.enableAI());
        assertEquals(Boolean.TRUE, config.sendImgResume());
        assertTrue(config.aiIntroduce().contains("3 年"));
        assertTrue(config.aiIntroduce().contains("熟悉 SQL"));
        assertNull(config.aiPrompt(), "草稿不改写 AI 提示词，保存时沿用模板快照");
        assertEquals(new DeliveryPolicy(60, 15, 30), config.deliveryPolicy());

        RulePayload rule = config.rule();
        assertEquals("INTERSECT", rule.salaryStrategy());
        assertEquals("硕士", rule.highestDegree());
        assertEquals(25, rule.expectedSalaryMin());
        assertEquals(0, rule.expectedSalaryMax());
        assertTrue(rule.jobDirections().isEmpty(), "草稿不再写方向白名单");
        assertTrue(rule.cities().isEmpty(), "草稿不再写可接受城市白名单");
        assertTrue(rule.blacklistCities().isEmpty(), "草稿不可接受城市为空");
        assertEquals("CONTAINS", rule.blacklistMatchMode());
        assertEquals(List.of("外包", "销售"), rule.blacklistJob());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void draftKeepsPrimaryCityForSearchButClearsRuleCityFilters() {
        AiDraftResult result = service.generate(fullRequest(
                PROFILE, "AI 产品经理", List.of("AI 产品经理"),
                List.of("杭州", "上海"), List.of(),
                20, 30, List.of("外包"), "normal", true, "模板"));
        assertEquals("杭州", result.config().cityCode(), "搜索主城市仍取第一个城市");
        assertTrue(result.config().rule().cities().isEmpty(), "可接受城市规则置空");
        assertTrue(result.config().rule().blacklistCities().isEmpty(), "不可接受城市为空");
        assertTrue(result.config().rule().jobDirections().isEmpty(), "方向规则置空");
        assertEquals("INTERSECT", result.config().rule().salaryStrategy(), "薪资策略保持 INTERSECT");
    }

    @Test
    void legacyCityCodeStillWorksWhenCitiesAbsent() {
        AiDraftResult result = service.generate(oldRequest(
                PROFILE, "AI 产品经理", List.of("AI 产品经理"), "上海",
                20, 30, List.of("外包"), "normal", true, "模板"));
        assertEquals("上海", result.config().cityCode());
        assertTrue(result.config().rule().cities().isEmpty(), "旧 cityCode 只用于搜索城市，不再写可接受城市");
    }

    @Test
    void citiesTakePrecedenceOverLegacyCityCode() {
        AiDraftResult result = service.generate(fullRequest(
                PROFILE, "AI 产品经理", List.of("AI 产品经理"),
                List.of("杭州", "上海"), List.of(),
                20, 30, List.of("外包"), "normal", true, "模板"));
        assertEquals("杭州", result.config().cityCode());
        assertTrue(result.config().rule().cities().isEmpty());
    }

    @Test
    void blankCitiesAndLegacyCityClearCityConditionsWithWarning() {
        AiDraftResult result = service.generate(request(
                PROFILE, "AI 产品经理", List.of("AI 产品经理"), "  ",
                20, 30, List.of("外包"), "normal", false, "模板", null, null));
        assertNull(result.config().cityCode());
        assertTrue(result.config().rule().cities().isEmpty());
        assertTrue(result.warnings().contains("未选择城市，草稿的城市条件为不限"));
    }

    @Test
    void industriesAreDedupedAndEmptyKeepsNull() {
        AiDraftResult result = service.generate(fullRequest(
                PROFILE, "AI 产品经理", List.of("AI 产品经理"), List.of("上海"),
                List.of(" 互联网/AI ", "互联网/AI", "人工智能"),
                20, 30, List.of("外包"), "normal", false, "模板"));
        assertEquals(List.of("互联网/AI", "人工智能"), result.config().industry());

        AiDraftResult empty = service.generate(fullRequest(
                PROFILE, "AI 产品经理", List.of("AI 产品经理"), List.of("上海"),
                List.of(), 20, 30, List.of("外包"), "normal", false, "模板"));
        assertNull(empty.config().industry(), "行业为空时保留 null（沿用模板原值）");
        assertTrue(empty.warnings().contains("未选择行业，草稿的行业条件为不限"));
    }

    @Test
    void rejectsTooManyCitiesOrIndustries() {
        OnboardingException citiesError = assertThrows(OnboardingException.class,
                () -> service.generate(fullRequest(PROFILE, "AI 产品经理", List.of("AI"),
                        List.of("上海", "北京", "杭州", "深圳", "广州"), List.of(),
                        20, 30, List.of(), "normal", false, "模板")));
        assertEquals(OnboardingException.DRAFT_INVALID, citiesError.code());
        assertTrue(citiesError.getMessage().contains("城市最多 4 项"));

        OnboardingException industriesError = assertThrows(OnboardingException.class,
                () -> service.generate(fullRequest(PROFILE, "AI 产品经理", List.of("AI"),
                        List.of("上海"),
                        List.of("互联网/AI", "人工智能", "企业服务", "医疗健康", "金融", "游戏", "教育"),
                        20, 30, List.of(), "normal", false, "模板")));
        assertEquals(OnboardingException.DRAFT_INVALID, industriesError.code());
        assertTrue(industriesError.getMessage().contains("行业最多 6 项"));
    }

    @Test
    void degreeIsMappedToBossNames() {
        ResumeProfile associate = profileWith(3, "中专");
        AiDraftResult result = service.generate(oldRequest(associate, "AI 产品经理",
                List.of("AI"), "上海", 20, 30, List.of("外包"), "normal", false, "模板"));
        assertEquals(List.of("中专/中技"), result.config().degree(), "中专应归一为 BOSS 名称");
        assertEquals("中专", result.config().rule().highestDegree(), "规则学历仍用枚举中专");

        ResumeProfile master = profileWith(3, "研究生");
        AiDraftResult masterResult = service.generate(oldRequest(master, "AI 产品经理",
                List.of("AI"), "上海", 20, 30, List.of("外包"), "normal", false, "模板"));
        assertEquals(List.of("硕士"), masterResult.config().degree());
    }

    @Test
    void unsupportedDegreeIsOmittedWithWarning() {
        ResumeProfile profile = profileWith(3, "不限");
        AiDraftResult result = service.generate(oldRequest(profile, "AI 产品经理",
                List.of("AI 产品经理"), "上海", 20, 30, List.of("外包"), "normal", false, "模板"));
        assertNull(result.config().degree());
        assertNull(result.config().rule().highestDegree());
        assertTrue(result.warnings().contains("简历未识别出可用的最高学历，草稿未设置学历条件"));
    }

    @Test
    void experienceIsMappedToSingleBossBand() {
        assertEquals(List.of("1年以下"), experienceOf(0));
        assertEquals(List.of("1-3年"), experienceOf(1));
        assertEquals(List.of("1-3年"), experienceOf(2));
        assertEquals(List.of("3-5年"), experienceOf(3));
        assertEquals(List.of("3-5年"), experienceOf(4));
        assertEquals(List.of("5-10年"), experienceOf(5));
        assertEquals(List.of("5-10年"), experienceOf(9));
        assertEquals(List.of("10年以上"), experienceOf(10));
        assertEquals(List.of("10年以上"), experienceOf(20));
    }

    @Test
    void missingYearsExperienceLeavesExperienceUnsetWithWarning() {
        AiDraftResult result = service.generate(oldRequest(
                profileWith(null, "本科"), "AI 产品经理", List.of("AI"), "上海",
                20, 30, List.of("外包"), "normal", false, "模板"));
        assertNull(result.config().experience());
        assertTrue(result.warnings().contains("简历未识别出工作年限，草稿未设置经验条件"));
    }

    @Test
    void intensityTrialUsesConservativeTwoFiveTwentyPolicy() {
        AiDraftResult result = service.generate(request(
                PROFILE, "AI 产品经理", List.of("AI 产品经理"), null,
                null, null, List.of(), "trial", false, "尝试模板", null, null));
        assertEquals(new DeliveryPolicy(2, 5, 20), result.config().deliveryPolicy());
    }

    @Test
    void intensityActiveUsesHigherCollectionPolicy() {
        AiDraftResult result = service.generate(request(
                PROFILE, "AI 产品经理", List.of("AI 产品经理"), null,
                null, null, List.of(), "active", false, "积极模板", null, null));
        assertEquals(new DeliveryPolicy(100, 30, 30), result.config().deliveryPolicy());
    }

    @Test
    void missingIntensityDefaultsToNormal() {
        AiDraftResult result = service.generate(request(
                PROFILE, "AI 产品经理", List.of("AI 产品经理"), null,
                null, null, List.of(), null, false, "模板", null, null));
        assertEquals(new DeliveryPolicy(60, 15, 30), result.config().deliveryPolicy());
    }

    @Test
    void missingTemplateNameFallsBackToDirection() {
        AiDraftResult result = service.generate(request(
                PROFILE, "AI 产品经理", List.of("AI 产品经理"), null,
                null, null, List.of(), "normal", false, "  ", null, null));
        assertEquals("AI 产品经理 模板", result.name());
    }

    @Test
    void missingSalaryFallsBackToProfileNumbers() {
        AiDraftResult result = service.generate(request(
                PROFILE, "AI 产品经理", List.of("AI 产品经理"), null,
                null, null, List.of(), "normal", false, "模板", null, null));
        assertNull(result.config().expectedSalaryMin());
        assertNull(result.config().expectedSalaryMax());
        assertEquals(20, result.config().rule().expectedSalaryMin());
        assertEquals(0, result.config().rule().expectedSalaryMax());
    }

    @Test
    void exclusionsAreTrimmedDedupedAndEmptyAddsWarning() {
        AiDraftResult deduped = service.generate(request(
                PROFILE, "AI 产品经理", List.of("AI 产品经理"), null,
                20, 30, List.of(" 外包 ", "外包", "销售", " "), "normal", false, "模板", null, null));
        assertEquals(List.of("外包", "销售"), deduped.config().rule().blacklistJob());

        AiDraftResult empty = service.generate(request(
                PROFILE, "AI 产品经理", List.of("AI 产品经理"), null,
                20, 30, List.of(), "normal", false, "模板", null, null));
        assertTrue(empty.config().rule().blacklistJob().isEmpty());
        assertTrue(empty.warnings().contains("未填写排除词，草稿的职位黑名单为空"));
    }

    @Test
    void rejectsMissingProfile() {
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> service.generate(request(null, "AI 产品经理", List.of("AI"), null,
                        null, null, List.of(), "normal", true, "模板", null, null)));
        assertEquals(OnboardingException.DRAFT_INVALID, error.code());
        assertEquals(400, error.status().value());
    }

    @Test
    void rejectsBlankDirection() {
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> service.generate(request(PROFILE, "  ", List.of("AI"), null,
                        null, null, List.of(), "normal", true, "模板", null, null)));
        assertEquals(OnboardingException.DRAFT_INVALID, error.code());
    }

    @Test
    void rejectsEmptyKeywords() {
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> service.generate(request(PROFILE, "AI 产品经理", List.of("  "), null,
                        null, null, List.of(), "normal", true, "模板", null, null)));
        assertEquals(OnboardingException.DRAFT_INVALID, error.code());
        assertTrue(error.getMessage().contains("关键词"));
    }

    @Test
    void rejectsInvalidIntensity() {
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> service.generate(request(PROFILE, "AI 产品经理", List.of("AI"), null,
                        null, null, List.of(), "aggressive", true, "模板", null, null)));
        assertEquals(OnboardingException.DRAFT_INVALID, error.code());
        assertTrue(error.getMessage().contains("trial"));
    }

    @Test
    void ignoresUpperBoundAndOnlyValidatesLowerBound() {
        // 上限不再写入规则，也不参与校验：反向区间（min 40 > max 20）不得报错。
        AiDraftResult reversed = service.generate(request(
                PROFILE, "AI 产品经理", List.of("AI"), null,
                40, 20, List.of(), "normal", true, "模板", null, null));
        assertEquals(40, reversed.config().rule().expectedSalaryMin(), "只写用户下限");
        assertEquals(0, reversed.config().rule().expectedSalaryMax(), "上限固定 0=未设置");
        assertNull(reversed.config().expectedSalaryMin(), "A 区下限不写");
        assertNull(reversed.config().expectedSalaryMax(), "A 区上限不写");

        // 用户下限高于简历推荐上限（PROFILE max=30）也不得报错：简历上限被忽略。
        AiDraftResult aboveProfileMax = service.generate(request(
                PROFILE, "AI 产品经理", List.of("AI"), null,
                60, null, List.of(), "normal", true, "模板", null, null));
        assertEquals(60, aboveProfileMax.config().rule().expectedSalaryMin());
        assertEquals(0, aboveProfileMax.config().rule().expectedSalaryMax());

        // 下限本身仍必须合法（负数 → DRAFT_INVALID）。
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> service.generate(request(PROFILE, "AI 产品经理", List.of("AI"), null,
                        -5, null, List.of(), "normal", true, "模板", null, null)));
        assertEquals(OnboardingException.DRAFT_INVALID, error.code());
        assertTrue(error.getMessage().contains("下限"));
    }

    @Test
    void rejectsNegativeWorkYears() {
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> service.generate(oldRequest(
                        profileWith(-1, "本科"), "AI 产品经理", List.of("AI"), "上海",
                        20, 30, List.of(), "normal", false, "模板")));
        assertEquals(OnboardingException.DRAFT_INVALID, error.code());
        assertTrue(error.getMessage().contains("工作年限"));
    }

    @Test
    void draftRulePassesRealRuleValidation() {
        AiDraftResult result = service.generate(fullRequest(
                PROFILE, "AI 产品经理", List.of("AI 产品经理", "大模型产品"),
                List.of("上海", "杭州"), List.of("互联网/AI", "人工智能"),
                25, 40, List.of("外包", "销售"), "normal", true, "AI 产品 模板"));

        RuleConfigService ruleConfigService = new RuleConfigService(null, null, null, null);
        RulePayload canonical = ruleConfigService.validateAndCanonicalize(result.config().rule());

        assertEquals("INTERSECT", canonical.salaryStrategy());
        assertEquals("硕士", canonical.highestDegree());
        assertEquals("CONTAINS", canonical.blacklistMatchMode());
        assertTrue(canonical.jobDirections().isEmpty(), "草稿方向为空，可通过规则校验");
        assertTrue(canonical.cities().isEmpty(), "草稿可接受城市为空，可通过规则校验");
        assertTrue(canonical.blacklistCities().isEmpty(), "草稿不可接受城市为空");
        assertTrue(canonical.blacklistJob().containsAll(List.of("外包", "销售")));
    }

    @Test
    void draftDoesNotMutateCallerLists() {
        List<String> keywords = new java.util.ArrayList<>(List.of(" AI 产品经理 "));
        List<String> cities = new java.util.ArrayList<>(List.of("上海", "杭州"));
        List<String> industries = new java.util.ArrayList<>(List.of("互联网/AI"));
        AiDraftResult result = service.generate(request(PROFILE, "AI 产品经理", keywords, "上海",
                20, 30, List.of(), "normal", false, "模板", cities, industries));
        assertEquals(List.of("AI 产品经理"), result.config().keywords());
        assertEquals(List.of(" AI 产品经理 "), keywords);
        assertEquals(List.of("上海", "杭州"), cities);
        assertEquals(List.of("互联网/AI"), industries);
        assertFalse(result.config().keywords().isEmpty());
    }

    // ==================== 工具 ====================

    private static ResumeProfile profileWith(Integer years, String degree) {
        return new ResumeProfile(years, List.of("产品经理"), List.of("SQL"), degree,
                "计算机科学与技术", 2021, List.of("上海"), 20, 30,
                List.of("互联网"), List.of("数据驱动"), List.of(), java.util.Map.of(), 0.8);
    }

    private List<String> experienceOf(Integer years) {
        AiDraftResult result = service.generate(oldRequest(
                profileWith(years, "本科"), "AI 产品经理", List.of("AI"), "上海",
                20, 30, List.of("外包"), "normal", false, "模板"));
        return result.config().experience();
    }

    /** 旧请求（只传 cityCode，不传 cities/industries）——用于兼容性测试。 */
    private static AiDraftRequest oldRequest(ResumeProfile profile, String direction, List<String> keywords,
                                             String city, Integer salaryMin, Integer salaryMax,
                                             List<String> exclusions, String intensity,
                                             boolean sendImgResume, String templateName) {
        return new AiDraftRequest(profile, direction, keywords, city, salaryMin, salaryMax,
                exclusions, intensity, sendImgResume, templateName);
    }

    /** 新请求（cities + industries），用于完整映射测试。 */
    private static AiDraftRequest fullRequest(ResumeProfile profile, String direction, List<String> keywords,
                                              List<String> cities, List<String> industries,
                                              Integer salaryMin, Integer salaryMax,
                                              List<String> exclusions, String intensity,
                                              boolean sendImgResume, String templateName) {
        return request(profile, direction, keywords, null, salaryMin, salaryMax,
                exclusions, intensity, sendImgResume, templateName, cities, industries);
    }

    private static AiDraftRequest request(ResumeProfile profile, String direction, List<String> keywords,
                                          String city, Integer salaryMin, Integer salaryMax,
                                          List<String> exclusions, String intensity,
                                          boolean sendImgResume, String templateName,
                                          List<String> cities, List<String> industries) {
        return new AiDraftRequest(profile, direction, keywords, city, salaryMin, salaryMax,
                exclusions, intensity, sendImgResume, templateName, cities, industries);
    }
}
