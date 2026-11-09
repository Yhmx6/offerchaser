package com.offerchaser.application.init;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 全新库默认模型服务地址与模型名。
 *
 * <p>只补 BASE_URL 和 MODEL，不写 API_KEY：用户第一次使用时只需要填写自己的 API Key。
 * 已有配置不会被覆盖；API_KEY 始终由用户自行填写。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
@RequiredArgsConstructor
public class DefaultModelConfigInitializer implements CommandLineRunner {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-flash";

    private final JdbcTemplate jdbc;

    @Override
    public void run(String... args) {
        if (!configTableExists()) {
            return;
        }
        insertIfMissing("BASE_URL", DEFAULT_BASE_URL,
                "AI 接口地址，需兼容 OpenAI 协议");
        insertIfMissing("MODEL", DEFAULT_MODEL,
                "请求体中使用的模型名");
    }

    private void insertIfMissing(String key, String value, String description) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM config WHERE config_key = ?", Integer.class, key);
            if (count != null && count > 0) {
                return;
            }
            jdbc.update("""
                    INSERT INTO config (config_key, config_value, config_type, category, description,
                                        created_at, updated_at)
                    VALUES (?, ?, 'string', 'ai', ?, datetime('now'), datetime('now'))
                    """, key, value, description);
            log.info("已写入默认模型配置 | key={} | value={}", key, value);
        } catch (Exception error) {
            log.warn("写入默认模型配置失败 | key={} | {}", key, error.getMessage());
        }
    }

    private boolean configTableExists() {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='config'",
                    Integer.class);
            return count != null && count > 0;
        } catch (Exception error) {
            log.warn("检查 config 表失败：{}", error.getMessage());
            return false;
        }
    }
}
