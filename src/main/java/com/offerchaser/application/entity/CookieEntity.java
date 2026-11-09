package com.offerchaser.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * cookie 表：按平台保存登录 Cookie 字符串。
 * <p>
 * 当前仅保留 boss 平台；启动时由 CookieSeedInitializer 确保存在一条可更新的种子记录。
 */
@Data
@TableName("cookie")
public class CookieEntity {

    /** 主键（数据库自增）。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 平台标识（boss）。 */
    @TableField("platform")
    private String platform;

    /** Cookie 字符串。 */
    @TableField("cookie_value")
    private String cookieValue;

    /** 备注（如 seed 表示种子记录）。 */
    @TableField("remark")
    private String remark;

    /** 创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
