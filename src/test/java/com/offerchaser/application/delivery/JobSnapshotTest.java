package com.offerchaser.application.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JobSnapshot 单元测试：Builder 字段映射、身份/JD 完整性判断、快照哈希。
 */
class JobSnapshotTest {

    @Test
    void builderMapsAllFieldsInOrder() {
        JobSnapshot snapshot = JobSnapshot.builder()
                .encryptId("enc-1")
                .encryptUserId("user-1")
                .jobName("Java后端开发")
                .salary("20-40K·14薪")
                .city("上海")
                .experience("3-5年")
                .degree("本科")
                .jobDescription("负责后端服务开发，参与需求评审与架构设计")
                .companyName("某某科技有限公司")
                .industry("互联网")
                .financingStage("B轮")
                .companyScale("100-499人")
                .companyAddress("上海市浦东新区")
                .recruitmentStatus("招聘中")
                .hrName("李四")
                .hrPosition("技术招聘")
                .hrActiveStatus("今日活跃")
                .jobUrl("https://www.zhipin.com/job_detail/enc-1.html")
                .sourceKeyword("Java")
                .sourceCity("上海")
                .build();

        assertEquals("enc-1", snapshot.encryptId());
        assertEquals("user-1", snapshot.encryptUserId());
        assertEquals("Java后端开发", snapshot.jobName());
        assertEquals("20-40K·14薪", snapshot.salary());
        assertEquals("上海", snapshot.city());
        assertEquals("3-5年", snapshot.experience());
        assertEquals("本科", snapshot.degree());
        assertEquals("负责后端服务开发，参与需求评审与架构设计", snapshot.jobDescription());
        assertEquals("某某科技有限公司", snapshot.companyName());
        assertEquals("互联网", snapshot.industry());
        assertEquals("B轮", snapshot.financingStage());
        assertEquals("100-499人", snapshot.companyScale());
        assertEquals("上海市浦东新区", snapshot.companyAddress());
        assertEquals("招聘中", snapshot.recruitmentStatus());
        assertEquals("李四", snapshot.hrName());
        assertEquals("技术招聘", snapshot.hrPosition());
        assertEquals("今日活跃", snapshot.hrActiveStatus());
        assertEquals("https://www.zhipin.com/job_detail/enc-1.html", snapshot.jobUrl());
        assertEquals("Java", snapshot.sourceKeyword());
        assertEquals("上海", snapshot.sourceCity());
    }

    @Test
    void hasIdentityRequiresNonBlankEncryptId() {
        assertTrue(JobSnapshot.builder().encryptId("enc-1").build().hasIdentity());
        assertFalse(JobSnapshot.builder().encryptId(null).build().hasIdentity());
        assertFalse(JobSnapshot.builder().encryptId("").build().hasIdentity());
        assertFalse(JobSnapshot.builder().encryptId("   ").build().hasIdentity());
    }

    @Test
    void hasEssentialContentRequiresJobNameAndTenNonWhitespaceChars() {
        assertTrue(JobSnapshot.builder()
                .jobName("Java开发")
                .jobDescription("0123456789")
                .build().hasEssentialContent(), "恰好 10 个非空白字符应通过");

        assertTrue(JobSnapshot.builder()
                .jobName("Java开发")
                .jobDescription("01234\n\t56789")
                .build().hasEssentialContent(), "换行/制表符不计入长度");

        assertFalse(JobSnapshot.builder()
                .jobName("Java开发")
                .jobDescription("012345678")
                .build().hasEssentialContent(), "9 个字符不足");

        assertFalse(JobSnapshot.builder()
                .jobName("Java开发")
                .jobDescription(" 0 1 2 3 4 5 6 7 8 ")
                .build().hasEssentialContent(), "去空白后仅 9 个字符");

        assertFalse(JobSnapshot.builder()
                .jobName("Java开发")
                .jobDescription(null)
                .build().hasEssentialContent());

        assertFalse(JobSnapshot.builder()
                .jobName("   ")
                .jobDescription("负责后端服务开发，参与需求评审与架构设计")
                .build().hasEssentialContent(), "岗位名为空白应视为缺失");
    }

    @Test
    void snapshotHashIsStableSha256Hex() {
        JobSnapshot snapshot = JobSnapshot.builder()
                .encryptId("abc")
                .jobName("Java开发")
                .salary("20-40K")
                .jobDescription("负责后端服务开发")
                .build();

        String hash = snapshot.snapshotHash();

        assertTrue(hash.matches("[0-9a-f]{64}"), "应为 64 位小写 hex：" + hash);
        // 固定向量：sha256("abc\nJava开发\n20-40K\n负责后端服务开发")
        assertEquals("447f9487a810d5cff8bb2668cf741bd7219ce8e69021862bb33b71191a324044", hash);
        assertEquals(hash, snapshot.snapshotHash(), "同一内容必须得到同一哈希");
    }

    @Test
    void snapshotHashIgnoresFieldsOutsideContract() {
        JobSnapshot first = JobSnapshot.builder()
                .encryptId("abc").jobName("Java开发").salary("20-40K").jobDescription("负责后端服务开发")
                .city("上海")
                .build();
        JobSnapshot second = JobSnapshot.builder()
                .encryptId("abc").jobName("Java开发").salary("20-40K").jobDescription("负责后端服务开发")
                .city("北京")
                .build();

        assertEquals(first.snapshotHash(), second.snapshotHash(),
                "哈希只覆盖 encryptId/jobName/salary/jobDescription 四个字段");
    }

    @Test
    void snapshotHashChangesWhenCoveredFieldChanges() {
        JobSnapshot base = JobSnapshot.builder()
                .encryptId("abc").jobName("Java开发").salary("20-40K").jobDescription("负责后端服务开发")
                .build();

        assertNotEquals(base.snapshotHash(), JobSnapshot.builder()
                .encryptId("abc").jobName("Java开发").salary("25-45K").jobDescription("负责后端服务开发")
                .build().snapshotHash());
        assertNotEquals(base.snapshotHash(), JobSnapshot.builder()
                .encryptId("abc2").jobName("Java开发").salary("20-40K").jobDescription("负责后端服务开发")
                .build().snapshotHash());
        assertNotEquals(base.snapshotHash(), JobSnapshot.builder()
                .encryptId("abc").jobName("Java开发").salary("20-40K").jobDescription("负责后端服务开发，参与架构设计")
                .build().snapshotHash());
    }

    @Test
    void snapshotHashTreatsNullAsEmptyString() {
        JobSnapshot allNull = JobSnapshot.builder().build();
        JobSnapshot allEmpty = JobSnapshot.builder()
                .encryptId("").jobName("").salary("").jobDescription("")
                .build();

        assertEquals(allEmpty.snapshotHash(), allNull.snapshotHash());
    }
}
