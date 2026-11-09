package com.offerchaser.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * boss_option 表：城市/行业/经验/职位类型/薪资/学历/规模/融资阶段的下拉选项。
 * <p>
 * {@code type} 区分选项类别，{@code code} 为 Boss URL 使用的代码，{@code sortOrder} 控制展示顺序。
 */
@Data
@TableName("boss_option")
public class BossOptionEntity {

    /** 主键（数据库自增）。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 选项类型：city / industry / experience / jobType / salary / degree / scale / stage。 */
    private String type;

    /** 选项展示名称。 */
    private String name;

    /** 选项代码（Boss URL 参数）。 */
    private String code;

    /** 展示排序，数值越小越靠前；为空时排在已排序项之后。 */
    private Integer sortOrder;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
