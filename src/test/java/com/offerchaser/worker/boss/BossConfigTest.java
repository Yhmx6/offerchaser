package com.offerchaser.worker.boss;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BossConfig 的纯逻辑单元测试（重复沟通话术列表）。
 * <p>
 * 原 BossRepeatChatTest 里的投递流程测试依赖已删除的 resumeSubmission 私有方法，
 * 改由 BossFlowDecisionTest 覆盖"先评估、再沟通"的决策路径。
 */
class BossConfigTest {

    @Test
    void selectsOnlyNonBlankLinesAndKeepsUserText() {
        BossConfig config = new BossConfig();
        config.setRepeatChatMessages("\r\n 第一条 \r\n\t\r\n第二条？\r\n");
        for (int i = 0; i < 100; i++) {
            assertTrue(java.util.Set.of("第一条", "第二条？").contains(config.randomRepeatChatMessage()));
        }
        config.setRepeatChatMessages(" \n\t\r\n");
        assertNull(config.randomRepeatChatMessage());
        config.setRepeatChatMessages(null);
        assertNull(config.randomRepeatChatMessage());
    }

    @Test
    void includesSixDefaultFollowUpMessages() {
        BossConfig config = new BossConfig();
        assertEquals(6, config.getRepeatChatMessages().lines().count());
        assertTrue(config.getRepeatChatMessages().contains("请问贵公司还招人吗？"));
    }
}
