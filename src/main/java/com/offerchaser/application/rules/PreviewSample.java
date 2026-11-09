package com.offerchaser.application.rules;

/**
 * 规则预览的输入样例。字段与 JobSnapshot 的关键维度对应，可只填关心的部分。
 */
public record PreviewSample(
        String jobName,
        String companyName,
        String salary,
        String degree,
        String experience,
        String city,
        String jd,
        String hrName,
        String hrActiveStatus,
        String encryptId) {
}
