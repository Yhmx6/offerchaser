package com.offerchaser.verification;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「AI 帮我建模板」第二轮向导的独立前端交付检查：
 * portal 弹窗、画像只读无薪资输入、城市搜索多选≤4（首个主搜索城市）、默认排除 chips+自定义合并、
 * 行业偏好≤6、草稿请求 cities/industries/cityCode=first、预览学历/经验/公司规模/融资阶段、默认不激活。
 */
class AiTemplateWizardFrontendDeliveryCheckTest {

    private static final Path FRONT = Path.of("front");
    private static final Path DIST = Path.of("src", "main", "resources", "dist");
    private static final Path WIZARD = FRONT.resolve("app/components/setup/AiTemplateWizard.tsx");
    private static final Path SETUP = FRONT.resolve("app/boss/setup/page.tsx");
    private static final Path API = FRONT.resolve("lib/api.ts");
    private static final Path TYPES = FRONT.resolve("lib/ai-template-types.ts");

    @Test
    void wizardAndIntegrationFilesExist() throws Exception {
        assertTrue(Files.isRegularFile(WIZARD), "缺少 AI 建模板向导组件");
        assertTrue(Files.size(WIZARD) > 60_000, "向导组件过小，疑似占位：" + Files.size(WIZARD));
        assertTrue(Files.isRegularFile(TYPES), "缺少向导类型文件 lib/ai-template-types.ts");
        assertTrue(Files.isRegularFile(SETUP), "缺少 /boss/setup 页面");

        String setup = Files.readString(SETUP);
        assertTrue(setup.contains("import AiTemplateWizard"), "配置空间必须引入向导组件");
        assertTrue(setup.contains("AI 帮我建模板"), "配置空间必须有「AI 帮我建模板」入口");
        assertTrue(setup.contains("setAiWizardOpen(true)") && setup.contains("<AiTemplateWizard"),
                "入口按钮必须打开并挂载向导");
        assertTrue(setup.contains("onOpenModelService={openModelService}"),
                "AI 未配置时应能跳到全局模型服务卡片");
        assertTrue(setup.contains("onSaved={(template, activated)"),
                "向导保存后应刷新模板列表并区分草稿/激活提示");
    }

    @Test
    void wizardUsesReactPortalForViewportSafety() throws Exception {
        String wizard = Files.readString(WIZARD);
        assertTrue(wizard.contains("import { DialogShell } from '@/components/ui/dialog-shell'"),
                "向导必须使用统一 DialogShell");
        assertTrue(wizard.contains("<DialogShell"), "缺少 DialogShell 渲染");
        assertTrue(wizard.contains("bare"), "向导应使用无默认标题的 DialogShell");
        assertTrue(wizard.contains("typeof document === 'undefined'"),
                "SSR 下必须避免直接访问 document");
        assertTrue(wizard.contains("max-h-[76vh]") && wizard.contains("DialogShell"),
                "弹窗应限制高度并通过 DialogShell 居中，避免超出视口");
    }

    @Test
    void wizardCoversSecondRoundSteps() throws Exception {
        String wizard = Files.readString(WIZARD);
        for (String step : List.of("'upload'", "'analyzing'", "'profile'", "'direction'",
                "'qa-city'", "'qa-salary'", "'qa-exclusions'", "'qa-intensity'", "'qa-image'",
                "'drafting'", "'preview'", "'done'")) {
            assertTrue(wizard.contains(step), "向导缺少步骤：" + step);
        }
        for (String title : List.of("希望优先看哪些城市？（可多选，最多 4 个）",
                "期望月薪下限是多少？", "有哪些岗位/公司/词需要排除？",
                "投递强度选哪一档？", "需要自动发送图片简历吗？")) {
            assertTrue(wizard.contains(title), "缺少逐条问题：" + title);
        }
        for (String text : List.of("隐私说明", "我已阅读并同意上述隐私说明（必选）",
                "上传简历图片", "粘贴简历文本", "跳过上传，手动填写画像",
                "跳过，手动填写画像", "确认识别出的画像（可编辑）",
                "选择一个投递方向", "预览草稿", "保存为草稿", "保存并激活")) {
            assertTrue(wizard.contains(text), "向导缺少入口/步骤文案：" + text);
        }
        assertTrue(wizard.contains("QA_STEPS") && wizard.contains("5 个问题"),
                "逐条问答应为 5 个问题");
    }

