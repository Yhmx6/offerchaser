package com.offerchaser.verification;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-02 前端交付检查（只读，不修改 front/dist）：
 * <ul>
 *   <li>{@code StatusSyncCard} 成功提示精简为「已读取 N 条会话」，保留已读/回复统计与 409 处理；
 *       {@code replyStateSynced} 字段/API 能力保留但不再有长文案展示（精简后）；</li>
 *   <li>{@code StatusSyncResult.replyStateSynced} 为可选数字类型；API 封装返回该字段；</li>
 *   <li>卡片挂载在 /boss 总览页，构建产物 dist 同步包含新文案。</li>
 * </ul>
 */
class StatusSyncReplyWritebackFrontendCheckTest {

    private static final Path FRONT = Path.of("front");
    private static final Path DIST = Path.of("src", "main", "resources", "dist");

    @Test
    void statusSyncCardShowsReplyWritebackCount() throws Exception {
        Path cardPath = FRONT.resolve("app/components/boss/StatusSyncCard.tsx");
        assertTrue(Files.isRegularFile(cardPath), "front 缺少 StatusSyncCard.tsx");
        String card = Files.readString(cardPath);

        assertTrue(card.contains("runDeliveryStatusSync"), "卡片必须调用真实状态同步接口");
        assertTrue(card.contains("getDeliveryStatusSyncSummary"), "卡片必须读取真实同步摘要");
        // 成功提示精简为「已读取 N 条会话」；replyStateSynced 的回写展示文案随精简移除（字段/API 仍保留）
        assertTrue(card.contains("条会话"), "成功提示应为「已读取 N 条会话」");
        assertFalse(card.contains("个联系人回复状态"), "不得再展示旧「回写 N 个联系人回复状态」长文案");
        assertFalse(card.contains("本次写入") || card.contains("条快照"),
                "成功提示不得再保留快照数量长文案");
        assertTrue(card.contains("ApiError") && card.contains("409"),
                "同步冲突 409 必须有专门提示");
        assertTrue(card.contains("await loadSummary()"),
                "同步成功后应刷新摘要（回写数量与最新 run 一致）");
        // 已读/回复三类来源统计不能回归
        for (String text : List.of("自动投递", "手动投递", "HR 主动招呼", "已读", "已回复")) {
            assertTrue(card.contains(text), "状态同步卡不应回归统计文案：" + text);
        }
        // 文案精简：仅保留标题/按钮/来源表格/今日昨日/每日补读，不再长篇说明
        for (String text : List.of("投递状态同步", "读取今日投递状态", "今日主动同步",
                "昨天启动补读", "来源", "总数", "已读", "已回复", "未读", "未知")) {
            assertTrue(card.contains(text), "状态同步卡应保留：" + text);
        }
        assertTrue(card.contains("投递状态同步卡：只读统计。"),
                "StatusSyncCard JSDoc 应精简为只读统计");
        assertFalse(card.contains("只读 BOSS 聊天，不发送消息；三种来源分开统计"),
                "StatusSyncCard 不得保留旧 JSDoc 长注释");
        assertTrue(card.contains("已读取 ${result.run.autoTotal + result.run.manualTotal + result.run.hrTotal} 条会话"),
                "同步成功提示应为「已读取 N 条会话」");
        assertFalse(card.contains("本次写入") || card.contains("条快照"),
                "成功提示不得再保留快照数量长文案");
        assertTrue(card.contains("每日首次启动会自动补读前一天。"),
                "应保留每日首次自动补读前一天的说明");
        assertTrue(card.contains("暂无记录"), "无记录文案应改为「暂无记录」");
        assertFalse(card.contains("还没有记录"), "不得再使用旧文案「还没有记录」");

    }

    @Test
    void typesAndApiExposeReplyStateSyncedAsOptionalNumber() throws Exception {
        String types = Files.readString(FRONT.resolve("lib/boss-types.ts"));
        assertTrue(types.contains("replyStateSynced?: number"),
                "StatusSyncResult.replyStateSynced 必须是可选数字类型");
        assertTrue(types.contains("D-02") && types.contains("回写"),
                "类型文件应注明 D-02 回写数量来源");
        assertTrue(types.contains("interface StatusSyncResult"), "缺少 StatusSyncResult 类型");
        assertTrue(types.contains("snapshotCount: number"), "StatusSyncResult 应保留快照数量");

        String api = Files.readString(FRONT.resolve("lib/api.ts"));
        assertTrue(api.contains("runDeliveryStatusSync"), "lib/api.ts 缺少 runDeliveryStatusSync");
        assertTrue(api.contains("/api/boss/status-sync"),
                "必须调用 POST /api/boss/status-sync");
        assertTrue(api.contains("StatusSyncResult"), "状态同步接口应返回 StatusSyncResult");
    }

    @Test
    void statusSyncCardIsMountedOnBossOverview() throws Exception {
        String page = Files.readString(FRONT.resolve("app/boss/page.tsx"));
        assertTrue(page.contains("StatusSyncCard"), "StatusSyncCard 应挂载在 /boss 总览页");
        assertTrue(page.contains("components/boss/StatusSyncCard"),
                "应从 components/boss/StatusSyncCard 引入");
    }

    @Test
    void distContainsShortStatusSyncCopy() throws Exception {
        assertTrue(distContainsAny("条会话"), "dist 应包含「已读取 N 条会话」短提示");
        assertTrue(distContainsAny("投递状态同步"), "dist 应包含状态同步卡标题");
        assertTrue(distContainsAny("每日首次启动会自动补读前一天。"),
                "dist 应保留每日首次补读说明");
        assertTrue(Files.isRegularFile(DIST.resolve("boss.html")), "dist 缺少 boss.html 总览页");
    }

    @Test
    void noMockDataInStatusSyncSources() throws Exception {
        List<Path> sources = List.of(
                FRONT.resolve("app/components/boss/StatusSyncCard.tsx"),
                FRONT.resolve("lib/boss-types.ts"),
                FRONT.resolve("lib/api.ts"));
        for (Path source : sources) {
            String content = Files.readString(source).toLowerCase(Locale.ROOT);
            for (String marker : List.of("mockdata", "mock-data", "mock_", "fakedata", "todo: mock")) {
                assertFalse(content.contains(marker),
                        source.getFileName() + " 不得使用 mock 数据：" + marker);
            }
        }
    }

    private static boolean distContainsAny(String... needles) throws Exception {
        try (Stream<Path> stream = Files.walk(DIST)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".js") || name.endsWith(".html") || name.endsWith(".txt");
                    }).toList();
            for (Path file : files) {
                String content = Files.readString(file);
                for (String needle : needles) {
                    if (content.contains(needle)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
