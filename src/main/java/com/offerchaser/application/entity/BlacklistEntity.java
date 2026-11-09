package com.offerchaser.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * boss_blacklist 表：按类型保存公司 / 招聘者 / 职位黑名单。
 * <p>
 * 字段名与列名一一对应，类型区分由业务层保证。
 */
@Data
@TableName("boss_blacklist")
public class BlacklistEntity {

    /** 主键（数据库自增）。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 黑名单类型：company / recruiter / job。 */
    private String type;

    /** 黑名单值（公司名、招聘者名或职位名）。 */
    private String value;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
