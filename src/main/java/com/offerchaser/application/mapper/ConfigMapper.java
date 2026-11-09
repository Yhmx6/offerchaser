package com.offerchaser.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.offerchaser.application.entity.ConfigEntity;
import org.apache.ibatis.annotations.Mapper;

/** 通用键值配置表（config）的 MyBatis-Plus Mapper。 */
@Mapper
public interface ConfigMapper extends BaseMapper<ConfigEntity> {
}
