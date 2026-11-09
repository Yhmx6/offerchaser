package com.offerchaser.application.onboarding;

import java.util.List;

/**
 * 一次简历分析的返回体（analyze / analyze-text 的 data）。
 *
 * @param profile     候选人画像
 * @param directions  AI 推荐方向（可能为空，前端会让用户手填）
 * @param salaryBands AI 建议薪资区间（可能为空）
 * @param warnings    合并后的提示（画像提示 + 生成过程的降级说明）
 * @param visionUsed  是否使用了视觉（图片）解析
 */
public record ResumeAnalysis(ResumeProfile profile, List<ResumeDirection> directions,
                             List<ResumeSalaryBand> salaryBands, List<String> warnings,
                             boolean visionUsed) {
}
