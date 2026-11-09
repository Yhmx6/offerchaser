package com.offerchaser.application.onboarding;

import com.offerchaser.application.rules.RulePayload;
import com.offerchaser.application.templates.ProfileTemplateService.DeliveryPolicy;
import com.offerchaser.application.templates.ProfileTemplateService.TemplateConfigRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 模板草稿生成服务：只根据用户已确认的画像与问答拼装 {@link TemplateConfigRequest}。
 * <p>
 * 明确边界：
 * <ul>
 *   <li>不调用 AI、不调用任何写接口、不落库；</li>
 *   <li>不创建/激活模板，保存动作仍由前端确认后走现有 create/update 接口；</li>
 *   <li>不设置会绕过安全链路的字段（预算、身份、发送确认等都不在模板配置里）；</li>
 *   <li>投递强度只影响采集策略（每关键词上限/时长/无新增窗口），不改变每日预算与发送确认。</li>
 * </ul>
 * </p>
 */
@Service
public class TemplateDraftService {

    /** 草稿允许的最大关键词数 */
    private static final int MAX_KEYWORDS = 20;
    private static final int MAX_KEYWORD_LENGTH = 40;
    /** 排除词上限与单项长度 */
    private static final int MAX_EXCLUSIONS = 50;
    private static final int MAX_EXCLUSION_LENGTH = 50;
    /** 城市：最多 4 个，城市名限长 */
    private static final int MAX_CITIES = 4;
    private static final int MAX_CITY_LENGTH = 20;
    /** 行业：最多 6 个（用户确认的行业名称） */
    private static final int MAX_INDUSTRIES = 6;
    private static final int MAX_INDUSTRY_LENGTH = 40;
    /** 模板名/方向/描述长度（与模板保存接口的限制一致） */
    private static final int MAX_TEMPLATE_NAME = 60;
    private static final int MAX_DIRECTION = 40;
    private static final int MAX_DESCRIPTION = 300;
    private static final int MAX_AI_INTRODUCE = 300;
    private static final int MAX_SALARY_K = 1000;
    private static final int MAX_YEARS_EXPERIENCE = 60;

    /** 规则服务可接受的最高学历（与 RuleConfigService 校验一致） */
    private static final Set<String> RULE_DEGREES = Set.of("高中", "中专", "大专", "本科", "硕士", "博士");
    /** BOSS 学历选项名称（写进 boss_config.degree） */
    private static final Set<String> BOSS_DEGREE_NAMES =
            Set.of("初中及以下", "中专/中技", "高中", "大专", "本科", "硕士", "博士");
    /** 草稿默认公司规模：20 人以上（不含 0-20 人） */
    static final List<String> DEFAULT_SCALES = List.of(
            "20-99人", "100-499人", "500-999人", "1000-9999人", "10000人以上");

    /** 尝试期：更少候选、更短采集时长（每关键词 2 个候选 / 5 分钟 / 20 秒无新增窗口） */
    static final DeliveryPolicy TRIAL_POLICY = new DeliveryPolicy(2, 5, 20);
    /** 日常：与全局默认采集上限一致 */
    static final DeliveryPolicy NORMAL_POLICY = new DeliveryPolicy(60, 15, 30);
    /** 积极：提高采集上限（仍受每日预算与发送确认限制） */
    static final DeliveryPolicy ACTIVE_POLICY = new DeliveryPolicy(100, 30, 30);

