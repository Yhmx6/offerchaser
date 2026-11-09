package com.offerchaser.application.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis Mapper 扫描配置。
 * <p>
 * 扫描 {@code com.offerchaser.application.mapper} 包下的所有 Mapper 接口，
 * 使 MyBatis-Plus 自动生成 BaseMapper 的 CRUD 实现。
 */
@Configuration
@MapperScan("com.offerchaser.application.mapper")
public class DataMapperConfig {
}
