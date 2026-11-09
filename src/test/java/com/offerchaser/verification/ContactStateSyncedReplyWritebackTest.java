package com.offerchaser.verification;

import com.offerchaser.application.contacts.ContactStateService;
import com.offerchaser.application.persistence.BossQueryRepository;
import com.offerchaser.application.persistence.ContactStateRepository;
import com.offerchaser.application.persistence.FollowupPlanRepository;
import com.offerchaser.application.persistence.ManualActionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static com.offerchaser.verification.VerificationSupport.execute;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static com.offerchaser.verification.VerificationSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-02 联系人回复写回边界验收（真实临时 SQLite）：
 * <ul>
 *   <li>{@link ContactStateRepository#markRepliedFromStatus} 只在“无人工接管/停止联系、且当前不是
 *       REJECTED/INTERVIEW/REPLIED”时把 reply_state 置 REPLIED，补 last_reply_at 并 bump version；</li>
 *   <li>人工接管/停止/REJECTED/INTERVIEW/REPLIED 一律不覆盖，返回 0 行；</li>
 *   <li>{@link ContactStateService#applySyncedReply} 人工状态优先返回 false；成功回写后取消该联系人
 *       未执行的跟进计划（SUGGESTED/APPROVED → CANCELLED），已执行/已取消计划不动，且后续
 *       {@code isAutoContactBlocked} 为 true（阻断自动发送）。</li>
 * </ul>
 * 不接触真实库、不发送任何消息。
 */
class ContactStateSyncedReplyWritebackTest {

    private static final String ACCOUNT = "local";
    private static final String PLATFORM = "boss";
    private static final String KEY = "uid:U1";
    private static final String REPLY_AT = "2026-11-07 12:00:00";

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private ContactStateRepository contactStateRepository;
    private FollowupPlanRepository followupPlanRepository;
    private ContactStateService contactStateService;

    @BeforeEach
    void setUp() {
        dataSource = migratedDataSource(tempDir);
        contactStateRepository = new ContactStateRepository(dataSource);
        followupPlanRepository = new FollowupPlanRepository(dataSource);
        ManualActionRepository manualActionRepository = new ManualActionRepository(dataSource);
        BossQueryRepository bossQueryRepository = new BossQueryRepository(dataSource);
        contactStateService = new ContactStateService(contactStateRepository,
                followupPlanRepository, manualActionRepository, bossQueryRepository);
        contactStateService.ensureOnConfirmedFirstContact(KEY, "U1", "张女士", "示例科技",
                "2026-11-06 09:00:00");
    }

    // ==================== Repository 写回边界 ====================

    @Test
    void unknownStateIsMarkedRepliedAndBumpsVersionWithoutTouchingLastContact() {
        int updated = contactStateRepository.markRepliedFromStatus(ACCOUNT, PLATFORM, KEY, REPLY_AT);

        assertEquals(1, updated, "UNKNOWN → REPLIED 应更新 1 行");
        ContactStateRepository.ContactState state = contactStateRepository.findByKey(ACCOUNT, PLATFORM, KEY);
        assertNotNull(state);
        assertEquals("REPLIED", state.replyState());
        assertEquals(REPLY_AT, state.lastReplyAt(), "应补 last_reply_at");
        assertEquals("2026-11-06 09:00:00", state.lastContactAt(), "写回不得触碰 last_contact_at");
        assertEquals(1, state.version(), "状态写回必须 bump version");
        assertNotNull(state.updatedAt());
    }

    @Test
    void existingLastReplyAtIsNotOverwritten() {
        execute(dataSource, "UPDATE contact_state SET reply_state='NO_REPLY', " +
                "last_reply_at='2026-11-05 08:00:00' WHERE contact_key=?", KEY);

        assertEquals(1, contactStateRepository.markRepliedFromStatus(ACCOUNT, PLATFORM, KEY, REPLY_AT));

        assertEquals("2026-11-05 08:00:00", scalarString(dataSource,
                "SELECT last_reply_at FROM contact_state WHERE contact_key=?", KEY),
                "last_reply_at 使用 COALESCE：已有回复时间不被同步覆盖");
        assertEquals("REPLIED", scalarString(dataSource,
                "SELECT reply_state FROM contact_state WHERE contact_key=?", KEY));
    }

    @Test
    void manualTakeoverOrStopContactIsNeverOverwritten() {
        execute(dataSource, "UPDATE contact_state SET manual_takeover=1 WHERE contact_key=?", KEY);
        assertEquals(0, contactStateRepository.markRepliedFromStatus(ACCOUNT, PLATFORM, KEY, REPLY_AT),
                "人工接管优先：同步不得覆盖");
        assertEquals(1, scalar(dataSource,
                "SELECT manual_takeover FROM contact_state WHERE contact_key=?", KEY),
                "同步不得清除人工接管标记");
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT reply_state FROM contact_state WHERE contact_key=?", KEY));

        execute(dataSource, "UPDATE contact_state SET manual_takeover=0, stop_contact=1 WHERE contact_key=?", KEY);
        assertEquals(0, contactStateRepository.markRepliedFromStatus(ACCOUNT, PLATFORM, KEY, REPLY_AT),
                "停止联系优先：同步不得覆盖");
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT reply_state FROM contact_state WHERE contact_key=?", KEY));
        assertEquals(0, scalar(dataSource, "SELECT version FROM contact_state WHERE contact_key=?", KEY));
    }

    @Test
    void manualReplyStatesRejectedInterviewRepliedAreNeverOverwritten() {
        for (String state : new String[]{"REJECTED", "INTERVIEW", "REPLIED"}) {
            execute(dataSource, "UPDATE contact_state SET reply_state=?, manual_takeover=0, " +
                    "stop_contact=0, version=0 WHERE contact_key=?", state, KEY);
            assertEquals(0, contactStateRepository.markRepliedFromStatus(ACCOUNT, PLATFORM, KEY, REPLY_AT),
                    state + " 属人工判断/已回复，状态同步不得覆盖");
            assertEquals(state, scalarString(dataSource,
                    "SELECT reply_state FROM contact_state WHERE contact_key=?", KEY));
        }
    }

    @Test
    void missingContactOrWrongAccountPlatformUpdatesNothing() {
        assertEquals(0, contactStateRepository.markRepliedFromStatus(ACCOUNT, PLATFORM, "uid:NOT-EXIST", REPLY_AT),
                "联系人不存在不得插入新行");
        assertEquals(1, scalar(dataSource, "SELECT COUNT(*) FROM contact_state"));

        assertEquals(0, contactStateRepository.markRepliedFromStatus("other", PLATFORM, KEY, REPLY_AT),
                "账号不一致不得写回");
        assertEquals(0, contactStateRepository.markRepliedFromStatus(ACCOUNT, "other-platform", KEY, REPLY_AT),
                "平台不一致不得写回");
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT reply_state FROM contact_state WHERE contact_key=?", KEY));
    }

    // ==================== Service：人工优先 + 取消未执行跟进 ====================

    @Test
    void applySyncedReplyWritesRepliedAndCancelsActiveFollowups() {
        insertFollowup("plan-suggested", "SUGGESTED");
        insertFollowup("plan-approved", "APPROVED");
        insertFollowup("plan-executed", "EXECUTED");
        insertFollowup("plan-cancelled", "CANCELLED");

        assertTrue(contactStateService.applySyncedReply(KEY, REPLY_AT),
                "非人工状态应成功回写 REPLIED");

        ContactStateRepository.ContactState state = contactStateRepository.findByKey(ACCOUNT, PLATFORM, KEY);
        assertEquals("REPLIED", state.replyState());
        assertEquals(REPLY_AT, state.lastReplyAt());
        assertEquals("CANCELLED", scalarString(dataSource,
                "SELECT status FROM followup_plan WHERE plan_id='plan-suggested'"),
                "SUGGESTED 跟进应被取消");
        assertEquals("CANCELLED", scalarString(dataSource,
                "SELECT status FROM followup_plan WHERE plan_id='plan-approved'"),
                "APPROVED 跟进应被取消");
        assertEquals("EXECUTED", scalarString(dataSource,
                "SELECT status FROM followup_plan WHERE plan_id='plan-executed'"),
                "已执行计划不得被改写");
        assertEquals("CANCELLED", scalarString(dataSource,
                "SELECT status FROM followup_plan WHERE plan_id='plan-cancelled'"));
        assertEquals("投递状态同步读取到 HR 已回复", scalarString(dataSource,
                        "SELECT reason FROM followup_plan WHERE plan_id='plan-suggested'"),
                "取消原因应可追溯");
        assertTrue(contactStateService.isAutoContactBlocked(KEY),
                "写回 REPLIED 后自动发送必须被阻断");
    }

    @Test
    void manualTakeoverStopAndManualReplyStatesWinOverSync() {
        // 直接改库模拟人工标记（不经 markState，便于验证同步本身不取消跟进）
        insertFollowup("plan-active", "SUGGESTED");
        execute(dataSource, "UPDATE contact_state SET manual_takeover=1 WHERE contact_key=?", KEY);
        assertFalse(contactStateService.applySyncedReply(KEY, REPLY_AT), "人工接管优先返回 false");
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT reply_state FROM contact_state WHERE contact_key=?", KEY));
        assertEquals("SUGGESTED", scalarString(dataSource,
                "SELECT status FROM followup_plan WHERE plan_id='plan-active'"),
                "人工接管时状态同步不得替人工取消跟进");

        execute(dataSource, "UPDATE contact_state SET manual_takeover=0, stop_contact=1 WHERE contact_key=?", KEY);
        assertFalse(contactStateService.applySyncedReply(KEY, REPLY_AT), "停止联系优先返回 false");
        assertEquals("SUGGESTED", scalarString(dataSource,
                "SELECT status FROM followup_plan WHERE plan_id='plan-active'"),
                "停止联系时状态同步不得取消跟进");

        for (String state : new String[]{"REJECTED", "INTERVIEW", "REPLIED"}) {
            execute(dataSource, "UPDATE contact_state SET reply_state=?, stop_contact=0 WHERE contact_key=?",
                    state, KEY);
            assertFalse(contactStateService.applySyncedReply(KEY, REPLY_AT), state + " 人工判断优先");
            assertEquals(state, scalarString(dataSource,
                    "SELECT reply_state FROM contact_state WHERE contact_key=?", KEY));
        }
    }

    @Test
    void secondSyncDoesNotCancelAgainNorChangeReplyState() {
        insertFollowup("plan-active", "SUGGESTED");
        assertTrue(contactStateService.applySyncedReply(KEY, REPLY_AT));
        int versionAfterFirst = scalar(dataSource,
                "SELECT version FROM contact_state WHERE contact_key=?", KEY);

        assertFalse(contactStateService.applySyncedReply(KEY, "2026-11-07 13:00:00"),
                "已 REPLIED 再次同步应返回 false（幂等，不重复计数）");

        assertEquals(versionAfterFirst, scalar(dataSource,
                "SELECT version FROM contact_state WHERE contact_key=?", KEY), "幂等同步不得再次 bump version");
        assertEquals(REPLY_AT, scalarString(dataSource,
                "SELECT last_reply_at FROM contact_state WHERE contact_key=?", KEY),
                "保留首次回写时间");
    }

    @Test
    void blankContactKeyAndMissingContactReturnFalseWithoutSideEffects() {
        assertFalse(contactStateService.applySyncedReply(null, REPLY_AT));
        assertFalse(contactStateService.applySyncedReply("  ", REPLY_AT));
        assertFalse(contactStateService.applySyncedReply("uid:NOT-EXIST", REPLY_AT));
        assertEquals(1, scalar(dataSource, "SELECT COUNT(*) FROM contact_state"));
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM followup_plan"));
        assertNull(contactStateRepository.findByKey(ACCOUNT, PLATFORM, "uid:NOT-EXIST"));
    }

    // ==================== 工具 ====================

    private void insertFollowup(String planId, String status) {
        followupPlanRepository.insert(new FollowupPlanRepository.FollowupPlan(
                0L, planId, ACCOUNT, PLATFORM, "ENC-1", "U1", KEY, "Java 开发", "示例科技",
                "FOLLOWUP", status, "测试计划", null, 0, "2026-11-07 09:00:00", null, null, null,
                "2026-11-07 09:00:00", "2026-11-07 09:00:00"));
    }
}
