package com.offerchaser.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * boss_data 表：BIOS 岗位采集快照（按 encrypt_id/encrypt_user_id 去重）。
 * <p>
 * 采集字段与投递状态分开维护：采集更新不得覆盖投递终态；B4 的 first_seen_at / last_detail_at /
 * snapshot_hash 用于统计新增岗位与判断详情是否变化，旧数据这三列为 null。
 */
@Data
@TableName("boss_data")
public class BossJobDataEntity {

    /** 主键（数据库自增）。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "encrypt_id")
    private String encryptId;

    @TableField(value = "encrypt_user_id")
    private String encryptUserId;

    @TableField(value = "company_name")
    private String companyName;

    @TableField(value = "job_name")
    private String jobName;

    @TableField(value = "salary")
    private String salary;

    @TableField(value = "location")
    private String location;

    @TableField(value = "experience")
    private String experience;

    @TableField(value = "degree")
    private String degree;

    @TableField(value = "hr_name")
    private String hrName;

    @TableField(value = "hr_position")
    private String hrPosition;

    @TableField(value = "hr_active_status")
    private String hrActiveStatus;

    /** 投递状态：未投递 / 已投递 / 已过滤 / 投递失败 / 状态未知。 */
    @TableField(value = "delivery_status")
    private String deliveryStatus;

    @TableField(value = "job_description")
    private String jobDescription;

    @TableField(value = "job_url")
    private String jobUrl;

    @TableField(value = "recruitment_status")
    private String recruitmentStatus;

    @TableField(value = "company_address")
    private String companyAddress;

    @TableField(value = "industry")
    private String industry;

    @TableField(value = "introduce")
    private String introduce;

    @TableField(value = "financing_stage")
    private String financingStage;

    @TableField(value = "company_scale")
    private String companyScale;

    @TableField(value = "created_at")
    private LocalDateTime createdAt;

    @TableField(value = "updated_at")
    private LocalDateTime updatedAt;

    /** 首次发现时间；迁移前旧记录为 null（统计时单独记为 legacy）。 */
    @TableField(value = "first_seen_at")
    private LocalDateTime firstSeenAt;

    /** 最近一次抓取详情的时间。 */
    @TableField(value = "last_detail_at")
    private LocalDateTime lastDetailAt;

    /** 最近一次详情快照哈希，用于判断岗位内容是否变化。 */
    @TableField(value = "snapshot_hash")
    private String snapshotHash;
}

