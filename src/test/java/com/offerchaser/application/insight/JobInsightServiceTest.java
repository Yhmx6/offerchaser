package com.offerchaser.application.insight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 岗位解读入口的纯校验测试：不启动浏览器、不调用 AI。 */
class JobInsightServiceTest {

    private final JobInsightService service = new JobInsightService(null, null, null);

    @Test
    void rejectsNonBossUrlBeforeTouchingBrowserOrAi() {
        JobInsightService.JobInsightException error = assertThrows(
                JobInsightService.JobInsightException.class,
                () -> service.analyze("https://example.com/job_detail/x.html"));
        assertEquals("INVALID_JOB_URL", error.code());
        assertTrue(error.getMessage().contains("BOSS"));
    }

    @Test
    void rejectsBossHomePageInsteadOfJobDetail() {
        JobInsightService.JobInsightException error = assertThrows(
                JobInsightService.JobInsightException.class,
                () -> service.analyze("https://www.zhipin.com/web/geek/jobs"));
        assertEquals("INVALID_JOB_URL", error.code());
    }

    @Test
    void rejectsBlankUrl() {
        JobInsightService.JobInsightException error = assertThrows(
                JobInsightService.JobInsightException.class,
                () -> service.analyze("  "));
        assertEquals("INVALID_JOB_URL", error.code());
    }
}