    @Test
    void profileStepHasNoSalaryInputsAndReadonlyAiFields() throws Exception {
        String wizard = Files.readString(WIZARD);
        int profileStart = wizard.indexOf("function ProfileStep(");
        int directionStart = wizard.indexOf("function DirectionStep(");
        assertTrue(profileStart > 0 && directionStart > profileStart, "缺少画像/方向步骤组件");
        String profileStep = wizard.substring(profileStart, directionStart);

        assertFalse(profileStep.contains("form.expectedSalary")
                        || profileStep.contains("type=\"number\"")
                        || profileStep.contains("setForm({ ...form, expectedSalary"),
                "画像确认步骤不得再有可编辑的期望薪资/数字输入（只允许只读展示画像建议）");
        for (String text : List.of("专业", "毕业年份", "AI 判断最高学历（只读）",
                "AI 判断工作经验（只读）", "AI 建议薪资（只读，下一步问答中确认）",
                "学历/经验由后端按画像自动带入草稿，问答不再重复询问")) {
            assertTrue(profileStep.contains(text), "画像只读区缺少：" + text);
        }
        assertTrue(profileStep.contains("profile?.major") && profileStep.contains("profile?.graduationYear"),
                "画像应展示 AI 识别的专业/毕业年份");
        assertTrue(profileStep.contains("yearsExperience") && profileStep.contains("experienceLabel"),
                "画像应只读展示年限与 BOSS 经验档");

        // 薪资只在问答里确认
        int qaStart = wizard.indexOf("function QaStep(");
        String qaStep = wizard.substring(qaStart, wizard.indexOf("function PreviewStep("));
        assertTrue(qaStep.contains("月薪下限（K，只设下限）"),
                "薪资问答只保留下限输入");
        assertFalse(qaStep.contains("月薪上限（K）"), "薪资问答不得再有上限输入");
        assertTrue(qaStep.contains("只设下限，不设上限"), "薪资问答应说明只设下限");
    }

    @Test
    void cityQaSupportsSearchMultiSelectMaxFour() throws Exception {
        String wizard = Files.readString(WIZARD);
        assertTrue(wizard.contains("const MAX_CITIES = 4"), "城市上限必须是 4");
        assertTrue(wizard.contains("cities: string[]") && wizard.contains("answers.cities"),
                "城市答案必须是数组");
        for (String text : List.of("搜索城市，如：上海 / 杭州", "城市候选", "（主搜索城市）",
                "已选 {answers.cities.length}/{MAX_CITIES}", "城市选项不可用：手动输入城市",
                "第 1 个城市作为模板主搜索城市（config.cityCode）")) {
            assertTrue(wizard.contains(text), "城市多选/主城市标注缺失：" + text);
        }
        assertTrue(wizard.contains("answers.cities.length >= MAX_CITIES"),
                "超过 4 个必须有前端拦截");
        assertTrue(wizard.contains("最多选择 ${MAX_CITIES} 个城市"),
                "超过上限必须给出明确提示");
        assertTrue(wizard.contains("remove") || wizard.contains("移除 ${city}"),
                "必须能移除已选城市");
    }

    @Test
    void exclusionsDefaultChipsAndCustomTextAreMerged() throws Exception {
        String wizard = Files.readString(WIZARD);
        assertTrue(wizard.contains(
                        "const DEFAULT_EXCLUSIONS = ['外包', '销售', '保险', '房产中介', '培训', '劳务派遣']"),
                "默认排除项必须为约定的 6 项");
        assertTrue(wizard.contains("exclusionSelected: [...DEFAULT_EXCLUSIONS]"),
                "默认排除项必须默认勾选");
        assertTrue(wizard.contains("function mergeExclusions(")
                        && wizard.contains("uniqueList([...selected, ...splitList(custom)])"),
                "常用 chips 与自定义排除词必须合并去重");
        for (String text : List.of("常用排除项（默认已勾选，可取消）", "常用排除项",
                "自定义排除词", "会与上方勾选项合并去重", "最终排除项")) {
            assertTrue(wizard.contains(text), "排除项 UI 缺失：" + text);
        }
        assertTrue(wizard.contains("aria-label=\"常用排除项\""), "常用排除 chips 需要分组语义");
    }

