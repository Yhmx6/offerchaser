package com.offerchaser.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.offerchaser.application.entity.BossJobDataEntity;
import org.apache.ibatis.annotations.Mapper;

/** 岗位采集快照表（boss_data）的 MyBatis-Plus Mapper。 */
@Mapper
public interface BossJobDataMapper extends BaseMapper<BossJobDataEntity> {
}
