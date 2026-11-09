package com.offerchaser.application.controller;

import com.offerchaser.application.onboarding.AiDraftRequest;
import com.offerchaser.application.onboarding.AiDraftResult;
import com.offerchaser.application.onboarding.OnboardingException;
import com.offerchaser.application.onboarding.ResumeAnalysis;
import com.offerchaser.application.onboarding.ResumeAnalysisService;
import com.offerchaser.application.onboarding.TemplateDraftService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「AI 帮我建模板」后端入口。
 * <p>
 * 路由（均为写接口，自动受本机会话认证 + CSRF 头保护，不做登录校验之外的额外放行）：
 * <ul>
 *   <li>{@code POST /api/boss/resume/analyze}：multipart 简历图片 → 视觉解析画像 + 方向/薪资建议；</li>
 *   <li>{@code POST /api/boss/resume/analyze-text}：JSON {{@code {"text":"..."}}} → 文本解析（视觉兜底）；</li>
 *   <li>{@code POST /api/boss/templates/ai-draft}：已确认画像 + 问答 → 模板草稿（不创建/不激活/不落库）。</li>
 * </ul>
 * 只做分析与草稿生成：不保存简历、不写数据库、不触发投递。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/boss")
@RequiredArgsConstructor
public class ResumeOnboardingController {

    private final ResumeAnalysisService resumeAnalysisService;
    private final TemplateDraftService templateDraftService;

    /**
     * 分析简历图片（视觉解析）。
     *
     * @param file       multipart 文件（缺省时由服务返回 INVALID_IMAGE）
     * @param supplement 候选人补充说明（可选，最多 1000 字，不保存）
     * @return {@code {success:true,data:{profile,directions,salaryBands,warnings,visionUsed}}}
     */
    @PostMapping(value = "/resume/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> analyzeResume(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "supplement", required = false) String supplement) {
        ResumeAnalysis analysis = supplement == null || supplement.isBlank()
                ? resumeAnalysisService.analyzeImage(file)
                : resumeAnalysisService.analyzeImage(file, supplement);
        log.info("简历图片分析完成 | visionUsed={} | directions={} | supplementChars={}",
                analysis.visionUsed(), analysis.directions().size(),
                supplement == null ? 0 : supplement.trim().length());
        return ok(analysis);
    }

    /**
     * 分析粘贴的简历文本（视觉不可用时的兜底）。
     *
     * @param body JSON {{@code {"text":"...","supplement":"..."}}}；supplement 可选，最多 1000 字
     * @return 与图片分析相同的 data 结构，visionUsed=false
     */
    @PostMapping("/resume/analyze-text")
    public ResponseEntity<Map<String, Object>> analyzeResumeText(
            @RequestBody(required = false) Map<String, Object> body) {
        Object rawText = body == null ? null : body.get("text");
        if (rawText != null && !(rawText instanceof String)) {
            throw OnboardingException.invalidText("text 必须是字符串");
        }
        Object rawSupplement = body == null ? null : body.get("supplement");
        if (rawSupplement != null && !(rawSupplement instanceof String)) {
            throw OnboardingException.invalidText("supplement 必须是字符串");
        }
        String supplement = (String) rawSupplement;
        ResumeAnalysis analysis = supplement == null || supplement.isBlank()
                ? resumeAnalysisService.analyzeText((String) rawText)
                : resumeAnalysisService.analyzeText((String) rawText, supplement);
        log.info("简历文本分析完成 | visionUsed=false | directions={} | supplementChars={}",
                analysis.directions().size(), supplement == null ? 0 : supplement.trim().length());
        return ok(analysis);
    }

    /**
     * 根据已确认画像与向导问答生成模板草稿。
     *
     * @param request 草稿请求（profile/direction/keywords/cityCode/薪资/exclusions/intensity/sendImgResume/templateName）
     * @return {@code {success:true,data:{name,direction,description,config,warnings}}}
     */
    @PostMapping("/templates/ai-draft")
    public ResponseEntity<Map<String, Object>> generateTemplateDraft(
            @RequestBody(required = false) AiDraftRequest request) {
        AiDraftResult draft = templateDraftService.generate(request);
        log.info("模板草稿生成完成 | direction={} | keywords={} | warnings={}",
                draft.direction(), draft.config().keywords().size(), draft.warnings().size());
        return ok(draft);
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }
}
