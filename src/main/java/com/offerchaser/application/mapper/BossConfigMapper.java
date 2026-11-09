package com.offerchaser.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.offerchaser.application.entity.BossConfigEntity;
import org.apache.ibatis.annotations.Mapper;

/** Boss 全局配置表（boss_config）的 MyBatis-Plus Mapper。 */
@Mapper
public interface BossConfigMapper extends BaseMapper<BossConfigEntity> {
}
