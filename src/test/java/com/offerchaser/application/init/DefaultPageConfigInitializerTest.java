package com.offerchaser.application.init;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认页面配置种子（D1 基线）：
 * 全新库只写入随包发布的 BOSS 相关默认值（boss_config / ai / boss_blacklist），
 * 老库或已写过种子的库一律保留用户设置、不重复灌入；cookie 会话不受影响。
 * <p>
 * 断言以 {@code defaults/page-config.json} 为唯一真源：避免在测试里再抄一份默认值，
 * 同时逐列校验写入内容，而不是只校验非空/包含。
 */
@DisplayName("DefaultPageConfigInitializer：默认页面配置种子")
class DefaultPageConfigInitializerTest {

    private static final String DEFAULT_RESOURCE = "defaults/page-config.json";
    private static final String SEED_TABLE = "default_page_config_seed";
    private static final Set<String> EXPECTED_TABLES = Set.of("boss_config", "ai", "boss_blacklist");
    private static final int EXPECTED_BLACKLIST_COUNT = 0;
    private static final int EXPECTED_PROMPT_PLACEHOLDERS = 0;

    @TempDir
    Path directory;

    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private Map<String, List<Map<String, Object>>> defaults;
    private DefaultPageConfigInitializer initializer;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper();
        defaults = loadDefaults();
        assertTrue(defaults.keySet().containsAll(EXPECTED_TABLES),
                "默认页面配置必须覆盖 BOSS 相关三张表");
        assertTrue(EXPECTED_TABLES.containsAll(defaults.keySet()),
                "默认页面配置不得写入 BOSS 以外的页面配置表");

        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + directory.resolve("seed.db"));
        jdbc = new JdbcTemplate(source);
        createPageConfigTablesFromFixture();
        jdbc.execute("CREATE TABLE cookie (value TEXT)");
        jdbc.update("INSERT INTO cookie VALUES ('existing-session')");

        initializer = new DefaultPageConfigInitializer(jdbc, mapper);
    }

    // ==================== 全新库 ====================

    @Test
    @DisplayName("全新库：只写入 BOSS 默认页面配置，逐列值来自资源真源，Cookie 会话保留")
    void seedsFreshDatabaseWithBossOnlyDefaults() throws Exception {
        initializer.run();

        assertEquals(1, count("SELECT COUNT(*) FROM " + SEED_TABLE),
                "首次写入后应留下且仅留下 1 行种子标记");
        assertEquals(1, count("SELECT COUNT(*) FROM boss_config"), "默认 BOSS 配置应为 1 行");
        assertEquals(1, count("SELECT COUNT(*) FROM ai"), "默认 AI 配置应为 1 行");
        assertEquals(EXPECTED_BLACKLIST_COUNT, count("SELECT COUNT(*) FROM boss_blacklist"),
                "默认不再携带来自上游的私人黑名单");

        // boss_config：逐列与资源 JSON 对比（不是只测 city/keywords 两个字段）
        Map<String, Object> bossConfig = defaults.get("boss_config").getFirst();
        assertRowEqualsFixture("boss_config", 1, bossConfig);
        assertEquals("", text("SELECT city_code FROM boss_config"), "默认不携带上游城市");
        assertEquals("[]", text("SELECT keywords FROM boss_config"), "默认不携带上游关键词");
        assertEquals(0, count("SELECT debugger FROM boss_config"));
        assertEquals(3, count("SELECT wait_time FROM boss_config"));
        assertEquals(0, count("SELECT enable_ai FROM boss_config"), "默认关闭 AI，等待用户配置");
        assertEquals(0, count("SELECT send_img_resume FROM boss_config"));
        assertEquals(0, count("SELECT filter_dead_hr FROM boss_config"));
        assertEquals(0, count("SELECT allow_repeat_chat FROM boss_config"),
                "默认重复沟通开关必须关闭");
        assertNull(jdbc.queryForObject("SELECT expected_salary_min FROM boss_config", Integer.class));
        assertNull(jdbc.queryForObject("SELECT expected_salary_max FROM boss_config", Integer.class));
        String repeatMessages = text("SELECT repeat_chat_messages FROM boss_config");
        assertNotNull(repeatMessages, "默认应提供非个人的重复沟通消息");
        assertTrue(repeatMessages.contains("招聘"), "默认重复沟通消息应为通用文案");
        assertNotNull(text("SELECT say_hi FROM boss_config"), "默认应提供通用招呼语");

        // ai：默认不携带上游个人介绍和 AI 提示词
        Map<String, Object> ai = defaults.get("ai").getFirst();
        assertRowEqualsFixture("ai", 1, ai);
        String prompt = text("SELECT prompt FROM ai");
        assertEquals(EXPECTED_PROMPT_PLACEHOLDERS, prompt.split("%s", -1).length - 1,
                "默认提示词改用命名变量，不再使用旧版 %s 顺序占位符");
        assertTrue(prompt.contains("{{job_name}}") && prompt.contains("{{job_description}}"),
                "默认提示词应保留命名变量");
        assertEquals("", text("SELECT introduce FROM ai"), "默认不携带上游个人介绍");

        // boss_blacklist：默认空，不携带上游作者的私人公司/岗位/猎头黑名单
        List<String> actualBlacklist = jdbc.query(
                "SELECT type, value FROM boss_blacklist ORDER BY id",
                (rs, index) -> rs.getString("type") + "|" + rs.getString("value"));
        assertTrue(actualBlacklist.isEmpty(), "默认黑名单必须为空");
        assertTrue(defaults.get("boss_blacklist").isEmpty(), "资源文件不得携带默认黑名单");

        // 不许动已有会话（cookie）
        assertEquals("existing-session", text("SELECT value FROM cookie"));
    }

    @Test
    @DisplayName("用户修改/清空配置后重启：不重复灌默认值，保留用户编辑")
    void doesNotReseedAfterUserEdits() throws Exception {
        initializer.run();
        jdbc.update("UPDATE boss_config SET city_code = '用户修改的城市', " +
                "allow_repeat_chat = 1, repeat_chat_messages = '用户编辑的消息'");
        jdbc.update("DELETE FROM boss_blacklist");

        // 模拟重启：同一库、新 initializer 实例
        new DefaultPageConfigInitializer(jdbc, mapper).run();

        assertEquals("用户修改的城市", text("SELECT city_code FROM boss_config"));
        assertEquals(1, count("SELECT allow_repeat_chat FROM boss_config"));
        assertEquals("用户编辑的消息", text("SELECT repeat_chat_messages FROM boss_config"));
        assertEquals(0, count("SELECT COUNT(*) FROM boss_blacklist"),
                "用户主动清空的黑名单不得在重启后被默认值覆盖");
        assertEquals(1, count("SELECT COUNT(*) FROM boss_config"), "不得重复插入 BOSS 配置");
        assertEquals(1, count("SELECT COUNT(*) FROM ai"), "不得重复插入 AI 配置");
        assertEquals(1, count("SELECT COUNT(*) FROM " + SEED_TABLE));
    }

    // ==================== 老库 / 已有数据 ====================

    @Test
    @DisplayName("老库已有 AI 配置：跳过默认值，保留用户 AI 内容且不写其它表")
    void existingPageConfigIsPreservedAndDefaultsSkipped() throws Exception {
        jdbc.update("INSERT INTO ai (introduce, prompt) VALUES ('自己的技能', '自己的提示词')");

        initializer.run();

        assertEquals("自己的提示词", text("SELECT prompt FROM ai"));
        assertEquals("自己的技能", text("SELECT introduce FROM ai"));
        assertEquals(0, count("SELECT COUNT(*) FROM boss_config"),
                "老库检测到已有 AI 配置后不得再写默认 BOSS 配置");
        assertEquals(0, count("SELECT COUNT(*) FROM boss_blacklist"),
                "老库检测到已有 AI 配置后不得再写默认黑名单");
        assertEquals(1, count("SELECT COUNT(*) FROM " + SEED_TABLE),
                "即使未写默认值也必须落种子标记，避免下次启动再判断");
    }

    @ParameterizedTest(name = "已有数据表 {0} 即视为老库，其它表不写默认值")
    @ValueSource(strings = {"boss_config", "ai", "boss_blacklist"})
    @DisplayName("任一张页面配置表已有数据即视为老库")
    void anyExistingTableMeansLegacyDatabase(String table) throws Exception {
        switch (table) {
            case "boss_config" -> jdbc.update("INSERT INTO boss_config (city_code) VALUES ('用户城市')");
            case "ai" -> jdbc.update("INSERT INTO ai (introduce) VALUES ('用户介绍')");
            case "boss_blacklist" -> jdbc.update("INSERT INTO boss_blacklist (type, value) " +
                    "VALUES ('company', '用户公司')");
            default -> throw new IllegalArgumentException(table);
        }

        initializer.run();

        assertEquals(1, count("SELECT COUNT(*) FROM " + table), "已有数据表只保留用户数据");
        for (String other : EXPECTED_TABLES.stream().filter(item -> !item.equals(table)).toList()) {
            assertEquals(0, count("SELECT COUNT(*) FROM " + other),
                    "检测到 " + table + " 有数据后 " + other + " 不得写入默认值");
        }
        assertEquals(1, count("SELECT COUNT(*) FROM " + SEED_TABLE));
    }

    @Test
    @DisplayName("已写种子标记后即使用户清空全部配置，也不再写入默认值")
    void seedMarkerShortCircuitsEvenWhenTablesAreEmpty() throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + SEED_TABLE + " (id INTEGER PRIMARY KEY)");
        jdbc.update("INSERT INTO " + SEED_TABLE + " (id) VALUES (1)");

        initializer.run();

        for (String table : EXPECTED_TABLES) {
            assertEquals(0, count("SELECT COUNT(*) FROM " + table),
                    "已有种子标记时不得再写默认值：" + table);
        }
        assertEquals(1, count("SELECT COUNT(*) FROM " + SEED_TABLE), "种子标记不得重复");
    }

    // ==================== 工具 ====================

    private Map<String, List<Map<String, Object>>> loadDefaults() throws Exception {
        try (var input = new ClassPathResource(DEFAULT_RESOURCE).getInputStream()) {
            return mapper.readValue(input, new TypeReference<>() {
            });
        }
    }

    /** 按资源 JSON 的列集合建表（与 main 的历史建表方式一致，列名/类型由 JSON 决定）。 */
    private void createPageConfigTablesFromFixture() {
        for (var entry : defaults.entrySet()) {
            assertNotNull(entry.getValue(), "默认配置表不允许空列表：" + entry.getKey());
            List<String> columns = entry.getValue().stream()
                    .flatMap(row -> row.keySet().stream())
                    .distinct()
                    .toList();
            if (columns.isEmpty()) {
                // 默认黑名单已清空，但表本身仍需要存在；模拟真实 schema 的两列。
                jdbc.execute("CREATE TABLE " + entry.getKey()
                        + " (id INTEGER PRIMARY KEY, type TEXT, value TEXT)");
                continue;
            }
            String columnDefs = String.join(",",
                    columns.stream().map(column -> column + " " + columnType(column)).toList());
            jdbc.execute("CREATE TABLE " + entry.getKey() + " (id INTEGER PRIMARY KEY," + columnDefs + ")");
        }
    }

    private static String columnType(String column) {
        return switch (column) {
            case "debugger", "wait_time", "expected_salary_min", "expected_salary_max",
                 "enable_ai", "send_img_resume", "filter_dead_hr", "allow_repeat_chat" -> "INTEGER";
            default -> "TEXT";
        };
    }

    /** 单行表（boss_config/ai）：逐列断言 DB 值等于资源 JSON 值。 */
    private void assertRowEqualsFixture(String table, long id, Map<String, Object> fixture) {
        for (var entry : fixture.entrySet()) {
            String column = entry.getKey();
            Object expected = entry.getValue();
            if (expected == null) {
                assertNull(jdbc.queryForObject(
                                "SELECT " + column + " FROM " + table + " WHERE id = ?", Object.class, id),
                        table + "." + column + " 应为 null");
                continue;
            }
            Object actual = jdbc.queryForObject(
                    "SELECT " + column + " FROM " + table + " WHERE id = ?", Object.class, id);
            assertEquals(String.valueOf(expected), String.valueOf(actual),
                    table + "." + column + " 应与资源默认值一致");
        }
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        assertNotNull(value, "查询无结果：" + sql);
        return value;
    }

    private String text(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }
}
