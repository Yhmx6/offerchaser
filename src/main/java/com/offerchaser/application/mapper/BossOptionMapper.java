package com.offerchaser.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.offerchaser.application.entity.BossOptionEntity;
import org.apache.ibatis.annotations.Mapper;

/** 下拉选项表（boss_option）的 MyBatis-Plus Mapper。 */
@Mapper
public interface BossOptionMapper extends BaseMapper<BossOptionEntity> {
}
