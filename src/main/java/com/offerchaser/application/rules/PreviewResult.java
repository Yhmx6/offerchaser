package com.offerchaser.application.rules;

/**
 * 规则预览结果：只做判定，不发送、不写评估表。
 *
 * @param index    样例序号（从 0 开始）
 * @param jobName  样例岗位名
 * @param decision PASS / REJECT / REVIEW
 * @param reason   原因码（RuleReason 枚举名）
 * @param evidence 命中证据（黑名单词、薪资原文等）
 * @param detail   中文说明
 */
public record PreviewResult(int index, String jobName, String decision, String reason,
                            String evidence, String detail) {
}