    @Test
    void industryPreferenceDefaultsFromDirectionAndCapsAtSix() throws Exception {
        String wizard = Files.readString(WIZARD);
        assertTrue(wizard.contains("const MAX_INDUSTRIES = 6"), "行业上限必须是 6");
        assertTrue(wizard.contains("const defaultIndustriesFor = ("),
                "必须提供方向/画像行业默认值函数");
        assertTrue(wizard.contains("industryNames.includes(name)"),
                "默认行业必须与 getBossOptions('industry') 选项匹配");
        assertTrue(wizard.contains(".slice(0, MAX_INDUSTRIES)"), "默认/手动行业都必须截断到 6");
        assertTrue(wizard.contains("industryOptions.map") && wizard.contains("aria-label=\"行业偏好\""),
                "行业偏好必须渲染为选项 chips");
        assertTrue(wizard.contains("已选 {selectedIndustries.length}/{MAX_INDUSTRIES}"),
                "行业已选计数必须展示");
        assertTrue(wizard.contains("行业会写入模板搜索条件（config.industry）"),
                "必须说明行业写入 config.industry");
        assertTrue(wizard.contains("getBossOptions('industry')"), "行业选项必须走真实 BOSS 选项接口");
    }

    @Test
    void draftPayloadAndPreviewCoverNewFields() throws Exception {
        String wizard = Files.readString(WIZARD);
        int payloadStart = wizard.indexOf("const payload: AiDraftRequest = {");
        int payloadEnd = wizard.indexOf("const result = await generateAiTemplateDraft(payload)");
        String payload = wizard.substring(payloadStart, payloadEnd);
        for (String marker : List.of("cities: answers.cities.slice(0, MAX_CITIES)",
                "industries: answers.industries.slice(0, MAX_INDUSTRIES)",
                "cityCode: answers.cities[0] ?? null",
                "exclusions: mergeExclusions(answers.exclusionSelected, answers.exclusions)",
                "intensity: answers.intensity")) {
            assertTrue(payload.contains(marker), "草稿请求体缺少字段：" + marker);
        }

        int previewStart = wizard.indexOf("function PreviewStep(");
        String preview = wizard.substring(previewStart);
        for (String label : List.of("城市", "行业", "学历要求", "经验要求", "公司规模", "融资阶段",
                "期望月薪下限", "采集策略", "筛选规则")) {
            assertTrue(preview.contains("label: '" + label + "'"),
                    "预览缺少字段：" + label);
        }
        assertTrue(preview.contains("（主搜索城市）"), "预览城市应标注主搜索城市");
        assertTrue(preview.contains("20 人以上（20-99 / 100-499 / 500-999 / 1000-9999 / 10000 人以上）"),
                "公司规模无值时预览应显示 20 人以上五档");
        assertTrue(preview.contains("stage?.length ? config.stage.join") && preview.contains("'不限'"),
                "融资阶段无值时预览应显示不限");
        assertTrue(preview.contains("config.degree") && preview.contains("config.experience"),
                "预览应直接展示后端映射的学历/经验");
        assertTrue(wizard.contains("config.industry?.length"), "预览应展示后端映射的行业");
        assertTrue(wizard.contains("rule?.expectedSalaryMin") && wizard.contains("${rule.expectedSalaryMin}K 起"),
                "预览薪资应显示规则下限 minK 起");
        assertTrue(wizard.contains("expectedSalaryMax: null"),
                "AI 草稿请求上限必须为 null（只设下限）");
        assertFalse(wizard.contains("月薪上限（K）"), "向导不得再有薪资上限输入");
    }

