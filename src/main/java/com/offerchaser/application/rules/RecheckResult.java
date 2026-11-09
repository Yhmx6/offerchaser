package com.offerchaser.application.rules;

/**
 * 重评结果：只新增 job_evaluation，不发送、不改投递状态。
 */
public record RecheckResult(int ruleVersion, int evaluated, int passed, int rejected, int reviewed) {
}
