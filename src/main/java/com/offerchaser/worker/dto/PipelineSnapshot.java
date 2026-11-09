package com.offerchaser.worker.dto;

import java.util.List;

/**
 * 投递流程快照（`GET /api/boss/status` 的 pipeline 字段；SSE 连接建立时也会随 snapshot 下发）。
 *
 * @param runId         任务 runId
 * @param currentJob    当前正在处理的岗位（可为 null）
 * @param currentCompany 当前岗位公司
 * @param currentStage  当前阶段代码（可为 null）
 * @param currentStageLabel 当前阶段中文名
 * @param updatedAt     最近一次事件时间
 * @param stages        各阶段汇总（按流程顺序）
 * @param recent        最近事件（倒序，最多 30 条）
 */
public record PipelineSnapshot(String runId,
                               String currentJob,
                               String currentCompany,
                               String currentStage,
                               String currentStageLabel,
                               Long updatedAt,
                               List<PipelineStageStat> stages,
                               List<PipelineEvent> recent) {
}
