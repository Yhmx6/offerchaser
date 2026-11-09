package com.offerchaser.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * config 表：通用键值配置（键、值、类型、分类与描述）。
 * <p>
 * 读写由 ConfigService 统一封装；列名与历史数据库保持一致。
 */
@Data
@TableName("config")
public class ConfigEntity {

    /** 主键（数据库自增）。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 配置键（业务内唯一）。 */
    @TableField("config_key")
    private String configKey;

    /** 配置值（按 configType 解释）。 */
    @TableField("config_value")
    private String configValue;

    /** 配置类型：字符串/数字/布尔等，供前端编辑器使用。 */
    @TableField("config_type")
    private String configType;

    /** 配置分类，便于分组展示。 */
    @TableField("category")
    private String category;

    /** 配置说明。 */
    @TableField("description")
    private String description;

    /** 创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
