package com.offerchaser.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.offerchaser.application.entity.BossIndustryEntity;
import org.apache.ibatis.annotations.Mapper;

/** Boss 行业对照表（boss_industry）的 MyBatis-Plus Mapper。 */
@Mapper
public interface BossIndustryMapper extends BaseMapper<BossIndustryEntity> {
}
