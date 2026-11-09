package com.offerchaser.application.init;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首次启动时写入随包发布的默认页面配置（仅 BOSS 配置、AI 内容、Boss 黑名单）。
 * <p>
 * 只对“全新库”生效：三张目标表只要有一张已有数据即视为老库，保留用户设置、不写默认值。
 * 无论是否写入，都会在 default_page_config_seed 留下标记，避免用户主动清空后被默认值灌回。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class DefaultPageConfigInitializer implements CommandLineRunner {

    /** 默认页面配置覆盖的表（与 defaults/page-config.json 的 key 一一对应）。 */
    private static final List<String> CONFIG_TABLES = List.of(
            "boss_config", "ai", "boss_blacklist");
    private static final Set<String> CONFIG_TABLE_SET = Set.copyOf(CONFIG_TABLES);
    /** 只允许 snake_case 列名进入动态 SQL，避免 JSON 内容拼出意外语句。 */
    private static final String COLUMN_NAME_PATTERN = "[a-z_]+";
    private static final String DEFAULT_PAGE_CONFIG_RESOURCE = "defaults/page-config.json";
    private static final String SEED_MARKER_TABLE = "default_page_config_seed";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Transactional
    @Override
    public void run(String... args) throws Exception {
        ensureSeedMarkerTable();
        if (seedMarkerPresent()) {
            return;
        }
        if (anyConfigTableHasData()) {
            log.info("检测到已有页面配置，保留用户设置，不加载项目默认值");
        } else {
            importBundledDefaults();
        }
        // 即使本次跳过写入也要落标记：用户主动清空黑名单后，重启不能被默认值覆盖
        jdbc.update("INSERT INTO default_page_config_seed (id) VALUES (1)");
    }

    private void ensureSeedMarkerTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS default_page_config_seed (id INTEGER PRIMARY KEY)");
    }

    private boolean seedMarkerPresent() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + SEED_MARKER_TABLE, Integer.class);
        return count != null && count > 0;
    }

    /** 任一张目标表已有数据，即认为不是全新库。 */
    private boolean anyConfigTableHasData() {
        for (String table : CONFIG_TABLES) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            if (count != null && count > 0) {
                return true;
            }
        }
        return false;
    }

    private void importBundledDefaults() throws Exception {
        Map<String, List<Map<String, Object>>> defaults = readBundledDefaults();
        if (!defaults.keySet().equals(CONFIG_TABLE_SET)) {
            throw new IllegalStateException("默认页面配置的表范围不正确");
        }
        for (String table : CONFIG_TABLES) {
            Set<String> columns = existingColumns(table);
            for (Map<String, Object> row : defaults.get(table)) {
                insertDefaultRow(table, columns, row);
            }
        }
        log.info("已加载默认页面配置（BOSS配置、AI内容、Boss黑名单）");
    }

    private Map<String, List<Map<String, Object>>> readBundledDefaults() throws Exception {
        try (var input = new ClassPathResource(DEFAULT_PAGE_CONFIG_RESOURCE).getInputStream()) {
            return objectMapper.readValue(input, new TypeReference<>() {
            });
        }
    }

    private Set<String> existingColumns(String table) {
        List<String> names = jdbc.query("PRAGMA table_info(" + table + ")",
                (resultSet, index) -> resultSet.getString("name"));
        return Set.copyOf(names);
    }

    private void insertDefaultRow(String table, Set<String> existingColumns, Map<String, Object> row) {
        boolean usable = !row.isEmpty()
                && existingColumns.containsAll(row.keySet())
                && row.keySet().stream().allMatch(column -> column.matches(COLUMN_NAME_PATTERN));
        if (!usable) {
            throw new IllegalStateException("默认页面配置字段不正确: " + table);
        }
        List<String> columns = List.copyOf(row.keySet());
        String placeholders = String.join(",", Collections.nCopies(columns.size(), "?"));
        Object[] values = columns.stream().map(row::get).toArray();
        jdbc.update("INSERT INTO " + table + " (" + String.join(",", columns)
                + ") VALUES (" + placeholders + ")", values);
    }
}
