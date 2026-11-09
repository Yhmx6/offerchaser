package com.offerchaser.application.controller;

import com.offerchaser.application.insight.JobInsightService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 岗位解读控制器契约测试；Service mock，不启动浏览器、不联网。 */
class JobInsightControllerTest {

    private JobInsightService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(JobInsightService.class);
        JobInsightController controller = new JobInsightController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void returnsJobAndInsightEnvelope() throws Exception {
        when(service.analyze(anyString())).thenReturn(new JobInsightService.JobInsightResult(
                new JobInsightService.JobView("AI 产品经理", "测试公司", "20-30K", "上海",
                        "3-5年", "本科", "互联网/AI", "A轮", "100-499人", "上海",
                        "https://www.zhipin.com/job_detail/test.html", "负责 AI 产品规划与落地"),
                new JobInsightService.InsightView(
                        "岗位摘要", "公司摘要",
                        List.of("匹配点"), List.of("缺口"), List.of("风险"),
                        List.of("沟通建议"), "开场话术", List.of(), List.of("问 HR"), List.of("避免"), 0.8, "需核实")));

        mockMvc.perform(post("/api/boss/jobs/insight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://www.zhipin.com/job_detail/test.html\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.job.jobName").value("AI 产品经理"))
                .andExpect(jsonPath("$.data.insight.openingScript").value("开场话术"));
    }

    @Test
    void rejectsNonStringUrl() throws Exception {
        mockMvc.perform(post("/api/boss/jobs/insight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":123}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_JOB_URL"));
    }
}
