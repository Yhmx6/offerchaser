package com.offerchaser.application.rules;

import java.util.List;

/**
 * 规则配置的可编辑载荷（JSON DTO）。
 * <p>
 * 所有规则字段都在这里显式列出，保存时由 {@link RuleConfigService} 做类型/范围/长度校验，
 * 然后序列化为不可变快照（job_rules = RuleSet）供规则引擎使用。
 * <p>
 * {@code cities} 是旧版「可接受城市」（保留兼容）；{@code blacklistCities} 是新增的
 * 「不可接受城市」，空表示不限。旧版本 JSON 没有 {@code blacklistCities} 时反序列化为 null，
 * 规范化后按空列表处理。
 * </p>
 */
public record RulePayload(
        String highestDegree,
        String salaryStrategy,
        Integer expectedSalaryMin,
        Integer expectedSalaryMax,
        List<String> jobDirections,
        List<String> cities,
        List<String> blacklistCities,
        List<String> workTypes,
        String blacklistMatchMode,
        List<String> blacklistJob,
        List<String> blacklistCompany,
        List<String> blacklistRecruiter,
        List<String> whitelist,
        Boolean filterDeadHr,
        List<String> deadStatus) {

    /**
     * 兼容旧调用方（14 参，不含 blacklistCities）：新字段按空列表处理。
     * <p>JSON 反序列化按字段名工作；这个构造器只是保住直接 new 的旧代码与测试的源码兼容。</p>
     */
    public RulePayload(String highestDegree, String salaryStrategy, Integer expectedSalaryMin,
                       Integer expectedSalaryMax, List<String> jobDirections, List<String> cities,
                       List<String> workTypes, String blacklistMatchMode, List<String> blacklistJob,
                       List<String> blacklistCompany, List<String> blacklistRecruiter,
                       List<String> whitelist, Boolean filterDeadHr, List<String> deadStatus) {
        this(highestDegree, salaryStrategy, expectedSalaryMin, expectedSalaryMax, jobDirections, cities,
                List.of(), workTypes, blacklistMatchMode, blacklistJob, blacklistCompany, blacklistRecruiter,
                whitelist, filterDeadHr, deadStatus);
    }
}
