package com.offerchaser.worker.boss;

import com.offerchaser.worker.dto.PipelineEvent;
import com.offerchaser.worker.dto.PipelineSnapshot;
import com.offerchaser.worker.dto.PipelineStage;
import com.offerchaser.worker.dto.PipelineStageStat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投递流程快照：阶段进入数按岗位去重、结果计数、当前岗位/阶段与最近事件。
 */
class BossRunContextPipelineTest {

    @Test
    void pipelineSnapshotAggregatesStagesAndRecentEvents() {
        BossRunContext context = new BossRunContext("run-pipeline");

        context.recordPipeline(PipelineEvent.of(PipelineStage.COLLECT, "岗位A", "公司A", "RUNNING", "开始"));
        context.recordPipeline(PipelineEvent.of(PipelineStage.COLLECT, "岗位A", "公司A", "PASS", "已采集"));
        context.recordPipeline(PipelineEvent.of(PipelineStage.RULE, "岗位A", "公司A", "RUNNING", "规则中"));
        context.recordPipeline(PipelineEvent.of(PipelineStage.RULE, "岗位A", "公司A", "REJECT", "岗位黑名单"));
        // 同一岗位同阶段重复进入不应重复计 entered
        context.recordPipeline(PipelineEvent.of(PipelineStage.RULE, "岗位A", "公司A", "REJECT", "重复事件"));
        // 另一岗位进入同一阶段
        context.recordPipeline(PipelineEvent.of(PipelineStage.COLLECT, "岗位B", "公司B", "PASS", "已采集"));

        PipelineSnapshot snapshot = context.pipelineSnapshot();
        assertEquals("run-pipeline", snapshot.runId());
        assertEquals("岗位B", snapshot.currentJob());
        assertEquals("COLLECT", snapshot.currentStage());
        assertEquals(9, snapshot.stages().size(), "应包含全部 9 个阶段");

        PipelineStageStat collect = stage(snapshot, "COLLECT");
        assertEquals(2, collect.entered(), "两个岗位进入采集阶段");
        assertEquals(2, collect.outcomes().get("PASS"), "两个采集 PASS");
        assertTrue(!collect.outcomes().containsKey("RUNNING"), "RUNNING 不计入结果");

        PipelineStageStat rule = stage(snapshot, "RULE");
        assertEquals(1, rule.entered(), "同一岗位同阶段只计一次 entered");
        assertEquals(2, rule.outcomes().get("REJECT"), "REJECT 结果按事件计数");
        assertEquals(0, stage(snapshot, "AI").entered(), "未进入过的阶段 entered=0");

        assertEquals("岗位B", snapshot.recent().get(0).jobName(), "最近事件应倒序");
        assertEquals(6, snapshot.recent().size());
        assertNotNull(snapshot.updatedAt());
    }

    private static PipelineStageStat stage(PipelineSnapshot snapshot, String stage) {
        return snapshot.stages().stream()
                .filter(item -> stage.equals(item.stage()))
                .findFirst()
                .orElseThrow();
    }
}
