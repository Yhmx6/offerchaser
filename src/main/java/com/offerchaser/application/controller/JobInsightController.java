package com.offerchaser.application.controller;

import com.offerchaser.application.insight.JobInsightService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「岗位解读」只读接口。
 *
 * <p>读取 BOSS 岗位链接并使用当前 AI 配置生成沟通建议；不写数据库、不发送消息、不投递。
 * 这个入口只做求职准备，不改变任何真实投递/联系人状态。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/boss/jobs")
@RequiredArgsConstructor
public class JobInsightController {

    private final JobInsightService jobInsightService;

    /**
     * POST /api/boss/jobs/insight
     * 请求：{@code {"url":"https://www.zhipin.com/job_detail/....html"}}
     * 返回：{@code {success:true,data:{job:{...},insight:{...}}}}
     */
    @PostMapping("/insight")
    public ResponseEntity<Map<String, Object>> analyzeJob(
            @RequestBody(required = false) Map<String, Object> body) {
        Object rawUrl = body == null ? null : body.get("url");
        if (rawUrl != null && !(rawUrl instanceof String)) {
            return failure("INVALID_JOB_URL", "url 必须是字符串", 400);
        }
        try {
            JobInsightService.JobInsightResult result =
                    jobInsightService.analyze((String) rawUrl);
            log.info("岗位解读完成 | job={} | company={}",
                    result.job().jobName(), result.job().companyName());
            return ok(result);
        } catch (JobInsightService.JobInsightException insightError) {
            log.info("岗位解读失败 | code={} | message={}", insightError.code(), insightError.getMessage());
            return failure(insightError.code(), insightError.getMessage(), insightError.status().value());
        } catch (Exception unexpected) {
            log.warn("岗位解读异常：{}", unexpected.getMessage());
            return failure("JOB_INSIGHT_FAILED", "岗位解读失败，请稍后重试", 500);
        }
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    private static ResponseEntity<Map<String, Object>> failure(String code, String message, int status) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("code", code);
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }
}
