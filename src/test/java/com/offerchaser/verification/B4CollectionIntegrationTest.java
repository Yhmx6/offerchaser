package com.offerchaser.verification;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.offerchaser.application.ai.AiMatchService;
import com.offerchaser.application.contacts.ContactStateService;
import com.offerchaser.application.contacts.FollowupService;
import com.offerchaser.application.entity.BossJobDataEntity;
import com.offerchaser.application.mapper.BlacklistMapper;
import com.offerchaser.application.mapper.BossConfigMapper;
import com.offerchaser.application.mapper.BossIndustryMapper;
import com.offerchaser.application.mapper.BossJobDataMapper;
import com.offerchaser.application.mapper.BossOptionMapper;
import com.offerchaser.application.persistence.BudgetService;
import com.offerchaser.application.persistence.DeliveryLedgerService;
import com.offerchaser.application.persistence.EvaluationRepository;
import com.offerchaser.application.persistence.JobSourceRepository;
import com.offerchaser.application.service.AiService;
import com.offerchaser.application.service.BossResumeImageService;
import com.offerchaser.application.service.BossService;
import com.offerchaser.worker.boss.Boss;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.offerchaser.verification.VerificationSupport.dataSource;
import static com.offerchaser.verification.VerificationSupport.execute;
import static com.offerchaser.verification.VerificationSupport.migrate;
import static com.offerchaser.verification.VerificationSupport.scalar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B4 采集幂等/溯源与采集有限化代码契约验收（不启动浏览器）：
 * job_source 幂等（同岗位同关键词 1 条、不同关键词 2 条）、boss_data first_seen_at 不被覆盖、
 * upsert 更新 last_detail_at/snapshot_hash、投递终态不被快照回退；
 * Boss 有限采集参数/停止原因/去重/来源记录调用点（源码 + 反射 + 字段默认值）。
 */
