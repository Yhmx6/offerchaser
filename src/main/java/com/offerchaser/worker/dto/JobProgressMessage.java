package com.offerchaser.worker.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE 进度消息 DTO：一次任务事件的所有载荷都收在这个扁平对象里。
 * <p>
 * 基础字段（platform/type/message/current/total/timestamp）用于普通进度提示；
 * {@code type=pipeline} 时再附带阶段、岗位与结果等流程字段。
 * 阶段 B B1 起增加 {@code runId}/{@code seq}/{@code state}：前端据此只更新当前任务、
 * 丢弃旧任务的迟到事件，并按 seq 去重/丢弃乱序事件。
 */
@Data
@NoArgsConstructor
public class JobProgressMessage {

    /** 平台标识（当前只有 boss；字段保留以兼容旧前端的 SSE 渲染） */
    private String platform;
    /** 事件类型：info / progress / success / error / warning / pipeline */
    private String type;
    /** 展示文案 */
    private String message;
    /** 总数量（progress 事件使用） */
    private Integer total;
    /** 已处理数量（progress 事件使用） */
    private Integer current;
    /** 事件创建时间（epoch millis） */
    private Long timestamp;

    /** 产生事件的运行 ID；任务尚未启动时的提示消息可能为空 */
    private String runId;
    /** 服务实例内单调递增事件序号（前端去重 / 丢弃乱序） */
    private Long seq;
    /** 事件产生时的任务状态（STARTING/RUNNING/.../IDLE） */
    private String state;

    /** 流程阶段代码（仅 type=pipeline）：COLLECT/RULE/AI/GATE/CHAT/IDENTITY/TEXT/IMAGE/RESULT */
    private String stage;
    /** 流程阶段中文名 */
    private String stageLabel;
    /** 岗位名称 */
    private String jobName;
    /** 公司名称 */
    private String companyName;
    /** 阶段结果：RUNNING/PASS/REJECT/REVIEW/SKIP/UNKNOWN/FAIL/CONFIRM/DELIVERED/ERROR */
    private String outcome;
    /** 给人看的原因 / 细节 */
    private String detail;

    /** 构造基础事件（时间戳取当前时刻）。 */
    private static JobProgressMessage of(String platform, String type, String message,
                                        Integer current, Integer total) {
        JobProgressMessage event = new JobProgressMessage();
        event.platform = platform;
        event.type = type;
        event.message = message;
        event.current = current;
        event.total = total;
        event.timestamp = System.currentTimeMillis();
        return event;
    }

    /** 进度类消息（带已处理数量/总数）。 */
    public static JobProgressMessage progress(String platform, String message, int current, int total) {
        return of(platform, "progress", message, current, total);
    }

    /** 普通信息消息。 */
    public static JobProgressMessage info(String platform, String message) {
        return of(platform, "info", message, null, null);
    }

    /** 成功消息。 */
    public static JobProgressMessage success(String platform, String message) {
        return of(platform, "success", message, null, null);
    }

    /** 错误消息。 */
    public static JobProgressMessage error(String platform, String message) {
        return of(platform, "error", message, null, null);
    }

    /** 警告消息。 */
    public static JobProgressMessage warning(String platform, String message) {
        return of(platform, "warning", message, null, null);
    }

    /** 投递流程事件（type=pipeline）：前端据此刷新流程节点、当前岗位与最近流水。 */
    public static JobProgressMessage pipeline(String platform, PipelineEvent event) {
        JobProgressMessage message = of(platform, "pipeline", event.detail(), null, null);
        message.stage = event.stage();
        message.stageLabel = event.stageLabel();
        message.jobName = event.jobName();
        message.companyName = event.companyName();
        message.outcome = event.outcome();
        message.detail = event.detail();
        return message;
    }
}
