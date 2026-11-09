package com.offerchaser.application.delivery;

import java.util.ArrayList;
import java.util.List;

/**
 * 黑名单策略：匹配模式 + 分类关键词 + 白名单（阶段 B B2 可配置）。
 * <p>
 * 构造时做不可变拷贝：{@code null} 列表按空列表处理，{@code null}/空白项丢弃、关键词去首尾空白；
 * {@code matchMode} 为 {@code null} 时按 {@link BlacklistMatchMode#CONTAINS}。
 * <p>
 * 判定语义（见 {@link JobRuleEngine}）：
 * <ol>
 *   <li>白名单优先：岗位名或公司名包含任一白名单词 → 本步直接通过；</li>
 *   <li>再按 matchMode 依次匹配 job（岗位名）、company（公司名）、recruiter（HR 职位）；</li>
 *   <li>命中即 REJECT，并把命中的词作为 evidence 返回。</li>
 * </ol>
 */
public record BlacklistPolicy(
        BlacklistMatchMode matchMode,
        List<String> job,
        List<String> company,
        List<String> recruiter,
        List<String> whitelist) {

    public BlacklistPolicy {
        matchMode = matchMode == null ? BlacklistMatchMode.CONTAINS : matchMode;
        job = copyOf(job);
        company = copyOf(company);
        recruiter = copyOf(recruiter);
        whitelist = copyOf(whitelist);
    }

    /** 空策略：包含匹配、四类名单都为空。 */
    public static BlacklistPolicy empty() {
        return new BlacklistPolicy(BlacklistMatchMode.CONTAINS, List.of(), List.of(), List.of(), List.of());
    }

    private static List<String> copyOf(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<String> copy = new ArrayList<>(source.size());
        for (String term : source) {
            if (term != null && !term.isBlank()) {
                copy.add(term.trim());
            }
        }
        return List.copyOf(copy);
    }
}
