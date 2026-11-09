package com.offerchaser.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.offerchaser.application.entity.CookieEntity;
import org.apache.ibatis.annotations.Mapper;

/** Cookie 表（cookie）的 MyBatis-Plus Mapper；由 DataMapperConfig 的 @MapperScan 注册。 */
@Mapper
public interface CookieMapper extends BaseMapper<CookieEntity> {
}
