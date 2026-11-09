package com.offerchaser.application.service;

import com.offerchaser.application.entity.BossConfigEntity;
import com.offerchaser.application.mapper.BossConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BOSS「重复沟通」配置：老库补列迁移 + 选择性保存。
 * <ul>
 *   <li>老库迁移：{@code boss_config} 缺列时补 {@code allow_repeat_chat INTEGER NOT NULL DEFAULT 0}
 *       与 {@code repeat_chat_messages TEXT}；保留原有招呼语；重启幂等、不覆盖用户已保存的值。</li>
 *   <li>选择性保存：非 null 字段覆盖、null 字段保持；开关 0/1 都可保存；显式空串覆盖消息；
 *       表里已有记录时只 update 该记录（不 insert），表为空时 insert 新记录并写时间戳。</li>
 * </ul>
 */
@DisplayName("BossService：重复沟通配置迁移与选择性保存")
class BossRepeatChatConfigTest {

    @TempDir
    Path directory;

    private SQLiteDataSource source;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + directory.resolve("repeat-chat.db"));
        jdbc = new JdbcTemplate(source);
    }

    @Nested
    @DisplayName("ensureRepeatChatColumn：老库补列迁移")
    class LegacyDatabaseMigration {

        @Test
        @DisplayName("老表缺列：补两列（开关默认 0、消息 null），原有招呼语保留")
        void addsMissingColumnsWithSafeDefaults() {
            jdbc.execute("CREATE TABLE boss_config (id INTEGER PRIMARY KEY, say_hi TEXT)");
            jdbc.update("INSERT INTO boss_config (id, say_hi) VALUES (1, '原有招呼语')");

            service().ensureRepeatChatColumn();

            List<Map<String, Object>> columns = bossConfigColumns();
            assertEquals(4, columns.size(), "应有 id/say_hi/allow_repeat_chat/repeat_chat_messages 四列");
            Map<String, Object> allow = column(columns, "allow_repeat_chat");
            assertEquals("INTEGER", allow.get("type"), "开关列应为 INTEGER");
            assertEquals(1, ((Number) allow.get("notnull")).intValue(), "开关列应 NOT NULL");
            assertEquals("0", String.valueOf(allow.get("dflt_value")), "开关列默认值应为 0");
            assertEquals("TEXT", column(columns, "repeat_chat_messages").get("type"));

            assertEquals("原有招呼语", jdbc.queryForObject(
                    "SELECT say_hi FROM boss_config WHERE id = 1", String.class), "迁移不得丢失原招呼语");
            assertEquals(0, jdbc.queryForObject(
                    "SELECT allow_repeat_chat FROM boss_config WHERE id = 1", Integer.class),
                    "老库迁移后开关应为关闭（0）");
            assertNull(jdbc.queryForObject(
                    "SELECT repeat_chat_messages FROM boss_config WHERE id = 1", String.class),
                    "老库迁移后消息应为 null（沿用内置默认文案）");
        }

        @Test
        @DisplayName("重启幂等：已补列不重复 DDL，且不覆盖用户已保存的开关/消息")
        void restartKeepsUserValuesAndIsIdempotent() {
            jdbc.execute("CREATE TABLE boss_config (id INTEGER PRIMARY KEY, say_hi TEXT)");
            jdbc.update("INSERT INTO boss_config (id, say_hi) VALUES (1, '原有招呼语')");
            service().ensureRepeatChatColumn();

            jdbc.update("UPDATE boss_config SET allow_repeat_chat = 1, repeat_chat_messages = '用户编辑的消息'");
            // 模拟应用重启：新的 BossService 实例再次执行补列
            service().ensureRepeatChatColumn();

            assertEquals(4, bossConfigColumns().size(), "重复执行不得重复加列");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT allow_repeat_chat FROM boss_config WHERE id = 1", Integer.class),
                    "重启不得把用户开启的开关重置为 0");
            assertEquals("用户编辑的消息", jdbc.queryForObject(
                    "SELECT repeat_chat_messages FROM boss_config WHERE id = 1", String.class),
                    "重启不得覆盖用户编辑的消息");
            assertEquals("原有招呼语", jdbc.queryForObject(
                    "SELECT say_hi FROM boss_config WHERE id = 1", String.class));
        }

        @Test
        @DisplayName("boss_config 尚未建表：不抛错、不创建表（由迁移/建表流程负责）")
        void missingTableIsNoOp() {
            assertDoesNotThrow(() -> service().ensureRepeatChatColumn());

            assertTrue(jdbc.queryForList("PRAGMA table_info('boss_config')").isEmpty(),
                    "表不存在时不应由 ensureRepeatChatColumn 创建");
        }
    }

    @Nested
    @DisplayName("saveOrUpdateFirstSelective：选择性保存")
    class SelectiveSave {

        private BossConfigMapper mapper;
        private BossConfigEntity existing;
        private BossService service;

        @BeforeEach
        void setUpMapper() {
            mapper = mock(BossConfigMapper.class);
            existing = new BossConfigEntity();
            existing.setId(1L);
            existing.setAllowRepeatChat(0);
            existing.setSayHi("原招呼语");
            existing.setDebugger(1);
            existing.setWaitTime(10);
            when(mapper.selectOne(any())).thenReturn(existing);
            service = new BossService(null, null, mapper, null, null, null, null);
        }

        @Test
        @DisplayName("开关 0/1 都能保存；非 null 字段覆盖，null 字段保持；消息空串可显式覆盖")
        void switchValuesAndMessagesRoundTrip() {
            BossConfigEntity enable = new BossConfigEntity();
            enable.setAllowRepeatChat(1);
            enable.setRepeatChatMessages("第一条\n第二条");

            assertSame(existing, service.saveOrUpdateFirstSelective(enable));
            assertEquals(1, existing.getAllowRepeatChat());
            assertEquals("第一条\n第二条", existing.getRepeatChatMessages());
            assertEquals("原招呼语", existing.getSayHi(), "未传的 sayHi 不得被改写");
            assertNotNull(existing.getUpdatedAt(), "更新应写 updatedAt");

            // 只传空 patch：所有字段保持
            BossConfigEntity emptyPatch = new BossConfigEntity();
            assertSame(existing, service.saveOrUpdateFirstSelective(emptyPatch));
            assertEquals(1, existing.getAllowRepeatChat(), "null 字段应保持原值");
            assertEquals("第一条\n第二条", existing.getRepeatChatMessages());

            // 显式空串覆盖消息 + 关闭开关
            BossConfigEntity disable = new BossConfigEntity();
            disable.setAllowRepeatChat(0);
            disable.setRepeatChatMessages("");
            assertSame(existing, service.saveOrUpdateFirstSelective(disable));
            assertEquals(0, existing.getAllowRepeatChat());
            assertEquals("", existing.getRepeatChatMessages(), "空字符串应覆盖原消息，而不是保持");

            verify(mapper, times(3)).updateById(existing);
            verify(mapper, never()).insert(any(BossConfigEntity.class));
        }

        @Test
        @DisplayName("表为空：插入新记录并写入 created/updated 时间戳（不走 update）")
        void insertWhenNoExistingRow() {
            when(mapper.selectOne(any())).thenReturn(null);
            BossConfigEntity patch = new BossConfigEntity();
            patch.setAllowRepeatChat(1);
            patch.setRepeatChatMessages("新消息");

            BossConfigEntity saved = service.saveOrUpdateFirstSelective(patch);

            assertSame(patch, saved);
            assertEquals(1, saved.getAllowRepeatChat());
            assertNotNull(saved.getCreatedAt(), "新记录应写 createdAt");
            assertNotNull(saved.getUpdatedAt(), "新记录应写 updatedAt");
            assertNotEquals(0L, saved.getUpdatedAt().getYear(), "updatedAt 应是真实时间");
            verify(mapper).insert(patch);
            verify(mapper, never()).updateById(any(BossConfigEntity.class));
        }

        @Test
        @DisplayName("未传字段保持：只传开关时 sayHi/debugger/waitTime 不被清空")
        void omittedFieldsKeepCurrentValues() {
            BossConfigEntity patch = new BossConfigEntity();
            patch.setAllowRepeatChat(1);

            service.saveOrUpdateFirstSelective(patch);

            assertEquals("原招呼语", existing.getSayHi());
            assertEquals(1, existing.getDebugger());
            assertEquals(10, existing.getWaitTime());
            verify(mapper, times(1)).updateById(existing);
        }
    }

    // ==================== 工具 ====================

    private BossService service() {
        return new BossService(null, null, null, null, null, source, null);
    }

    private List<Map<String, Object>> bossConfigColumns() {
        return jdbc.queryForList("PRAGMA table_info('boss_config')");
    }

    private static Map<String, Object> column(List<Map<String, Object>> columns, String name) {
        return columns.stream()
                .filter(column -> name.equals(column.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少列：" + name));
    }

    private static void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }
}
