package com.offerchaser.application.onboarding;

import com.offerchaser.application.templates.ProfileTemplateService.TemplateConfigRequest;

import java.util.List;

/**
 * 「AI 帮我建模板」草稿生成结果（只是草稿：不创建、不激活、不调用 AI 写入）。
 *
 * @param name        建议的模板名称
 * @param direction   模板方向
 * @param description 草稿说明
 * @param config      可直接提交给现有模板配置保存接口的配置
 * @param warnings    草稿提醒（缺省项/被忽略项）
 */
public record AiDraftResult(String name, String direction, String description,
                            TemplateConfigRequest config, List<String> warnings) {
}
