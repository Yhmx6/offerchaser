package com.offerchaser.application.init;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 补齐 BOSS 下拉选项字典（城市/行业/经验/学历/薪资/规模/融资阶段/职位类型）。
 *
 * <p>这些是通用选项，不含用户个人数据。老库通常已经有完整字典；全新库或从旧模板创建的库
 * 可能只有“不限/全国”占位，导致 AI 建模板和模板编辑器里城市/行业等项目全部显示未匹配。
 * 因此按 type + code 去重，只补缺失项，不覆盖用户已有项。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@RequiredArgsConstructor
public class DefaultBossOptionsInitializer implements CommandLineRunner {

    private static final String RESOURCE = "defaults/options.json";
    private static final String INSERT_SQL = """
            INSERT INTO boss_option (type, name, code, sort_order, created_at, updated_at)
            VALUES (?, ?, ?, ?, datetime('now'), datetime('now'))
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) throws Exception {
        if (!bossOptionTableExists()) {
            return;
        }
        Set<String> existing = new HashSet<>();
        jdbc.query("SELECT type, code FROM boss_option", rs -> {
            existing.add(rs.getString("type") + "\u0000" + rs.getString("code"));
        });

        List<OptionSeed> seeds;
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            seeds = objectMapper.readValue(input, new TypeReference<>() {
            });
        } catch (Exception error) {
            log.warn("读取默认 BOSS 选项失败，不阻断启动：{}", error.getMessage());
            return;
        }

        int inserted = 0;
        for (OptionSeed seed : seeds) {
            if (seed.type() == null || seed.name() == null || seed.code() == null) {
                continue;
            }
            String key = seed.type() + "\u0000" + seed.code();
            if (existing.contains(key)) {
                continue;
            }
            try {
                jdbc.update(INSERT_SQL, seed.type(), seed.name(), seed.code(),
                        seed.sortOrder() == null ? 0 : seed.sortOrder());
                existing.add(key);
                inserted++;
            } catch (Exception error) {
                log.warn("补齐 BOSS 选项失败 | type={} code={} | {}", seed.type(), seed.code(), error.getMessage());
            }
        }
        if (inserted > 0) {
            log.info("已补齐 BOSS 下拉选项 {} 条", inserted);
        }
    }

    private boolean bossOptionTableExists() {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='boss_option'",
                    Integer.class);
            return count != null && count > 0;
        } catch (Exception error) {
            log.warn("检查 boss_option 表失败：{}", error.getMessage());
            return false;
        }
    }

    /** options.json 的单条结构。 */
    public record OptionSeed(String type, String name, String code, Integer sortOrder) {
    }
}