class B4CollectionIntegrationTest {

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private JobSourceRepository jobSourceRepository;
    private BossJobDataMapper bossJobDataMapper;
    private BossService bossService;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = dataSource(tempDir.resolve("b4.db"));
        execute(dataSource, "CREATE TABLE IF NOT EXISTS boss_data (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "encrypt_id TEXT, encrypt_user_id TEXT, company_name TEXT, job_name TEXT, salary TEXT, " +
                "location TEXT, experience TEXT, degree TEXT, hr_name TEXT, hr_position TEXT, " +
                "hr_active_status TEXT, delivery_status TEXT, job_description TEXT, job_url TEXT, " +
                "recruitment_status TEXT, company_address TEXT, industry TEXT, introduce TEXT, " +
                "financing_stage TEXT, company_scale TEXT, created_at TEXT, updated_at TEXT)");
        new com.offerchaser.application.persistence.SchemaMigrationService(dataSource,
                new com.offerchaser.application.persistence.DatabaseMaintenanceService(dataSource)).migrate();
        jobSourceRepository = new JobSourceRepository(dataSource);
        bossJobDataMapper = mock(BossJobDataMapper.class);
        bossService = new BossService(
                mock(BossOptionMapper.class),
                mock(BossIndustryMapper.class),
                mock(BossConfigMapper.class),
                mock(BlacklistMapper.class),
                bossJobDataMapper,
                dataSource,
                jobSourceRepository);
    }

    // ==================== job_source 幂等 / 多关键词来源 ====================

    @Test
    void recordJobSourceIsIdempotentPerJobAndKeyword() {
        insertBossDataRow("ENC-S1", "USER-S1");

        boolean first = bossService.recordJobSource("Java", "101020100", "ENC-S1", "hash-1");
        boolean duplicate = bossService.recordJobSource("Java", "101020100", "ENC-S1", "hash-1");

        assertTrue(first, "首次采集应新增来源关联");
        assertFalse(duplicate, "同岗位同关键词重复采集不得重复写来源");
        assertEquals(1, jobSourceRepository.countRecords("local", null, null));
        assertEquals(1, scalar(dataSource, "SELECT COUNT(*) FROM boss_data WHERE encrypt_id='ENC-S1'"),
                "来源记录不得重复写岗位主表");
    }

    @Test
    void sameJobWithDifferentKeywordsKeepsTwoSourceRowsButOneJob() {
        insertBossDataRow("ENC-S1", "USER-S1");

        assertTrue(bossService.recordJobSource("Java", "101020100", "ENC-S1", "hash-1"));
        assertTrue(bossService.recordJobSource("后端", "101020100", "ENC-S1", "hash-1"));
        assertFalse(bossService.recordJobSource("Java", "101020100", "ENC-S1", "hash-2"));
        assertFalse(bossService.recordJobSource("Java", "101020100", "ENC-S1", "hash-2"));

        assertEquals(2, jobSourceRepository.countRecords("local", null, null),
                "同岗位不同关键词 → 2 条来源关联");
        assertEquals(1, jobSourceRepository.countUniqueJobs("local", null, null));
        assertEquals(1, scalar(dataSource, "SELECT COUNT(*) FROM boss_data WHERE encrypt_id='ENC-S1'"),
                "岗位主表仍只有 1 条");
        List<java.util.Map<String, Object>> summary = jobSourceRepository.summaryByKeyword("local", null, null);
        assertEquals(2, summary.size());
    }

    @Test
    void recordJobSourceSkipsBlankIdentifierAndNeverThrows() {
        assertFalse(bossService.recordJobSource("Java", "101020100", null, "h"));
        assertFalse(bossService.recordJobSource("Java", "101020100", " ", "h"));
        assertEquals(0, scalar(dataSource, "SELECT COUNT(*) FROM job_source"));
    }

    // ==================== boss_data first_seen/快照溯源 ====================

    @Test
    void insertBossJobWritesFirstSeenAtOnce() {
        when(bossJobDataMapper.insert(any(BossJobDataEntity.class))).thenReturn(1);
        BossJobDataEntity entity = new BossJobDataEntity();
        entity.setEncryptId("ENC-I1");
        entity.setJobName("Java 开发");

        bossService.insertBossJob(entity);

        ArgumentCaptor<BossJobDataEntity> captor = ArgumentCaptor.forClass(BossJobDataEntity.class);
        verify(bossJobDataMapper).insert(captor.capture());
        BossJobDataEntity inserted = captor.getValue();
        assertNotNull(inserted.getFirstSeenAt(), "首次写入必须落 first_seen_at");
        assertNotNull(inserted.getLastDetailAt());
        assertNotNull(inserted.getCreatedAt());
        assertNotNull(inserted.getUpdatedAt());
    }

    @Test
    void upsertNewJobSetsFirstSeenAtAndSnapshot() {
        when(bossJobDataMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(bossJobDataMapper.insert(any(BossJobDataEntity.class))).thenReturn(1);
        BossJobDataEntity entity = new BossJobDataEntity();
        entity.setEncryptId("ENC-U1");
        entity.setEncryptUserId("USER-U1");
        entity.setJobName("Java 开发");
        entity.setSnapshotHash("hash-new");

        bossService.upsertBossJob(entity);

        ArgumentCaptor<BossJobDataEntity> captor = ArgumentCaptor.forClass(BossJobDataEntity.class);
        verify(bossJobDataMapper).insert(captor.capture());
        BossJobDataEntity inserted = captor.getValue();
        assertNotNull(inserted.getFirstSeenAt(), "新岗位首次发现时间必须写入");
        assertNotNull(inserted.getLastDetailAt());
        assertEquals("hash-new", inserted.getSnapshotHash());
        assertEquals("未投递", inserted.getDeliveryStatus(), "新岗位默认未投递");
    }

    @Test
    void upsertExistingJobKeepsFirstSeenButRefreshesDetailAndSnapshot() {
        LocalDateTime oldFirstSeen = LocalDateTime.of(2026, 11, 1, 10, 0, 0);
        BossJobDataEntity existing = new BossJobDataEntity();
        existing.setId(1L);
        existing.setEncryptId("ENC-U1");
        existing.setEncryptUserId("USER-U1");
        existing.setJobName("旧岗位名");
        existing.setFirstSeenAt(oldFirstSeen);
        existing.setSnapshotHash("hash-old");
        existing.setDeliveryStatus("已投递");
        when(bossJobDataMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(bossJobDataMapper.updateById(any(BossJobDataEntity.class))).thenReturn(1);

        BossJobDataEntity incoming = new BossJobDataEntity();
        incoming.setEncryptId("ENC-U1");
        incoming.setEncryptUserId("USER-U1");
        incoming.setJobName("刷新后的岗位名");
        incoming.setSnapshotHash("hash-new");
        incoming.setDeliveryStatus("未投递"); // 快照试图回退终态，应被保护
        bossService.upsertBossJob(incoming);

        ArgumentCaptor<BossJobDataEntity> captor = ArgumentCaptor.forClass(BossJobDataEntity.class);
        verify(bossJobDataMapper).updateById(captor.capture());
        BossJobDataEntity updated = captor.getValue();
        assertEquals(oldFirstSeen, updated.getFirstSeenAt(), "重复采集不得覆盖 first_seen_at");
        assertEquals("hash-new", updated.getSnapshotHash(), "快照哈希应刷新");
        assertNotNull(updated.getLastDetailAt(), "最近详情时间应刷新");
        assertEquals("刷新后的岗位名", updated.getJobName());
        assertEquals("已投递", updated.getDeliveryStatus(), "投递终态不得被快照回退");
        assertSame(existing, updated);
    }

    // ==================== 采集有限化代码契约 ====================

    @Test
    void bossHasCollectionLimitsAndRealStopReasons() throws Exception {
        Boss boss = newBoss();
        assertEquals(60, ((Integer) ReflectionTestUtils.getField(boss, "collectMaxPerKeyword")).intValue());
        assertEquals(15, ((Integer) ReflectionTestUtils.getField(boss, "collectMaxDurationMinutes")).intValue());
        assertEquals(30, ((Integer) ReflectionTestUtils.getField(boss, "collectNoNewWindowSeconds")).intValue());
        Object processed = ReflectionTestUtils.getField(boss, "processedEncryptIds");
        assertTrue(processed instanceof Set, "本轮去重集合 processedEncryptIds 必须存在");

        String source = Files.readString(Path.of("src/main/java/com/offerchaser/worker/boss/Boss.java"));
        assertTrue(source.contains("collect-max-per-keyword:60"), "缺少每关键词上限配置");
        assertTrue(source.contains("collect-max-duration-minutes:15"), "缺少采集时长上限配置");
        assertTrue(source.contains("collect-no-new-window-seconds:30"), "缺少无新增窗口配置");
        assertTrue(source.contains("达到采集时长上限"), "停止原因必须说明达到时长上限");
        assertTrue(source.contains("达到每关键词候选上限"), "停止原因必须说明候选上限");
        assertTrue(source.contains("秒无新增，判定加载结束"), "加载结束必须用无新增时间窗判定");
        assertTrue(source.contains("岗位采集结束（\" + collection.stopReason()"), "进度提示必须携带真实停止原因");
        assertFalse(source.contains("岗位全部加载完毕"),
                "不得再用统一的“岗位全部加载完毕”掩盖真实停止原因");
        assertTrue(source.contains("lastGrowthAt") && source.contains("noNewCardsLongEnough"), "加载循环必须记录最后新增时间");
        assertTrue(source.contains("bossService.recordJobSource(keyword, cityCode, snapshot.encryptId(), "
                        + "snapshot.snapshotHash())"),
                "handleJobCard 必须记录来源（幂等）");
        assertTrue(source.contains("processedEncryptIds.add(snapshot.encryptId())"),
                "虚拟列表重排/多关键词必须按 encryptId 去重");
        assertTrue(source.contains("int cardLimit = effectiveCollectMaxPerKeyword()")
                        && source.contains("Math.min(collection.loadedCount(), cardLimit)"),
                "遍历卡片数必须受每关键词候选上限约束（D1：优先使用模板采集策略）");

        // 旧 4 参 handleJobCard 兼容重载必须保留，供旧测试/旧调用
        Method fourParams = Boss.class.getDeclaredMethod("handleJobCard",
                String.class, int.class, int.class, com.microsoft.playwright.Response.class);
        assertTrue(Modifier.isPrivate(fourParams.getModifiers()));
        Method fiveParams = Boss.class.getDeclaredMethod("handleJobCard",
                String.class, int.class, int.class, com.microsoft.playwright.Response.class, String.class);
        assertNotNull(fiveParams);
    }

    @Test
    void bossSourceDedupesRepeatedCardsBeforeEvaluation() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/offerchaser/worker/boss/Boss.java"));
        int sourceRecord = source.indexOf("bossService.recordJobSource(");
        int dedupe = source.indexOf("processedEncryptIds.add(snapshot.encryptId())");
        int earlyReturn = source.indexOf("岗位已处理过，仅更新来源");
        assertTrue(sourceRecord > 0 && dedupe > 0 && earlyReturn > 0, "来源记录/去重/提前返回必须同时存在");
        assertTrue(sourceRecord < dedupe, "先记录来源，再判断是否本轮重复处理");
        assertTrue(dedupe < earlyReturn, "重复岗位直接返回（不再评估/发送）");
    }

    // ==================== 工具 ====================

    private Boss newBoss() {
        return new Boss(
                mock(com.offerchaser.application.service.BossService.class),
                mock(AiService.class),
                mock(AiMatchService.class),
                mock(BossResumeImageService.class),
                mock(BudgetService.class),
                mock(DeliveryLedgerService.class),
                mock(EvaluationRepository.class),
                mock(ContactStateService.class),
                mock(FollowupService.class));
    }

    private void insertBossDataRow(String encryptId, String encryptUserId) {
        execute(dataSource, "INSERT INTO boss_data(encrypt_id, encrypt_user_id, company_name, job_name, " +
                        "delivery_status, created_at, updated_at) VALUES(?, ?, '示例科技', 'Java 开发', " +
                        "'未投递', '2026-11-01 10:00:00', '2026-11-01 10:00:00')",
                encryptId, encryptUserId);
    }

    @SuppressWarnings("unused")
    private static boolean noop() {
        return true;
    }
}