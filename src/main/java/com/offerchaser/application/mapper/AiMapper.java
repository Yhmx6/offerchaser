package com.offerchaser.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.offerchaser.application.entity.AiEntity;
import org.apache.ibatis.annotations.Mapper;

/** AI 配置表（ai）的 MyBatis-Plus Mapper。 */
@Mapper
public interface AiMapper extends BaseMapper<AiEntity> {
}
