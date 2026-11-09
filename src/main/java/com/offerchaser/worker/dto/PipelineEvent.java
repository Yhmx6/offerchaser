package com.offerchaser.worker.dto;

/**
 * 投递流程事件（阶段 + 岗位 + 结果），用于管理台实时可视化。
 *
 * @param timestamp   事件时间（毫秒）
 * @param stage       阶段代码（PipelineStage.name()）
 * @param stageLabel  阶段中文名
 * @param jobName     岗位名
 * @param companyName 公司名
 * @param outcome     RUNNING / PASS / REJECT / REVIEW / SKIP / UNKNOWN / FAIL / CONFIRM / DELIVERED / ERROR
 * @param detail      给人看的原因/细节
 */
public record PipelineEvent(long timestamp,
                            String stage,
                            String stageLabel,
                            String jobName,
                            String companyName,
                            String outcome,
                            String detail) {

    public static PipelineEvent of(PipelineStage stage, String jobName, String companyName,
                                   String outcome, String detail) {
        return new PipelineEvent(System.currentTimeMillis(), stage.name(), stage.getLabel(),
                jobName, companyName, outcome, detail);
    }

    public boolean isRunning() {
        return "RUNNING".equals(outcome);
    }
}