    /**
     * 生成模板草稿。
     *
     * @param request 已确认的画像 + 向导问答
     * @return 草稿（name/direction/description/config/warnings）
     * @throws OnboardingException 参数非法（DRAFT_INVALID，400）
     */
    public AiDraftResult generate(AiDraftRequest request) {
        if (request == null) {
            throw OnboardingException.draftInvalid("草稿请求不能为空");
        }
        ResumeProfile profile = request.profile();
        if (profile == null) {
            throw OnboardingException.draftInvalid("缺少简历画像，请先完成简历分析");
        }

        String direction = normalizeDirection(request.direction());
        List<String> keywords = normalizeTextList(request.keywords(), "搜索关键词", MAX_KEYWORDS, MAX_KEYWORD_LENGTH);
        if (keywords.isEmpty()) {
            throw OnboardingException.draftInvalid("请至少填写一个搜索关键词");
        }

        // 城市：新字段 cities 优先（第一个作模板城市、全部进规则城市条件）；旧 cityCode 作为回退。
        List<String> cities = normalizeTextList(request.cities(), "城市", MAX_CITIES, MAX_CITY_LENGTH);
        List<String> effectiveCities = cities.isEmpty()
                ? legacyCity(request.cityCode())
                : cities;
        String primaryCity = effectiveCities.isEmpty() ? null : effectiveCities.get(0);

        List<String> industries = normalizeTextList(request.industries(), "行业", MAX_INDUSTRIES, MAX_INDUSTRY_LENGTH);

        Integer salaryMin = firstNonNull(request.expectedSalaryMin(), profile.expectedSalaryMin());
        // 只写下限；不再用简历推荐上限校验，也不把上限写入规则。
        Integer salaryMax = null;
        validateSalary(salaryMin, salaryMax);
        List<String> exclusions = normalizeTextList(request.exclusions(), "排除词", MAX_EXCLUSIONS, MAX_EXCLUSION_LENGTH);
        String intensity = normalizeIntensity(request.intensity());
        boolean sendImgResume = Boolean.TRUE.equals(request.sendImgResume());
        String templateName = normalizeTemplateName(request.templateName(), direction);

        List<String> warnings = new ArrayList<>();
        String ruleDegree = normalizeRuleDegree(profile.highestDegree());
        if (ruleDegree == null) {
            warnings.add("简历未识别出可用的最高学历，草稿未设置学历条件");
        }
        List<String> experienceOption = mapExperience(profile.yearsExperience());
        if (experienceOption == null) {
            warnings.add("简历未识别出工作年限，草稿未设置经验条件");
        }
        if (effectiveCities.isEmpty()) {
            warnings.add("未选择城市，草稿的城市条件为不限");
        }
        if (industries.isEmpty()) {
            warnings.add("未选择行业，草稿的行业条件为不限");
        }
        if (salaryMin == null && salaryMax == null) {
            warnings.add("未提供期望薪资，草稿未设置薪资区间");
        }
        if (exclusions.isEmpty()) {
            warnings.add("未填写排除词，草稿的职位黑名单为空");
        }

        // 草稿不再写方向/可接受城市（置空，避免新模板继承旧白名单过滤）；不可接受城市也为空。
        RulePayload rule = new RulePayload(
                ruleDegree,
                "INTERSECT",
                salaryMin,
                0,
                List.of(),
                List.of(),
                List.of(),
                null,
                "CONTAINS",
                exclusions,
                List.of(),
                List.of(),
                null,
                null,
                null);

        TemplateConfigRequest config = new TemplateConfigRequest(
                keywords,
                primaryCity,
                null,
                industries.isEmpty() ? null : industries,
                experienceOption,
                bossDegreeOption(profile.highestDegree()),
                null,
                DEFAULT_SCALES,
                List.of(),
                null,
                null,
                true,
                sendImgResume,
                null,
                null,
                null,
                null,
                null,
                null,
                rule,
                buildAiIntroduce(profile, direction),
                null,
                policyFor(intensity));

        return new AiDraftResult(templateName, direction,
                buildDescription(direction, keywords, intensity), config, List.copyOf(warnings));
    }

    // ==================== 字段归一 ====================

    private static String normalizeDirection(String raw) {
        String direction = raw == null ? "" : raw.trim();
        if (direction.isEmpty()) {
            throw OnboardingException.draftInvalid("请先选择或填写目标方向");
        }
        if (direction.length() > MAX_DIRECTION) {
            throw OnboardingException.draftInvalid("目标方向最多 " + MAX_DIRECTION + " 字");
        }
        return direction;
    }

