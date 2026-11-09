package com.offerchaser.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ai 表：全局 AI 招呼语配置（单行）。
 * <p>
 * 列名与数据库约定保持一致；读写统一走 MyBatis-Plus 的 BaseMapper。
 */
@Data
@TableName("ai")
public class AiEntity {

    /** 主键（数据库自增）。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 技能/自我介绍文本，作为 AI 生成招呼语的背景。 */
    @TableField("introduce")
    private String introduce;

    /** AI 提示词（生成招呼语的指令模板）。 */
    @TableField("prompt")
    private String prompt;

    /** 创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
