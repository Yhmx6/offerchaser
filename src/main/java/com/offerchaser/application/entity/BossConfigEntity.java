package com.offerchaser.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * boss_config 表：Boss 投递的全局配置（通常只有一行）。
 * <p>
 * 列表型字段（关键词/城市/行业等）在库中存逗号或括号列表文本，由 BossService 负责解析与
 * 名称→代码转换；0/1 整型字段表示开关。
 */
@Data
@TableName("boss_config")
public class BossConfigEntity {

    /** 主键（数据库自增）。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 调试模式：1=开启，0=关闭。 */
    private Integer debugger;

    /** 页面操作等待时间（秒）。 */
    private Integer waitTime;

    /** 搜索关键词列表，如 "[Java,后端]" 或 "Java,后端"。 */
    private String keywords;

    /** 城市（名称或代码，支持列表）。 */
    private String cityCode;

    /** 行业（名称或代码，支持列表）。 */
    private String industry;

    /** 职位类型（名称或代码，单值或列表，只取第一项）。 */
    private String jobType;

    /** 工作经验（名称或代码，支持列表）。 */
    private String experience;

    /** 学历要求（名称或代码，支持列表）。 */
    private String degree;

    /** 薪资区间（名称或代码，支持列表）。 */
    private String salary;

    /** 公司规模（名称或代码，支持列表）。 */
    private String scale;

    /** 融资阶段（名称或代码，支持列表）。 */
    private String stage;

    /** 默认打招呼语（AI 返回为空时兜底使用）。 */
    private String sayHi;

    /** 期望薪资下限。 */
    private Integer expectedSalaryMin;

    /** 期望薪资上限。 */
    private Integer expectedSalaryMax;

    /** 是否启用 AI 生成招呼语：1=启用，0=关闭。 */
    private Integer enableAi;

    /** 是否发送图片简历：1=启用，0=关闭。 */
    private Integer sendImgResume;

    /** 是否允许向已沟通 Boss 再次发送消息：1=启用，0=关闭。 */
    private Integer allowRepeatChat;

    /** 重复沟通消息，每行一条。 */
    private String repeatChatMessages;

    /** 是否过滤不在线 HR：1=启用，0=关闭。 */
    private Integer filterDeadHr;

    /** HR 不在线状态列表（逗号或括号列表）。 */
    private String deadStatus;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
