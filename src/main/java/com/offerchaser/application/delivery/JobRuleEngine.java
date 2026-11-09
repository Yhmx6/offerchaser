package com.offerchaser.application.delivery;

import com.offerchaser.worker.boss.BossConfig;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 岗位硬规则引擎（阶段 B B2：规则快照可配置、结果可解释）。
 * <p>
 * 只读取 {@link RuleSet}（一轮投递固定使用的规则快照），不再直接读 BossConfig；
 * 管理台修改规则由 RuleConfigService 生成新版本，运行中不受影响。
 * <p>
 * 判定顺序（先便宜后昂贵、先明确后模糊）：
 * 身份 → 岗位内容完整性 → 岗位方向 → 城市 → 学历等级（偏好不拒 / JD 冲突复核）
 * → 黑名单（白名单优先，CONTAINS/EXACT）→ HR 活跃度 → 期望薪资 → PASS。
 * <p>
 * 本类不依赖 Spring 容器，可被单元测试直接 new 出来；{@link #evaluate(JobSnapshot)} 承诺不抛异常，
 * 任何内部异常都会转成 {@link RuleOutcome#REVIEW}（不确定就不投递）。
 */
@Slf4j
public final class JobRuleEngine {

    /** 规则版本号，写库时使用（job_evaluation.rule_version）。 */
    public static final String RULE_VERSION = "job-rules-v3";

    /** 「优先」等偏好表述：岗位学历要求高于用户时不拒绝。 */
    private static final String PREFERENCE_KEYWORD = "优先";
    /** 紧跟在学历要求之后的软化表述（偏好/加分），JD 冲突识别时跳过。 */
    private static final List<String> SOFT_EDUCATION_SUFFIXES = List.of("优先", "加分", "更佳");
    /** JD 里出现这些高学历关键词、且后面没跟「优先/加分」时，视为硬性学历要求。 */
    private static final List<String> HARD_EDUCATION_KEYWORDS = List.of("博士", "硕士", "研究生");
    /** 判断是否偏好表述时向后看的字数（覆盖「及以上学历优先」这类写法）。 */
    private static final int SOFT_EDUCATION_LOOKAHEAD = 6;

    private final RuleSet rules;

    /**
     * 主构造器：使用不可变规则快照。
     *
     * @param rules 规则快照，可为 null（退化为全默认规则，便于容错）
     */
    public JobRuleEngine(RuleSet rules) {
        this.rules = rules == null ? RuleSet.defaultFrom(null, null, null, null, null) : rules;
    }

    /**
     * 旧构造器：兼容 A+ 阶段调用方/旧测试。
     *
     * @deprecated 请改用 {@link #JobRuleEngine(RuleSet)} 并通过 RuleConfigService 取规则快照。
     */
    @Deprecated
    public JobRuleEngine(BossConfig config,
                         Set<String> blackCompanies,
                         Set<String> blackRecruiters,
                         Set<String> blackJobs,
                         SalaryStrategy salaryStrategy) {
        this(RuleSet.defaultFrom(config, blackCompanies, blackRecruiters, blackJobs, salaryStrategy));
    }

    /** 当前使用的规则快照（不可变）。 */
    public RuleSet getRules() {
        return rules;
    }

    /**
     * 执行全部硬规则。
     *
     * @param snapshot 岗位快照，可为 null
     * @return 永远非 null 的判定结果；内部异常时返回 REVIEW
     */
    public RuleDecision evaluate(JobSnapshot snapshot) {
        try {
            RuleDecision decision = decide(snapshot);
            logDecision(decision, snapshot);
            return decision;
        } catch (Exception e) {
            log.error("硬规则执行异常，转人工复核 | 岗位：{} | 规则：{}@v{} | 异常：{}",
                    snapshot == null ? null : snapshot.jobName(),
                    RULE_VERSION, rules.configVersion(), e.getMessage(), e);
            return RuleDecision.review(RuleReason.MISSING_DETAIL_RESPONSE, "",
                    "规则引擎内部异常，转人工复核：" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ":" + e.getMessage()));
        }
    }

    /** 顺序执行各条规则，遇到非 PASS 立即返回。 */
    private RuleDecision decide(JobSnapshot snapshot) {
        if (snapshot == null) {
            return RuleDecision.review(RuleReason.MISSING_DETAIL_RESPONSE, "",
                    "岗位快照为空，无法执行规则判定，转人工复核");
        }
        RuleDecision decision = checkIdentity(snapshot);
        if (decision.outcome() != RuleOutcome.PASS) {
            return decision;
        }
        decision = checkEssentialContent(snapshot);
        if (decision.outcome() != RuleOutcome.PASS) {
            return decision;
        }
        decision = checkDirections(snapshot);
        if (decision.outcome() != RuleOutcome.PASS) {
            return decision;
        }
        decision = checkCities(snapshot);
        if (decision.outcome() != RuleOutcome.PASS) {
            return decision;
        }
        decision = checkBlacklistCities(snapshot);
        if (decision.outcome() != RuleOutcome.PASS) {
            return decision;
        }
        decision = checkDegree(snapshot);
        if (decision.outcome() != RuleOutcome.PASS) {
            return decision;
        }
        decision = checkBlacklist(snapshot);
        if (decision.outcome() != RuleOutcome.PASS) {
            return decision;
        }
        decision = checkHrActivity(snapshot);
        if (decision.outcome() != RuleOutcome.PASS) {
            return decision;
        }
        return checkSalary(snapshot);
    }

    /** 1. 身份：没有 encryptId 无法去重/建台账/打开详情页。 */
    private RuleDecision checkIdentity(JobSnapshot snapshot) {
        if (snapshot.hasIdentity()) {
            return RuleDecision.pass();
        }
        return RuleDecision.reject(RuleReason.MISSING_IDENTITY, snapshot.encryptId(),
                "岗位 encryptId 为空，无法确认身份，跳过");
    }

    /** 2. 岗位内容完整性：岗位名非空且 JD 去空白后 >= 10 字，否则规则与 AI 都无从判断。 */
    private RuleDecision checkEssentialContent(JobSnapshot snapshot) {
        if (snapshot.hasEssentialContent()) {
            return RuleDecision.pass();
        }
        return RuleDecision.reject(RuleReason.MISSING_JOB_CONTENT, snapshot.jobName(),
                "岗位名称或职位描述缺失/过短（岗位名非空且 JD 去空白后 >= 10 字才允许投递），跳过");
    }

    /** 3. 岗位方向：jobDirections 非空时，岗位名需与任一方向互相包含；为空跳过。 */
    private RuleDecision checkDirections(JobSnapshot snapshot) {
        List<String> directions = rules.jobDirections();
        if (directions.isEmpty()) {
            return RuleDecision.pass();
        }
        String jobName = snapshot.jobName();
        for (String direction : directions) {
            if (containsMutualIgnoreCase(jobName, direction)) {
                return RuleDecision.pass();
            }
        }
        return RuleDecision.reject(RuleReason.DIRECTION_NOT_ACCEPTED, jobName,
                "岗位名称「" + nullToEmpty(jobName) + "」不在可接受方向 " + directions + " 内，跳过");
    }

    /** 4. 城市：cities 非空时，岗位城市需与任一城市互相包含；为空跳过。 */
    private RuleDecision checkCities(JobSnapshot snapshot) {
        List<String> cities = rules.cities();
        if (cities.isEmpty()) {
            return RuleDecision.pass();
        }
        String city = snapshot.city();
        for (String expected : cities) {
            if (containsMutualIgnoreCase(city, expected)) {
                return RuleDecision.pass();
            }
        }
        return RuleDecision.reject(RuleReason.CITY_NOT_ACCEPTED, city,
                "岗位城市「" + nullToEmpty(city) + "」不在可接受城市 " + cities + " 内，跳过");
    }

    /**
     * 5. 不可接受城市：blacklistCities 非空时，岗位城市与任一项互相包含即拒绝；为空跳过。
     * <p>旧 {@link RuleSet#cities()} 是「可接受城市」（白名单语义），本规则是「不可接受城市」
     * （黑名单语义），在前者通过后执行；两者可同时存在。</p>
     */
    private RuleDecision checkBlacklistCities(JobSnapshot snapshot) {
        List<String> blacklistCities = rules.blacklistCities();
        if (blacklistCities.isEmpty()) {
            return RuleDecision.pass();
        }
        String city = snapshot.city();
        for (String blocked : blacklistCities) {
            if (containsMutualIgnoreCase(city, blocked)) {
                return RuleDecision.reject(RuleReason.CITY_BLACKLISTED, city,
                        "岗位城市「" + nullToEmpty(city) + "」命中不可接受城市 " + blacklistCities + "，跳过");
            }
        }
        return RuleDecision.pass();
    }

    /**
     * 6. 学历等级比较。
     * <p>
     * 等级：不限=0、高中/中专=1、大专=2、本科=3、硕士/研究生=4、博士=5。
     * <ul>
     *   <li>岗位要求等级 &gt; 用户最高等级，但要求文案含「优先」→ 偏好不拒绝，PASS；</li>
     *   <li>岗位要求等级 &gt; 用户最高等级 → REJECT DEGREE_TOO_HIGH；</li>
     *   <li>岗位要求等级 &lt;= 用户最高等级，但 JD 出现「硕士及以上/博士/研究生学历」等硬性表述
     *       → REVIEW EDUCATION_CONFLICT（结构化学历可能漏配，转人工复核）。</li>
     * </ul>
     */
    private RuleDecision checkDegree(JobSnapshot snapshot) {
        String requirement = snapshot.degree();
        int jobLevel = degreeLevel(requirement);
        int userLevel = degreeLevel(rules.highestDegree());
        if (jobLevel > userLevel) {
            if (requirement != null && requirement.contains(PREFERENCE_KEYWORD)) {
                // 要求「硕士优先」这类偏好，不拒绝
                return RuleDecision.pass();
            }
            return RuleDecision.reject(RuleReason.DEGREE_TOO_HIGH, requirement,
                    "岗位学历要求「" + nullToEmpty(requirement) + "」（等级 " + jobLevel + "）高于最高学历「"
                            + rules.highestDegree() + "」（等级 " + userLevel + "），跳过");
        }
        String conflict = findHardEducationDemand(snapshot.jobDescription());
        if (conflict != null) {
            return RuleDecision.review(RuleReason.EDUCATION_CONFLICT, conflict,
                    "结构化学历「" + nullToEmpty(requirement) + "」未超过最高学历，但 JD 含硬性学历要求「"
                            + conflict + "」，转人工复核");
        }
        return RuleDecision.pass();
    }

    /** 学历文案等级：不限=0、高中/中专=1、大专=2、本科=3、硕士/研究生=4、博士=5；未知/空按 0。 */
    private static int degreeLevel(String degreeText) {
        if (degreeText == null || degreeText.isBlank()) {
            return 0;
        }
        String text = degreeText.trim();
        if (text.contains("博士")) {
            return 5;
        }
        if (text.contains("硕士") || text.contains("研究生")) {
            return 4;
        }
        if (text.contains("本科")) {
            return 3;
        }
        if (text.contains("大专")) {
            return 2;
        }
        if (text.contains("高中") || text.contains("中专")) {
            return 1;
        }
        // 含「不限」或无法识别的写法都按不设学历门槛处理，避免误杀
        return 0;
    }

    /**
     * 从 JD 中找硬性高学历要求表述；「优先/加分」等偏好表述不算冲突。
     * <p>
     * 只看关键词（博士/硕士/研究生）是否出现：后面紧跟「优先/加分/更佳」的是偏好，
     * 其余视为硬性要求（如「硕士及以上学历」「博士」「研究生学历」）。
     */
    private static String findHardEducationDemand(String jobDescription) {
        if (jobDescription == null || jobDescription.isBlank()) {
            return null;
        }
        for (String keyword : HARD_EDUCATION_KEYWORDS) {
            int index = jobDescription.indexOf(keyword);
            while (index >= 0) {
                if (!isSoftEducationMention(jobDescription, index, keyword.length())) {
                    return keyword;
                }
                index = jobDescription.indexOf(keyword, index + keyword.length());
            }
        }
        return null;
    }

    /** 学历关键词后面紧跟「优先/加分」等词时视为偏好而非硬性要求。 */
    private static boolean isSoftEducationMention(String text, int index, int length) {
        int tailEnd = Math.min(text.length(), index + length + SOFT_EDUCATION_LOOKAHEAD);
        String tail = text.substring(index + length, tailEnd);
        for (String soft : SOFT_EDUCATION_SUFFIXES) {
            if (tail.contains(soft)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 6. 黑名单：先白名单（岗位名或公司名包含任一白名单词 → 本步 PASS），
     * 再按 {@link BlacklistMatchMode} 依次匹配职位/公司/招聘者，命中词写入 evidence。
     */
    private RuleDecision checkBlacklist(JobSnapshot snapshot) {
        BlacklistPolicy policy = rules.blacklist() == null ? BlacklistPolicy.empty() : rules.blacklist();
        if (containsAny(snapshot.jobName(), policy.whitelist())
                || containsAny(snapshot.companyName(), policy.whitelist())) {
            return RuleDecision.pass();
        }
        BlacklistMatchMode mode = policy.matchMode() == null ? BlacklistMatchMode.CONTAINS : policy.matchMode();
        String matched = findMatchedTerm(policy.job(), snapshot.jobName(), mode);
        if (matched != null) {
            return RuleDecision.reject(RuleReason.JOB_BLACKLIST, matched,
                    "岗位名称「" + nullToEmpty(snapshot.jobName()) + "」命中职位黑名单关键词「" + matched
                            + "」（" + mode + "），跳过");
        }
        matched = findMatchedTerm(policy.company(), snapshot.companyName(), mode);
        if (matched != null) {
            return RuleDecision.reject(RuleReason.COMPANY_BLACKLIST, matched,
                    "公司「" + nullToEmpty(snapshot.companyName()) + "」命中公司黑名单关键词「" + matched
                            + "」（" + mode + "），跳过");
        }
        matched = findMatchedTerm(policy.recruiter(), snapshot.hrPosition(), mode);
        if (matched != null) {
            return RuleDecision.reject(RuleReason.RECRUITER_BLACKLIST, matched,
                    "招聘者职位「" + nullToEmpty(snapshot.hrPosition()) + "」命中招聘者黑名单关键词「" + matched
                            + "」（" + mode + "），跳过");
        }
        return RuleDecision.pass();
    }

    /**
     * 7. HR 活跃度：仅 rules.filterDeadHr=true 时生效；hrActiveStatus 命中 deadStatus 任一文案即视为不活跃。
     * 取不到活跃度文案时不视为死号（与旧实现一致），避免误杀。
     */
    private RuleDecision checkHrActivity(JobSnapshot snapshot) {
        if (!Boolean.TRUE.equals(rules.filterDeadHr())) {
            return RuleDecision.pass();
        }
        String activeStatus = snapshot.hrActiveStatus();
        List<String> deadStatus = rules.deadStatus();
        if (activeStatus == null || deadStatus.isEmpty()) {
            return RuleDecision.pass();
        }
        for (String status : deadStatus) {
            if (status != null && !status.isBlank() && activeStatus.contains(status)) {
                return RuleDecision.reject(RuleReason.HR_INACTIVE, status,
                        "HR 活跃度「" + activeStatus + "」命中不活跃状态「" + status + "」，跳过");
            }
        }
        return RuleDecision.pass();
    }

    /**
     * 8. 期望薪资：
     * <ul>
     *   <li>期望薪资上下限都未配置（null 或 &lt;=0）→ 跳过（PASS）；</li>
     *   <li>岗位薪资不可解析/面议 → REVIEW（不 REJECT，保留人工判断空间）；</li>
     *   <li>INTERSECT：岗位区间与期望区间无交集 → REJECT；</li>
     *   <li>FLOOR：岗位薪资上限低于期望下限 → REJECT，开得高不拦。</li>
     * </ul>
     */
    private RuleDecision checkSalary(JobSnapshot snapshot) {
        Integer expectedMinValue = rules.expectedSalaryMin();
        Integer expectedMaxValue = rules.expectedSalaryMax();
        boolean hasExpectedMin = expectedMinValue != null && expectedMinValue > 0;
        boolean hasExpectedMax = expectedMaxValue != null && expectedMaxValue > 0;
        if (!hasExpectedMin && !hasExpectedMax) {
            return RuleDecision.pass();
        }
        SalaryStrategy salaryStrategy = rules.salaryStrategy() == null
                ? SalaryStrategy.INTERSECT : rules.salaryStrategy();

        SalaryInfo salary = SalaryParser.parse(snapshot.salary());
        if (salary.negotiable()) {
            return RuleDecision.review(RuleReason.SALARY_UNPARSABLE, snapshot.salary(),
                    "岗位薪资为面议，无法判断是否符合期望，转人工复核");
        }
        if (!salary.parsable() || salary.minK() == null) {
            String errorMessage = salary.errorMessage() == null ? "薪资格式无法识别" : salary.errorMessage();
            return RuleDecision.review(RuleReason.SALARY_UNPARSABLE, snapshot.salary(),
                    "岗位薪资「" + nullToEmpty(snapshot.salary()) + "」无法解析（" + errorMessage
                            + "），无法判断是否符合期望，转人工复核");
        }

        double jobMin = salary.minK();
        Double jobMax = salary.maxK();
        // 岗位上限低于期望下限：给得太少（两种策略都拒绝）
        if (hasExpectedMin && jobMax != null && jobMax < expectedMinValue) {
            return RuleDecision.reject(RuleReason.SALARY_NOT_EXPECTED, snapshot.salary(),
                    "岗位薪资上限 " + formatK(jobMax) + "K 低于期望下限 " + expectedMinValue
                            + "K（策略：" + salaryStrategy + "），跳过");
        }
        if (salaryStrategy == SalaryStrategy.FLOOR) {
            return RuleDecision.pass();
        }
        // INTERSECT：岗位下限高于期望上限，区间无交集
        if (hasExpectedMax && jobMin > expectedMaxValue) {
            return RuleDecision.reject(RuleReason.SALARY_NOT_EXPECTED, snapshot.salary(),
                    "岗位薪资下限 " + formatK(jobMin) + "K 高于期望上限 " + expectedMaxValue
                            + "K（策略：" + salaryStrategy + "），跳过");
        }
        return RuleDecision.pass();
    }

    private void logDecision(RuleDecision decision, JobSnapshot snapshot) {
        String jobName = snapshot == null ? null : snapshot.jobName();
        String companyName = snapshot == null ? null : snapshot.companyName();
        if (decision.outcome() == RuleOutcome.PASS) {
            log.debug("硬规则通过 | 岗位：{} | 公司：{} | 规则：{}@v{}",
                    jobName, companyName, RULE_VERSION, rules.configVersion());
            return;
        }
        log.info("硬规则{} | 原因：{} | 证据：{} | 岗位：{} | 公司：{} | 规则：{}@v{}",
                decision.outcome(), decision.reason(), decision.evidence(),
                jobName, companyName, RULE_VERSION, rules.configVersion());
    }

    /** 白名单匹配：文本包含任一白名单词（始终按「包含」，不受 matchMode 影响）。 */
    private static boolean containsAny(String text, List<String> terms) {
        if (text == null || text.isBlank() || terms == null || terms.isEmpty()) {
            return false;
        }
        for (String term : terms) {
            if (term != null && !term.isBlank() && text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /** 按匹配模式找第一个命中的黑名单词；CONTAINS=包含，EXACT=去空白后完全相等。 */
    private static String findMatchedTerm(List<String> terms, String text, BlacklistMatchMode mode) {
        if (text == null || text.isBlank() || terms == null || terms.isEmpty()) {
            return null;
        }
        String normalizedText = text.trim();
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            boolean hit = mode == BlacklistMatchMode.EXACT
                    ? normalizedText.equals(term.trim())
                    : normalizedText.contains(term);
            if (hit) {
                return term;
            }
        }
        return null;
    }

    /** 方向/城市匹配：忽略大小写的「互相包含」（配置写全称、快照写简称也能对上）。 */
    private static boolean containsMutualIgnoreCase(String text, String keyword) {
        if (text == null || keyword == null) {
            return false;
        }
        String left = text.trim();
        String right = keyword.trim();
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        String lowerLeft = left.toLowerCase(Locale.ROOT);
        String lowerRight = right.toLowerCase(Locale.ROOT);
        return lowerLeft.contains(lowerRight) || lowerRight.contains(lowerLeft);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** K 数值展示：整数不带小数点，小数最多保留两位。 */
    private static String formatK(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}
