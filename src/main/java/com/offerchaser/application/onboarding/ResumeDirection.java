package com.offerchaser.application.onboarding;

import java.util.List;

/**
 * AI 推荐的求职方向。
 *
 * @param title      方向名称（用于模板方向与搜索关键词建议）
 * @param fit        匹配度 0~1（未知为 null）
 * @param reason     推荐理由
 * @param risks      该方向的风险/短板
 * @param keywords   建议的 BOSS 搜索关键词
 * @param industries 建议的行业名称（如 互联网/AI、企业服务、人工智能）
 */
public record ResumeDirection(String title, Double fit, String reason,
                              List<String> risks, List<String> keywords,
                              List<String> industries) {

    /** 兼容旧调用方的构造器（industries 为 null）。 */
    public ResumeDirection(String title, Double fit, String reason,
                           List<String> risks, List<String> keywords) {
        this(title, fit, reason, risks, keywords, List.of());
    }
}
