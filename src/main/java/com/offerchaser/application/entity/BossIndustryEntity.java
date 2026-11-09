package com.offerchaser.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * boss_industry 表：行业名称与 Boss 行业代码的对照。
 */
@Data
@TableName("boss_industry")
public class BossIndustryEntity {

    /** 主键（数据库自增）。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 行业名称（展示与配置使用）。 */
    private String name;

    /** Boss 行业代码（URL 筛选使用）。 */
    private Integer code;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