    @Test
    void saveChainDefaultDraftAndNoUnrelatedWrites() throws Exception {
        String wizard = Files.readString(WIZARD);
        int create = wizard.indexOf("created = await createTemplate(");
        int config = wizard.indexOf("await updateTemplateConfig(created.id, draft.config)");
        int activate = wizard.indexOf("await activateTemplate(created.id)");
        assertTrue(create > 0 && config > 0 && activate > 0, "保存链路必须包含三步");
        assertTrue(create < config && config < activate,
                "保存顺序必须是 createTemplate → updateTemplateConfig → (可选) activateTemplate");
        assertTrue(wizard.contains("if (activate) {"), "只有显式选择「保存并激活」才允许 activate");
        assertTrue(wizard.contains("persistDraft(false)") && wizard.contains("persistDraft(true)"),
                "保存为草稿/保存并激活必须分开");
        assertTrue(wizard.contains("title=\"保存并激活该模板？\"")
                        && wizard.contains("不会立即发送") && wizard.contains("不会自动开始投递"),
                "保存并激活必须二次确认且说明不会立即发送");
        assertTrue(wizard.contains("已保存为草稿（未激活）"), "完成页必须说明草稿未激活");

        for (String forbidden : List.of("startBoss", "stopBoss", "forceReset", "resumeVerification",
                "approveFollowup", "rejectFollowup", "decideManualRelease", "resolveAttempt",
                "createImageResendPlan")) {
            assertFalse(wizard.contains(forbidden), "AI 建模板不得调用无关写接口：" + forbidden);
        }
        for (String storage : List.of("localStorage", "sessionStorage", "indexedDB", "document.cookie")) {
            assertFalse(wizard.contains(storage), "向导不得写浏览器存储：" + storage);
        }
        assertTrue(wizard.contains("不会写入浏览器存储"), "上传区应说明不写浏览器存储");
        assertFalse(wizard.contains("fetch("), "向导不得绕过 lib/api.ts 直接 fetch");
    }

    @Test
    void apiAndTypesCoverNewDraftFields() throws Exception {
        String api = Files.readString(API);
        assertTrue(api.contains("'/api/boss/resume/analyze'")
                        && api.contains("'/api/boss/resume/analyze-text'")
                        && api.contains("'/api/boss/templates/ai-draft'"),
                "三个向导接口封装必须存在");
        assertTrue(api.contains("form.append('file', file)"), "图片分析必须是 multipart file");
        assertTrue(api.contains("supplement") && api.contains("form.append('supplement'"),
                "补充说明必须随图片/文本分析一起提交");

        String types = Files.readString(TYPES);
        for (String field : List.of("major", "graduationYear", "industries",
                "cities: string[]", "industries: string[]", "cityCode", "AiDraftRequest",
                "AiDraftResult", "TemplateConfigRequest")) {
            assertTrue(types.contains(field), "向导类型缺少新字段：" + field);
        }
    }

    @Test
    void distContainsSecondRoundWizardTexts() throws Exception {
        assertTrue(distContainsAny("AI 帮我建模板"), "dist 应包含 AI 建模板入口");
        assertTrue(distContainsAny("希望优先看哪些城市"), "dist 应包含新城市问题");
        assertTrue(distContainsAny("主搜索城市"), "dist 应包含主城市标注");
        assertTrue(distContainsAny("常用排除项"), "dist 应包含默认排除项");
        assertTrue(distContainsAny("行业偏好"), "dist 应包含行业偏好");
        assertTrue(distContainsAny("公司规模"), "dist 应包含公司规模预览");
        assertTrue(distContainsAny("oc-dialog-layer"), "dist 应包含 DialogShell 弹层实现");
        assertTrue(Files.readString(DIST.resolve("boss/setup.html")).contains("方向模板"),
                "dist setup 页应可挂载向导入口");
    }

    private static boolean distContainsAny(String... needles) throws Exception {
        try (var stream = Files.walk(DIST)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".js") || name.endsWith(".html") || name.endsWith(".txt");
                    }).toList();
            for (Path file : files) {
                if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".html")) {
                    continue;
                }
                String content = Files.readString(file);
                for (String needle : needles) {
                    if (content.contains(needle)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