    private static List<String> normalizeTextList(List<String> values, String field, int maxItems, int maxItemLength) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String text = value.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() > maxItemLength) {
                throw OnboardingException.draftInvalid(field + "中有一项超过 " + maxItemLength + " 字");
            }
            normalized.add(text);
            if (normalized.size() > maxItems) {
                throw OnboardingException.draftInvalid(field + "最多 " + maxItems + " 项");
            }
        }
        return List.copyOf(normalized);
    }

    /** 旧 cityCode 回退：为空返回空列表，非空时当作单个城市并校验长度。 */
    private static List<String> legacyCity(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String city = raw.trim();
        if (city.length() > MAX_CITY_LENGTH) {
            throw OnboardingException.draftInvalid("城市名称过长（最多 " + MAX_CITY_LENGTH + " 字）");
        }
        return List.of(city);
    }

    private static void validateSalary(Integer min, Integer max) {
        if (min != null && (min < 0 || min > MAX_SALARY_K)) {
            throw OnboardingException.draftInvalid("期望月薪下限必须在 0~" + MAX_SALARY_K + "K 之间");
        }
        if (max != null && (max < 0 || max > MAX_SALARY_K)) {
            throw OnboardingException.draftInvalid("期望月薪上限必须在 0~" + MAX_SALARY_K + "K 之间");
        }
        if (min != null && max != null && min > max) {
            throw OnboardingException.draftInvalid("期望月薪下限不能大于上限");
        }
    }

    private static String normalizeIntensity(String raw) {
        String intensity = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (intensity.isEmpty()) {
            return "normal";
        }
        if ("trial".equals(intensity) || "normal".equals(intensity) || "active".equals(intensity)) {
            return intensity;
        }
        throw OnboardingException.draftInvalid("投递强度只能是 trial / normal / active");
    }

    private static String normalizeTemplateName(String raw, String direction) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            name = direction + " 模板";
        }
        return name.length() > MAX_TEMPLATE_NAME ? name.substring(0, MAX_TEMPLATE_NAME) : name;
    }

    /** 规则学历只接受固定枚举；识别不到/不在枚举内时留空（保存时沿用模板当前值）。 */
    private static String normalizeRuleDegree(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String degree = switch (raw.trim()) {
            case "研究生", "硕士研究生", "硕士生" -> "硕士";
            case "博士研究生" -> "博士";
            case "学士" -> "本科";
            case "专科" -> "大专";
            case "中专/中技" -> "中专";
            default -> raw.trim();
        };
        return RULE_DEGREES.contains(degree) ? degree : null;
    }

    /** BOSS 学历选项：只接受 BOSS 实际存在的学历名称，映射不到返回 null（不设置）。 */
    private static List<String> bossDegreeOption(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String degree = switch (raw.trim()) {
            case "研究生", "硕士研究生", "硕士生" -> "硕士";
            case "博士研究生" -> "博士";
            case "学士" -> "本科";
            case "专科" -> "大专";
            case "中专" -> "中专/中技";
            default -> raw.trim();
        };
        return BOSS_DEGREE_NAMES.contains(degree) ? List.of(degree) : null;
    }

    /** 工作年限 → BOSS 经验单档（半开区间：<1 / [1,3) / [3,5) / [5,10) / >=10）。 */
    private static List<String> mapExperience(Integer yearsExperience) {
        if (yearsExperience == null) {
            return null;
        }
        if (yearsExperience < 0) {
            throw OnboardingException.draftInvalid("工作年限不能为负数");
        }
        if (yearsExperience > MAX_YEARS_EXPERIENCE) {
            throw OnboardingException.draftInvalid("工作年限不能超过 " + MAX_YEARS_EXPERIENCE + " 年");
        }
        if (yearsExperience < 1) {
            return List.of("1年以下");
        }
        if (yearsExperience < 3) {
            return List.of("1-3年");
        }
        if (yearsExperience < 5) {
            return List.of("3-5年");
        }
        if (yearsExperience < 10) {
            return List.of("5-10年");
        }
        return List.of("10年以上");
    }

    private static DeliveryPolicy policyFor(String intensity) {
        return switch (intensity) {
            case "trial" -> TRIAL_POLICY;
            case "active" -> ACTIVE_POLICY;
            default -> NORMAL_POLICY;
        };
    }

    // ==================== 文本生成 ====================

    /** 基于画像的简短 AI 介绍（只拼事实字段，不调用 AI）。 */
    private static String buildAiIntroduce(ResumeProfile profile, String direction) {
        StringBuilder intro = new StringBuilder();
        if (profile.yearsExperience() != null && profile.yearsExperience() > 0) {
            intro.append(profile.yearsExperience()).append(" 年");
        }
        List<String> roles = safeList(profile.roles());
        List<String> skills = safeList(profile.skills());
        List<String> strengths = safeList(profile.strengths());

        if (!roles.isEmpty()) {
            intro.append(joinLimit(roles, 2, "、")).append("经验");
        } else if (intro.length() == 0) {
            intro.append(direction).append("方向");
        }
        if (!skills.isEmpty()) {
            if (intro.length() > 0) {
                intro.append("；");
            }
            intro.append("熟悉 ").append(joinLimit(skills, 5, "、"));
        }
        if (!strengths.isEmpty()) {
            if (intro.length() > 0) {
                intro.append("；");
            }
            intro.append("优势：").append(joinLimit(strengths, 3, "、"));
        }
        if (intro.length() > 0) {
            intro.append('。');
        }
        intro.append("求职方向：").append(direction).append('。');
        String text = intro.toString();
        return text.length() > MAX_AI_INTRODUCE ? text.substring(0, MAX_AI_INTRODUCE) : text;
    }

    private static String buildDescription(String direction, List<String> keywords, String intensity) {
        String description = "AI 向导草稿：" + direction + " 方向，关键词 " + joinLimit(keywords, 5, "、")
                + "；投递强度 " + intensityLabel(intensity)
                + "。保存后仍需通过规则/AI/预算/身份核对与文字、图片发送确认。";
        return description.length() > MAX_DESCRIPTION ? description.substring(0, MAX_DESCRIPTION) : description;
    }

    private static String intensityLabel(String intensity) {
        return switch (intensity) {
            case "trial" -> "尝试期（更保守）";
            case "active" -> "积极（更主动）";
            default -> "日常（推荐）";
        };
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static String joinLimit(List<String> values, int limit, String separator) {
        List<String> selected = values.size() <= limit ? values : values.subList(0, limit);
        return String.join(separator, selected);
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }
}
