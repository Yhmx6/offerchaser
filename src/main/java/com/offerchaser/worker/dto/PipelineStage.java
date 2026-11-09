package com.offerchaser.worker.dto;

/**
 * 投递流程阶段（管理台「投递流程」可视化用）。
 * <p>顺序即流程顺序；前端按 name() 识别、按 label() 显示。</p>
 */
public enum PipelineStage {
    COLLECT("采集岗位"),
    RULE("规则筛选"),
    AI("AI匹配"),
    GATE("准入检查"),
    CHAT("沟通入口"),
    IDENTITY("身份核对"),
    TEXT("文字发送"),
    IMAGE("图片简历"),
    RESULT("投递结果");

    private final String label;

    PipelineStage(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
