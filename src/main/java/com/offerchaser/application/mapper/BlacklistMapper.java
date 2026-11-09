package com.offerchaser.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.offerchaser.application.entity.BlacklistEntity;
import org.apache.ibatis.annotations.Mapper;

/** Boss 黑名单表（boss_blacklist）的 MyBatis-Plus Mapper。 */
@Mapper
public interface BlacklistMapper extends BaseMapper<BlacklistEntity> {
}
