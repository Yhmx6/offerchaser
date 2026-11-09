package com.offerchaser.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerchaser.application.contacts.ContactStateService;
import com.offerchaser.application.persistence.BossQueryRepository;
import com.offerchaser.application.persistence.ContactStateRepository;
import com.offerchaser.application.persistence.DeliveryStatusRepository;
import com.offerchaser.application.persistence.FollowupPlanRepository;
import com.offerchaser.application.persistence.ManualActionRepository;
import com.offerchaser.application.persistence.ProfileTemplateRepository;
import com.offerchaser.application.status.DeliveryStatusSyncService;
import com.offerchaser.worker.boss.BossChatStatusReader;
import com.offerchaser.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.offerchaser.verification.VerificationSupport.execute;
import static com.offerchaser.verification.VerificationSupport.migratedDataSource;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static com.offerchaser.verification.VerificationSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D2/D-02：投递状态同步服务分类验收（真实 SQLite + mock Playwright/Reader）。
 * 覆盖：自动投递匹配/DB_ONLY、手动投递、HR 主动招呼、已读/回复/未知口径、
 * 目标日期过滤（HH:mm / 昨天 / MM-DD / 完整日期）、重复匹配、空跑、FAILED、busy、启动跳过/摘要；
 * D-02：状态同步读到 HR 已回复时回写 contact_state（按 contactKey 匹配、replyStateSynced 计数、
 * 无联系人/AUTO/MANUAL 之外/人工接管不写）。
 */
class DeliveryStatusSyncServiceIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private DeliveryStatusRepository repository;
    private PlaywrightManager playwrightManager;
    private BossChatStatusReader reader;
    private DeliveryStatusSyncService service;
    private Page page;
    private ContactStateService contactStateService;
    private ContactStateRepository contactStateRepository;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = migratedDataSource(tempDir);
        repository = new DeliveryStatusRepository(dataSource);
        contactStateRepository = new ContactStateRepository(dataSource);
        contactStateService = new ContactStateService(contactStateRepository,
                new FollowupPlanRepository(dataSource),
                new ManualActionRepository(dataSource),
                new BossQueryRepository(dataSource));
        playwrightManager = mock(PlaywrightManager.class);
        reader = mock(BossChatStatusReader.class);
        page = mock(Page.class);
        service = new DeliveryStatusSyncService(playwrightManager, reader, repository,
                contactStateService, dataSource);

        when(playwrightManager.isInitialized()).thenReturn(true);
        when(playwrightManager.isPlaywrightBusy()).thenReturn(false);
        when(playwrightManager.isLoggedIn("boss")).thenReturn(true);
        when(playwrightManager.getBossPage()).thenReturn(page);
        when(playwrightManager.callOnPlaywright(any())).thenAnswer(invocation -> {
            Callable<?> task = invocation.getArgument(0);
            return task.call();
        });
        when(reader.readChatList(any())).thenReturn(List.of());
        when(reader.detectInitiator(any(), any())).thenReturn(BossChatStatusReader.Initiator.UNKNOWN);
    }

    // ==================== 分类 ====================

    @Test
    void classifiesAutoManualAndHrInitiated() {
        insertAttempt("A1", "U1", "示例科技", "Java 开发", startedToday("09:00:00"));
        insertAttempt("A2", "U2", "另一个公司", "产品经理", startedToday("09:10:00"));
        when(reader.readChatList(page)).thenReturn(List.of(
                chat(0, "张HR", "示例科技", true, true, false, "", "您好，我对该岗位感兴趣", "10:30"),
                chat(1, "", "手动公司", true, false, true, "", "请问还招人吗", "11:00"),
                chat(2, "李HR", "HR 主动公司", false, false, false, "2", "你好，有兴趣聊聊吗", "12:00")));
        when(reader.detectInitiator(page, chat(1, "", "手动公司", true, false, true, "", "请问还招人吗", "11:00")))
                .thenReturn(BossChatStatusReader.Initiator.SELF);
        when(reader.detectInitiator(page, chat(2, "李HR", "HR 主动公司", false, false, false, "2",
                "你好，有兴趣聊聊吗", "12:00")))
                .thenReturn(BossChatStatusReader.Initiator.HR);

        Map<String, Object> result = service.syncActiveToday();

        assertFalse((Boolean) result.get("skipped"));
        var run = repository.findLatest();
        assertNotNull(run);
        assertEquals("ACTIVE", run.triggerType());
        assertEquals("TODAY", run.scope());
        assertEquals("COMPLETED", run.status());
        assertEquals(today().toString(), run.targetDate());
        // AUTO：A1 命中 + A2 DB_ONLY => total2/read1/unknown1
        assertEquals(2, run.autoTotal());
        assertEquals(1, run.autoRead());
        assertEquals(1, run.autoUnknown(), "未匹配到聊天的投递记录应计 DB_ONLY unknown");
        // MANUAL：未匹配且自己发起、送达未读
        assertEquals(1, run.manualTotal());
        assertEquals(1, run.manualUnread());
        // HR 主动：未知已读、已回复
        assertEquals(1, run.hrTotal());
        assertEquals(1, run.hrUnknown());
        assertEquals(1, run.hrReplied());
        var snapshots = repository.findSnapshots(run.syncId());
        assertEquals(4, snapshots.size());
        assertEquals(1, snapshots.stream().filter(s -> "HR_INITIATED".equals(s.source())).count());
        assertEquals(2, snapshots.stream().filter(s -> "AUTO".equals(s.source())).count());
        assertEquals(1, snapshots.stream().filter(s -> "MANUAL".equals(s.source())).count());
        assertTrue(snapshots.stream().anyMatch(s -> "DB_ONLY_NO_CHAT".equals(s.evidence())),
                "DB_ONLY 候选应有明确证据");
    }

    @Test
    void readAndReplyUnknownSemantics() {
        insertAttempt("A1", "U1", "示例科技", "Java", startedToday("09:00:00"));
        // 匹配但列表既无已读/送达标记，也无未读红点、最后一条非 HR → 已读/回复均 UNKNOWN
        when(reader.readChatList(page)).thenReturn(List.of(
                chat(0, "张HR", "示例科技", false, false, false, "", "", "10:00")));

        service.syncActiveToday();

        var run = repository.findLatest();
        assertEquals(1, run.autoTotal());
        assertEquals(1, run.autoUnknown());
        assertEquals(0, run.autoRead());
        assertEquals(0, run.autoReplied());
        var snapshot = repository.findSnapshots(run.syncId()).get(0);
        assertEquals("UNKNOWN", snapshot.readStatus());
        assertEquals("UNKNOWN", snapshot.replyStatus());
    }

    @Test
    void duplicateChatMatchCountsOnce() {
        insertAttempt("A1", "U1", "示例科技", "Java", startedToday("09:00:00"));
        var dup = chat(0, "张HR", "示例科技", true, true, false, "", "您好", "10:00");
        when(reader.readChatList(page)).thenReturn(List.of(dup, dup));

        service.syncActiveToday();

        var run = repository.findLatest();
        assertEquals(1, run.autoTotal(), "同一投递记录匹配到多条聊天时应只统计一次");
        assertEquals(0, run.manualTotal(), "重复匹配的聊天不得再计入手动投递");

    }

    @Test
    void noDataRunCompletesWithZeroCounts() {
        Map<String, Object> result = service.syncActiveToday();

        assertFalse((Boolean) result.get("skipped"));
        assertEquals(0, result.get("snapshotCount"));
        var run = repository.findLatest();
        assertEquals("COMPLETED", run.status());
        assertEquals(0, run.autoTotal() + run.manualTotal() + run.hrTotal());
    }

    // ==================== 日期过滤 ====================

    @Test
    void previousDaySyncOnlyCountsYesterdayChats() {
        String yesterday = today().minusDays(1).toString();
        String mdYesterday = today().minusDays(1).getMonthValue() + "-"
                + today().minusDays(1).getDayOfMonth();
        when(reader.readChatList(page)).thenReturn(List.of(
                chat(0, "", "昨天公司", false, false, false, "", "昨天消息", "昨天"),
                chat(1, "", "今天公司", false, false, false, "", "今天消息", "09:30"),
                chat(2, "", "月日公司", false, false, false, "", "月日消息", mdYesterday),
                chat(3, "", "完整日期公司", false, false, false, "", "完整日期消息", yesterday)));

        Map<String, Object> result = service.syncPreviousDayIfNeeded();

        assertFalse((Boolean) result.get("skipped"));
        var run = repository.findLatest();
        assertEquals("STARTUP", run.triggerType());
        assertEquals("PREVIOUS_DAY", run.scope());
        assertEquals(yesterday, run.targetDate());
        assertEquals(3, run.manualTotal(), "昨天/MM-DD/完整日期应计入，今天的 HH:mm 应排除");
    }

    // ==================== 失败 / busy ====================

    @Test
    void failureWritesFailedAndThrows() {
        when(playwrightManager.isInitialized()).thenReturn(false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.syncActiveToday());

        assertTrue(error.getMessage().contains("浏览器"), error.getMessage());
        var run = repository.findLatest();
        assertEquals("FAILED", run.status());
        assertNotNull(run.error());
        assertTrue(run.error().contains("浏览器尚未初始化"));
        assertEquals(0, run.autoTotal() + run.manualTotal() + run.hrTotal());
        // running 标记应释放：再次调用仍是业务失败而不是 busy
        assertTrue(assertThrows(IllegalStateException.class, () -> service.syncActiveToday())
                .getMessage().contains("浏览器尚未初始化"));
    }

    @Test
    void busyGuardRejectsConcurrentSync() throws Exception {
        Field running = DeliveryStatusSyncService.class.getDeclaredField("running");
        running.setAccessible(true);
        AtomicBoolean flag = (AtomicBoolean) running.get(service);
        assertTrue(flag.compareAndSet(false, true));
        try {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> service.syncActiveToday());
            assertTrue(error.getMessage().contains("正在进行"), error.getMessage());
        } finally {
            flag.set(false);
        }
    }

    // ==================== 启动补读 / 摘要 ====================

    @Test
    void startupSkipWhenPreviousDayCompleted() {
        String previous = today().minusDays(1).toString();
        insertCompletedStartup(previous);

        Map<String, Object> result = service.syncPreviousDayIfNeeded();

        assertEquals(Boolean.TRUE, result.get("skipped"));
        assertEquals(previous, result.get("targetDate"));
        assertEquals(1, repository.findRecent(30).stream()
                .filter(run -> "STARTUP".equals(run.triggerType())).count(),
                "已完成的当天启动补读不应重复执行");
    }

    @Test
    void startupRunsWhenPreviousDayNotCompleted() {
        String previous = today().minusDays(1).toString();
        // 前一天 FAILED 的启动补读不应阻止今天再试
        repository.start(new DeliveryStatusRepository.StatusSyncRun(
                "startup-failed", "STARTUP", "PREVIOUS_DAY", previous, "FAILED",
                previous + " 08:00:00", previous + " 08:01:00",
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "boom"));

        Map<String, Object> result = service.syncPreviousDayIfNeeded();

        assertFalse((Boolean) result.get("skipped"));
        assertEquals("STARTUP", repository.findLatest().triggerType());
        assertEquals("COMPLETED", repository.findLatest().status());
    }

    @Test
    void summaryExposesActiveStartupLatestAndRecent() {
        when(reader.readChatList(page)).thenReturn(List.of());
        service.syncActiveToday();
        String previous = today().minusDays(1).toString();
        insertCompletedStartup(previous);

        Map<String, Object> summary = service.summary();

        assertEquals(today().toString(), summary.get("today"));
        assertEquals(previous, summary.get("previousDay"));
        assertNotNull(summary.get("active"));
        assertEquals("ACTIVE", ((DeliveryStatusRepository.StatusSyncRun) summary.get("active")).triggerType());
        assertNotNull(summary.get("startup"));
        assertEquals("STARTUP", ((DeliveryStatusRepository.StatusSyncRun) summary.get("startup")).triggerType());
        assertNotNull(summary.get("latest"));
        assertTrue(((List<?>) summary.get("recent")).size() >= 2);
    }

    // ==================== D-02：回复写回 contact_state ====================

    @Test
    void autoRepliedChatWritesBackContactStateAndReportsSyncedCount() {
        contactStateService.ensureOnConfirmedFirstContact("uid:U1", "U1", "张HR", "示例科技",
                "2026-11-06 09:00:00");
        insertAttempt("A1", "U1", "uid:U1", "示例科技", "Java", startedToday("09:00:00"));
        when(reader.readChatList(page)).thenReturn(List.of(
                chat(0, "张HR", "示例科技", true, true, false, "1", "您好，方便聊聊吗", "10:00")));

        Map<String, Object> result = service.syncActiveToday();

        assertEquals(1, result.get("replyStateSynced"), "本次应回写 1 个联系人回复状态");
        var run = repository.findLatest();
        assertEquals(1, run.autoReplied());
        var snapshot = repository.findSnapshots(run.syncId()).stream()
                .filter(item -> "AUTO".equals(item.source())).findFirst().orElseThrow();
        assertEquals("uid:U1", snapshot.contactKey(), "AUTO 快照应带投递记录里的 contactKey");
        assertEquals("REPLIED", snapshot.replyStatus());
        assertEquals("REPLIED", scalarString(dataSource,
                "SELECT reply_state FROM contact_state WHERE contact_key='uid:U1'"),
                "HR 已回复应写回 contact_state");
        assertNotNull(scalarString(dataSource,
                "SELECT last_reply_at FROM contact_state WHERE contact_key='uid:U1'"));
    }

    @Test
    void manualRepliedChatMatchesContactByHrAndCompanyAndWritesBack() {
        contactStateService.ensureOnConfirmedFirstContact("uid:U2", "U2", "李女士", "另一个公司",
                "2026-11-06 09:00:00");
        when(reader.readChatList(page)).thenReturn(List.of(
                chat(0, "李HR", "另一个公司", false, false, false, "2", "你好，有兴趣聊聊吗", "11:00")));
        when(reader.detectInitiator(any(), any())).thenReturn(BossChatStatusReader.Initiator.SELF);

        Map<String, Object> result = service.syncActiveToday();

        assertEquals(1, result.get("replyStateSynced"), "手动投递的回复也应按联系人写回");
        var run = repository.findLatest();
        assertEquals(1, run.manualTotal());
        var snapshot = repository.findSnapshots(run.syncId()).get(0);
        assertEquals("MANUAL", snapshot.source());
        assertEquals("uid:U2", snapshot.contactKey(),
                "MANUAL 快照应按 HR+公司匹配到的 contactKey 回写");
        assertEquals("REPLIED", scalarString(dataSource,
                "SELECT reply_state FROM contact_state WHERE contact_key='uid:U2'"));
    }

    @Test
    void unmatchedChatWithoutContactCandidateDoesNotWriteBack() {
        when(reader.readChatList(page)).thenReturn(List.of(
                chat(0, "陌生HR", "陌生公司", false, false, false, "3", "你好", "11:00")));

        Map<String, Object> result = service.syncActiveToday();

        assertEquals(0, result.get("replyStateSynced"), "没有匹配联系人时不得凭空写回");
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM contact_state"),
                "状态同步不得创建联系人");
        var snapshot = repository.findSnapshots(repository.findLatest().syncId()).get(0);
        assertEquals("MANUAL", snapshot.source());
        assertNull(snapshot.contactKey(), "未匹配到联系人时快照 contactKey 应为空");
    }

    @Test
    void hrInitiatedRepliesAreNotWrittenBackAsManualReply() {
        contactStateService.ensureOnConfirmedFirstContact("uid:U3", "U3", "王女士", "主动公司",
                "2026-11-06 09:00:00");
        var hrChat = chat(0, "王HR", "主动公司", false, false, false, "2", "你好，有兴趣聊聊吗", "12:00");
        when(reader.readChatList(page)).thenReturn(List.of(hrChat));
        when(reader.detectInitiator(page, hrChat)).thenReturn(BossChatStatusReader.Initiator.HR);

        Map<String, Object> result = service.syncActiveToday();

        var run = repository.findLatest();
        assertEquals(1, run.hrTotal());
        assertEquals("HR_INITIATED", repository.findSnapshots(run.syncId()).get(0).source());
        assertEquals(0, result.get("replyStateSynced"),
                "HR 主动招呼来源不在 AUTO/MANUAL 回写范围");
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT reply_state FROM contact_state WHERE contact_key='uid:U3'"),
                "HR 主动招呼不得把联系人写成 REPLIED");
    }

    @Test
    void manualTakeoverContactIsNotOverwrittenBySync() {
        contactStateService.ensureOnConfirmedFirstContact("uid:U4", "U4", "赵女士", "接管公司",
                "2026-11-06 09:00:00");
        contactStateService.markState("uid:U4", new ContactStateService.MarkStateRequest(
                null, true, null, "人工接管", 0, null));
        when(reader.readChatList(page)).thenReturn(List.of(
                chat(0, "赵HR", "接管公司", false, false, false, "2", "你好", "12:30")));
        when(reader.detectInitiator(any(), any())).thenReturn(BossChatStatusReader.Initiator.SELF);

        Map<String, Object> result = service.syncActiveToday();

        assertEquals(0, result.get("replyStateSynced"), "人工接管优先：同步不得回写");
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT reply_state FROM contact_state WHERE contact_key='uid:U4'"));
        assertEquals(1, scalar(dataSource,
                "SELECT manual_takeover FROM contact_state WHERE contact_key='uid:U4'"),
                "人工接管标记不得被同步清除");
    }

    @Test
    void unknownReplyStatusDoesNotWriteBack() {
        contactStateService.ensureOnConfirmedFirstContact("uid:U5", "U5", "钱女士", "未回复公司",
                "2026-11-06 09:00:00");
        // 有投递记录、列表有自己已读标记、无未读红点、最后一条不是 HR → replyStatus=UNKNOWN
        insertAttempt("A5", "U5", "uid:U5", "未回复公司", "Java", startedToday("09:00:00"));
        when(reader.readChatList(page)).thenReturn(List.of(
                chat(0, "钱HR", "未回复公司", true, true, false, "", "", "09:30")));

        Map<String, Object> result = service.syncActiveToday();

        assertEquals(0, result.get("replyStateSynced"), "未确认 HR 回复时不得写回 REPLIED");
        assertEquals("UNKNOWN", scalarString(dataSource,
                "SELECT reply_state FROM contact_state WHERE contact_key='uid:U5'"));
    }

    // ==================== 工具 ====================

    private static String startedToday(String clock) {
        return today() + " " + clock;
    }

    private static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    private static String now() {
        return java.time.LocalDateTime.now(ZONE)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private void insertAttempt(String attemptId, String encryptUserId, String company, String job,
                               String startedAt) {
        insertAttempt(attemptId, encryptUserId, null, company, job, startedAt);
    }

    private void insertAttempt(String attemptId, String encryptUserId, String contactKey,
                               String company, String job, String startedAt) {
        execute(dataSource, "INSERT INTO delivery_attempt(attempt_id, run_id, account, kind, " +
                        "encrypt_id, encrypt_user_id, contact_key, job_name, company_name, text_status, " +
                        "image_status, started_at, created_at) VALUES(?, 'run-d2', 'local', 'FIRST_CONTACT', " +
                        "?, ?, ?, ?, ?, 'CONFIRMED', 'SKIPPED', ?, ?)",
                attemptId, "ENC-" + attemptId, encryptUserId, contactKey, job, company, startedAt, startedAt);
    }

    private void insertCompletedStartup(String targetDate) {
        repository.start(new DeliveryStatusRepository.StatusSyncRun(
                "startup-" + targetDate, "STARTUP", "PREVIOUS_DAY", targetDate, "RUNNING",
                targetDate + " 08:00:00", null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null));
        repository.finish("startup-" + targetDate, "COMPLETED",
                DeliveryStatusRepository.Counts.zero(), DeliveryStatusRepository.Counts.zero(),
                DeliveryStatusRepository.Counts.zero(), null);
    }

    private static BossChatStatusReader.ChatItem chat(int index, String hrName, String company,
                                                      boolean hasOwnStatus, boolean ownRead,
                                                      boolean ownDelivered, String unreadText,
                                                      String lastMessage, String chatTime) {
        int unreadCount = unreadText.isBlank() ? 0 : parseIntOrDefault(unreadText);
        return new BossChatStatusReader.ChatItem(index, hrName, company, "岗位", unreadText,
                unreadCount, hasOwnStatus, "", ownRead, ownDelivered, lastMessage, chatTime);
    }

    private static int parseIntOrDefault(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}