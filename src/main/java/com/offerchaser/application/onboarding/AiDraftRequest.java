package com.offerchaser.application.onboarding;

import java.util.List;

/**
 * 「AI 帮我建模板」向导生成草稿的请求体（前端字段一一对应）。
 *
 * @param profile            用户已确认的简历画像
 * @param direction          用户选定的求职方向
 * @param keywords           搜索关键词
 * @param cityCode           旧版城市字段（城市名称；新请求优先使用 cities，仍然兼容只传 cityCode）
 * @param expectedSalaryMin  期望月薪下限（K）
 * @param expectedSalaryMax  期望月薪上限（K）
 * @param exclusions         排除词（进入规则黑名单）
 * @param intensity          投递强度：trial / normal / active
 * @param sendImgResume      是否自动发送图片简历（仍走图片确认）
 * @param templateName       模板名称
 * @param cities             城市名称列表（最多 4 个；第一个作为模板城市，全部进入规则城市条件）
 * @param industries         用户确认的行业名称（最多 6 个；写入模板行业条件）
 */
public record AiDraftRequest(
        ResumeProfile profile,
        String direction,
        List<String> keywords,
        String cityCode,
        Integer expectedSalaryMin,
        Integer expectedSalaryMax,
        List<String> exclusions,
        String intensity,
        Boolean sendImgResume,
        String templateName,
        List<String> cities,
        List<String> industries) {

    /** 兼容旧调用方（只有 cityCode，没有 cities/industries）。 */
    public AiDraftRequest(ResumeProfile profile, String direction, List<String> keywords,
                          String cityCode, Integer expectedSalaryMin, Integer expectedSalaryMax,
                          List<String> exclusions, String intensity, Boolean sendImgResume,
                          String templateName) {
        this(profile, direction, keywords, cityCode, expectedSalaryMin, expectedSalaryMax,
                exclusions, intensity, sendImgResume, templateName, null, null);
    }
}
