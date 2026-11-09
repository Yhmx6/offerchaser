package com.offerchaser.application.delivery;

import com.offerchaser.worker.boss.BossConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 一轮投递固定使用的规则快照（阶段 B B2）。
 * <p>
 * 规则版本化后，{@link JobRuleEngine} 只读取本 record，不再直接读 BossConfig；
 * 运行中修改管理台配置只影响下一轮（由 RuleConfigService 生成新版本）。
 * <p>
 * 字段说明：
 * <ul>
 *   <li>{@code configVersion}：rule_config_version.version；0 表示默认/未持久化；</li>
 *   <li>{@code sourceHash}：规则 JSON 的 sha256 hex，可空；</li>
 *   <li>{@code highestDegree}：用户最高学历（默认「本科」）；</li>
 *   <li>{@code salaryStrategy}：期望薪资策略；</li>
 *   <li>{@code expectedSalaryMin}/{@code expectedSalaryMax}：期望月薪 K；null 或 &lt;=0 视为未配置；</li>
 *   <li>{@code jobDirections}/{@code cities}：旧版可接受方向/城市，空=不限（保留兼容）；</li>
 *   <li>{@code blacklistCities}：不可接受城市，空=不限；岗位城市与任一项互相包含即拒绝；</li>
 *   <li>{@code workTypes}：保留字段（当前快照无工作类型，不参与判定）；</li>
 *   <li>{@code blacklist}：黑名单/白名单策略；</li>
 *   <li>{@code filterDeadHr}/{@code deadStatus}：沿用旧 BossConfig 的 HR 活跃度过滤语义；
 *       filterDeadHr 为 null 视为不过滤。</li>
 * </ul>
 * 构造时做不可变拷贝：null 列表按空处理、空白项丢弃；null 的 highestDegree 按「本科」、
 * null 的 salaryStrategy 按 INTERSECT、null 的 blacklist 按 {@link BlacklistPolicy#empty()}。
 */
public record RuleSet(
        int configVersion,
        String sourceHash,
        String highestDegree,
        SalaryStrategy salaryStrategy,
        Integer expectedSalaryMin,
        Integer expectedSalaryMax,
        List<String> jobDirections,
        List<String> cities,
        List<String> blacklistCities,
        List<String> workTypes,
        BlacklistPolicy blacklist,
        Boolean filterDeadHr,
        List<String> deadStatus) {

    /** 用户最高学历默认值。 */
    public static final String DEFAULT_HIGHEST_DEGREE = "本科";

    /**
     * 兼容旧调用方（12 参，不含 blacklistCities）：不可接受城市按空列表处理。
     */
    public RuleSet(int configVersion, String sourceHash, String highestDegree,
                   SalaryStrategy salaryStrategy, Integer expectedSalaryMin, Integer expectedSalaryMax,
                   List<String> jobDirections, List<String> cities, List<String> workTypes,
                   BlacklistPolicy blacklist, Boolean filterDeadHr, List<String> deadStatus) {
        this(configVersion, sourceHash, highestDegree, salaryStrategy, expectedSalaryMin, expectedSalaryMax,
                jobDirections, cities, List.of(), workTypes, blacklist, filterDeadHr, deadStatus);
    }

    public RuleSet {
        highestDegree = highestDegree == null || highestDegree.isBlank()
                ? DEFAULT_HIGHEST_DEGREE : highestDegree.trim();
        salaryStrategy = salaryStrategy == null ? SalaryStrategy.INTERSECT : salaryStrategy;
        jobDirections = copyOf(jobDirections);
        cities = copyOf(cities);
        blacklistCities = copyOf(blacklistCities);
        workTypes = copyOf(workTypes);
        blacklist = blacklist == null ? BlacklistPolicy.empty() : blacklist;
        // filterDeadHr 允许为 null（=不启用 HR 活跃过滤），保持原值
        deadStatus = copyOf(deadStatus);
    }

    /**
     * 从旧 BossConfig + 黑名单表构造默认规则（版本 0，未持久化）。
     * <p>
     * 用于 A+ 旧调用方兼容：highestDegree=本科、方向/城市/不可接受城市/白名单为空、matchMode=CONTAINS。
     */
    public static RuleSet defaultFrom(BossConfig config,
                                      Set<String> companies,
                                      Set<String> recruiters,
                                      Set<String> jobs,
                                      SalaryStrategy strategy) {
        Integer expectedMin = null;
        Integer expectedMax = null;
        Boolean filterDeadHr = null;
        List<String> deadStatus = List.of();
        if (config != null) {
            List<Integer> expected = config.getExpectedSalary();
            if (expected != null) {
                if (!expected.isEmpty()) {
                    expectedMin = expected.get(0);
                }
                if (expected.size() > 1) {
                    expectedMax = expected.get(1);
                }
            }
            filterDeadHr = config.getFilterDeadHR();
            deadStatus = config.getDeadStatus() == null ? List.of() : config.getDeadStatus();
        }
        BlacklistPolicy blacklist = new BlacklistPolicy(
                BlacklistMatchMode.CONTAINS,
                fromSet(jobs),
                fromSet(companies),
                fromSet(recruiters),
                List.of());
        return new RuleSet(0, null, DEFAULT_HIGHEST_DEGREE, strategy,
                expectedMin, expectedMax,
                List.of(), List.of(), List.of(), List.of(),
                blacklist, filterDeadHr, deadStatus);
    }

    private static List<String> copyOf(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<String> copy = new ArrayList<>(source.size());
        for (String value : source) {
            if (value != null && !value.isBlank()) {
                copy.add(value.trim());
            }
        }
        return List.copyOf(copy);
    }

    /** 黑名单 Set 转稳定顺序的不可变 List：过滤 null/空白、排序，保证相同数据得到相同规则行为。 */
    private static List<String> fromSet(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(source.size());
        for (String value : source) {
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        values.sort(String::compareTo);
        return List.copyOf(values);
    }
}
