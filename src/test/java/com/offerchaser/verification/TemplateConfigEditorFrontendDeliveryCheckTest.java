package com.offerchaser.verification;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 5 空间重构后的模板编辑器交付检查：编辑器迁入配置空间（embedded 模式，隐藏收起按钮）；
 * 四区块与整包保存不变；今日页只读、不承载配置表单；运行胶囊负责运行控制；
 * status 节流/终态强制复位等回归仍由源码检查覆盖；dist 同步。
 */
class TemplateConfigEditorFrontendDeliveryCheckTest {

    private static final Path FRONT = Path.of("front");
    private static final Path DIST = Path.of("src", "main", "resources", "dist");

    @Test
    void templateConfigEditorExistsAndCoversFourSections() throws Exception {
        Path editor = FRONT.resolve("app/boss/templates/TemplateConfigEditor.tsx");
        assertTrue(Files.isRegularFile(editor), "缺少 TemplateConfigEditor");
        assertTrue(Files.size(editor) > 20_000, "编辑器文件过小，可能未实现四区块");
        String source = Files.readString(editor);
        for (String api : List.of("getTemplateDetail", "getBossOptions", "updateTemplateConfig")) {
            assertTrue(source.contains(api), "编辑器必须调用真实 API：" + api);
        }
        for (String section : List.of("搜索与投递", "公司要求与筛选规则", "AI 配置",
                "模板图片简历")) {
            assertTrue(source.contains(section), "编辑器缺少区块：" + section);
        }
        assertTrue(source.contains("保存配置"), "必须有整包保存入口");
        assertTrue(source.contains("已保存并立即应用"), "生效模板保存后应立即应用提示");
        assertTrue(source.contains("未激活") || source.contains("激活后生效"),
                "非生效模板保存应提示激活后生效");
    }

    @Test
    void setupPageShowsCardByDefaultAndExpandsEditorOnDemand() throws Exception {
        String setup = Files.readString(FRONT.resolve("app/boss/setup/page.tsx"));
        assertTrue(setup.contains("import TemplateConfigEditor"), "配置空间必须引入编辑器");
        assertTrue(setup.contains("const [editing, setEditing] = useState(false)"),
                "选中模板默认不应展开配置编辑器");
        assertTrue(setup.contains("setEditing(false)") && setup.contains("[selectedId]"),
                "切换模板时必须收起编辑器/回到卡片态");
        assertTrue(setup.contains("{!editing ? (")
                        && setup.contains("onClick={() => setEditing(true)}"),
                "默认卡片态应提供「编辑配置」按钮");
        assertTrue(setup.contains("编辑配置"), "缺少「编辑配置」按钮文案");

        // 默认（!editing）卡片字段：方向/关键词/期望月薪/图片简历/最近更新/描述
        for (String label : List.of("方向", "关键词", "期望月薪下限", "图片简历", "最近更新", "描述")) {
            assertTrue(setup.contains("<dt className=\"shrink-0 text-xs text-[var(--v3-muted)]\">"
                            + label + "</dt>"),
                    "基本信息卡缺少字段：" + label);
        }
        assertTrue(setup.contains("selected.direction || '未设置'")
                        && setup.contains("selected.keywords || '未设置'")
                        && setup.contains("selected.hasResume ? '已上传' : '使用当前简历'"),
                "卡片应只读展示模板基本信息");

        // 点编辑后：基本信息编辑行 + embedded 编辑器，保存成功后自动收起
        assertTrue(setup.contains("aria-label=\"模板名称\"") && setup.contains("保存基本信息"),
                "编辑态应允许改模板基本信息");
        assertTrue(setup.contains("<TemplateConfigEditor") && setup.contains("embedded"),
                "编辑态应以 embedded 模式渲染编辑器");
        assertTrue(setup.contains("onSaved={() => {") && setup.contains("setEditing(false)")
                        && setup.contains("void refreshQuietly()"),
                "编辑器保存成功后必须自动收起回卡片并刷新列表");
        String editor = Files.readString(FRONT.resolve("app/boss/templates/TemplateConfigEditor.tsx"));
        assertTrue(editor.contains("embedded?: boolean"), "编辑器应提供 embedded 属性");
        assertTrue(editor.contains("embedded ? null"), "embedded 模式应隐藏收起按钮");
        assertTrue(Files.readString(FRONT.resolve("app/boss/templates/page.tsx"))
                .contains("LegacyRedirect"), "旧模板页应重定向");
    }

    @Test
    void editorUsesUnlimitedStageFallbackAndCompactMultiCheckList() throws Exception {
        String editor = Files.readString(FRONT.resolve("app/boss/templates/TemplateConfigEditor.tsx"));
        assertTrue(editor.contains("stage: parseTextList(config.stage).length > 0 ? parseTextList(config.stage) : ['不限']"),
                "融资阶段空值必须显式回退为「不限」");
        assertTrue(editor.contains("空值在 BOSS 侧表示不限"),
                "必须注释说明空值=不限，避免误以为漏选");
        assertTrue(editor.contains("label=\"融资阶段\"") && editor.contains("selected={bossForm.stage}"),
                "融资阶段多选必须绑定回退后的值");
        assertTrue(editor.contains("max-h-[158px] overflow-y-auto"),
                "多选列表 max-height 必须是 150px（约 5 行、不露第 6 项）");
        assertTrue(editor.contains("min-h-7"), "多选行高应保持约 28px（158px ≈ 5 行）");
        assertTrue(editor.contains("MultiCheckList"), "多选列表组件必须存在");
        assertTrue(editor.contains("未匹配"), "历史未匹配值应保留展示（不能清空已有模板数据）");

        // 「不限」与具体选项互斥：选不限→只保留不限；选具体项→自动移除不限；取消不限→空选择
        assertTrue(editor.contains("const UNLIMITED = '不限'"),
                "多选互斥应使用统一 UNLIMITED 常量");
        assertTrue(editor.contains("「不限」与具体选项互斥"), "必须有互斥规则说明");
        assertTrue(editor.contains("if (name === UNLIMITED)"),
                "选择「不限」必须走独占分支");
        assertTrue(editor.contains("onChange(selectedSet.has(UNLIMITED) ? [] : [UNLIMITED])"),
                "选「不限」只保留不限；再次取消不限应回到空选择");
        assertTrue(editor.contains("selected.filter((item) => item !== UNLIMITED)"),
                "选择具体项前必须移除「不限」");
        assertTrue(editor.contains("onChange([...withoutUnlimited, name])"),
                "具体项应追加在移除「不限」之后");
        assertTrue(editor.contains("blacklistCompany") && editor.contains("blacklistRecruiter")
                        && editor.contains("blacklistCompanyText") && editor.contains("blacklistRecruiterText"),
                "编辑器必须保留公司/HR 黑名单字段（不得清空已有模板数据）");
        assertTrue(editor.contains("格式由后端严格校验。"),
                "提示词说明应精简为「格式由后端严格校验。」");
        assertFalse(editor.contains("仅影响本模板"),
                "编辑器不得再有「仅影响本模板：」前缀");
        assertFalse(editor.contains("修改前请确认格式") || editor.contains("遵循既有约定"),
                "不得再保留旧的提示词长说明");
        assertTrue(editor.contains("onSaved(result.template)"),
                "编辑器保存成功必须回调 onSaved 以触发外部收起");
        assertTrue(editor.contains("保存配置"), "仍保留整包保存按钮");
    }

    @Test
    void todayPageIsReadOnlyOverviewWithoutConfigForm() throws Exception {
        String today = Files.readString(FRONT.resolve("app/boss/page.tsx"));
        for (String text : List.of("今日", "投递管线", "事件流",
                "今日投递数", "总投递数", "总回复数", "跟进待办", "需手动核对",
                "详情 · 只读", "最多展示 20 条", "生效模板", "今日预算环", "状态同步")) {
            assertTrue(today.contains(text), "今日页缺少只读总览：" + text);
        }
        for (String forbidden : List.of("updateBossConfig", "saveBossConfig", "uploadResumeImage",
                "type=\"file\"", "updateTemplateConfig")) {
            assertFalse(today.contains(forbidden), "今日页不应包含配置表单/运行按钮：" + forbidden);
        }
        assertTrue(today.contains("按当前生效模板执行。") && today.contains("需手动点击开始。"),
                "今日开始面板说明应精简为短句");
        assertFalse(today.contains("开始投递不是自动跑"),
                "今日页顶部不得再保留旧长说明「开始投递不是自动跑」");
        assertFalse(today.contains("真实发送仍由后端规则 / AI / 身份 / 预算 / 文字+图片确认链路决定"),
                "今日页不得再保留旧长安全链路说明");
        assertTrue(today.contains("/boss/setup"), "今日页应引导到配置空间");
        // 今日页独立大开始按钮：RUN 面板 + 大按钮 + 禁用/原因提示，仍走同一 actions.start
        for (String marker : List.of("RUN · 开始一轮投递", "开始投递", "startDisabled",
                "actions.start()", "BOSS 未登录，请先扫码。", "worker 尚未退出，可先强制复位。",
                "当前状态不可开始。", "workerExited === false", "!boss.canStart",
                "actionBusy === 'start'")) {
            assertTrue(today.contains(marker), "今日页缺少独立开始面板：" + marker);
        }
        // 顶部 RunCapsule 在 /boss 隐藏小号开始按钮
        String capsule = Files.readString(FRONT.resolve("app/components/shell/RunCapsule.tsx"));
        assertTrue(today.contains("按当前生效模板执行。") && today.contains("需手动点击开始。"),
                "开始面板说明应为短句");
        assertFalse(today.contains("BOSS 未登录：请先在 Chrome 窗口扫码登录，再点开始投递。"),
                "开始禁用原因不得再用旧长句");
        assertFalse(today.contains("运行控制只在顶栏胶囊") || today.contains("本页不发任何写请求"),
                "今日 JSDoc 不得再保留过期说明");
        assertTrue(capsule.contains("const onToday = pathname === '/boss'"),
                "RunCapsule 必须识别今日页");
        assertTrue(capsule.contains(") : onToday ? null : ("),
                "RunCapsule 在今日页必须隐藏小号开始按钮");
        assertTrue(capsule.contains("onClick={() => void actions.start()}"),
                "非今日页仍保留小号开始按钮");
    }

    @Test
    void runCapsuleAndStatusThrottleRegressionCoveredBySource() throws Exception {
        String capsule = Files.readString(FRONT.resolve("app/components/shell/RunCapsule.tsx"));
        for (String marker : List.of("startDisabled", "stopDisabled", "resumeDisabled",
                "forceResetDisabled", "ConfirmDialog", "开始投递", "停止投递", "继续任务", "强制复位")) {
            assertTrue(capsule.contains(marker), "运行胶囊缺少：" + marker);
        }
        String controller = Files.readString(FRONT.resolve("lib/use-boss-status.ts"));
        assertTrue(controller.contains("700") && controller.contains("scheduleStatusRefresh"),
                "progress 触发的 status 刷新必须有 700ms 节流");
        assertTrue(controller.contains("forceResetDisabled = isTerminal && status?.workerExited !== false"),
                "终态但 workerExited=false 时允许强制复位");
        assertTrue(controller.contains("startDisabled") && controller.contains("workerExited === false"));
        assertTrue(Files.readString(FRONT.resolve("app/layout.tsx")).contains("AuthGate"),
                "根布局仍保留认证门");
        assertTrue(Files.readString(FRONT.resolve("app/components/shell/ThemeProvider.tsx"))
                        .contains("THEME_ORDER"),
                "主题提供者应保留三套主题");
    }

    @Test
    void distContainsEditorAndFiveSpaceEntries() throws Exception {
        assertTrue(distContainsAny("搜索与投递"), "dist 应包含编辑器「搜索与投递」区块");
        assertTrue(distContainsAny("公司要求与筛选规则"), "dist 应包含规则区块");
        assertTrue(distContainsAny("保存配置", "已保存并立即应用"), "dist 应包含保存配置逻辑");
        assertTrue(distContainsAny("模型服务（全局）"), "dist 应包含模型服务卡片");
        assertTrue(distContainsAny("今日投递数"), "dist 应包含今日五卡总览");
        assertTrue(distContainsAny("编辑配置"), "dist 应包含模板卡片「编辑配置」入口");
        assertTrue(distContainsAny("最近更新"), "dist 应包含模板卡片「最近更新」字段");
        assertTrue(distContainsAny("max-h-[158px]"), "dist 应包含多选列表 150px 高度实现");
        long distFiles;
        try (Stream<Path> stream = Files.walk(DIST)) {
            distFiles = stream.filter(Files::isRegularFile).count();
        }
        assertTrue(distFiles >= 130, "dist 文件数量异常：" + distFiles);
        assertTrue(Files.readString(DIST.resolve("boss/setup.html")).contains("方向模板"));
    }

    @Test
    void salaryRangeIsEditableInEditorWhileCardAndWizardStayLowerBound() throws Exception {
        String editor = Files.readString(FRONT.resolve("app/boss/templates/TemplateConfigEditor.tsx"));
        // A 区不再有期望月薪输入；B 区为规则薪资区间（下限/上限，留空=不限）
        int aStart = editor.indexOf("A. 搜索与投递");
        int bStart = editor.indexOf("B. 公司要求与筛选规则");
        assertTrue(aStart > 0 && bStart > aStart, "缺少 A/B 区块");
        String sectionA = editor.substring(aStart, bStart);
        assertFalse(sectionA.contains("期望月薪"), "A 区不得再有期望月薪输入");
        assertFalse(sectionA.contains("expectedSalary"), "A 区不得再绑定 BOSS 期望薪资字段");
        assertFalse(editor.contains("期望月薪下限（K）"), "旧 A 区期望月薪下限输入应删除");
        assertFalse(editor.contains("期望月薪上限（K）"), "旧 A 区期望月薪上限输入应删除");
        assertTrue(editor.contains("规则期望月薪区间（K，留空=不限）"),
                "B 区应展示规则期望月薪区间（下限/上限）");
        assertTrue(editor.contains("aria-label=\"规则期望月薪下限\"")
                        && editor.contains("aria-label=\"规则期望月薪上限\""),
                "B 区应有下限/上限两个输入");
        assertTrue(editor.contains("placeholder=\"上限（可不填）\""),
                "上限应允许留空（=不设上限）");
        assertTrue(editor.contains("expectedSalaryMax: string"),
                "RuleForm 应包含 expectedSalaryMax");
        assertTrue(editor.contains("const ruleMin = toIntOrNull(ruleForm.expectedSalaryMin)")
                        && editor.contains("const ruleMax = toIntOrNull(ruleForm.expectedSalaryMax)"),
                "保存时应同时读取下限/上限");
        assertTrue(editor.contains("ruleMax < ruleMin"),
                "上限小于下限时必须拦截保存");
        assertTrue(editor.contains("薪资上限不能低于下限。"),
                "上限小于下限应有明确错误提示");
        assertTrue(editor.contains("expectedSalaryMin: ruleMin"),
                "payload rule 写入下限");
        assertTrue(editor.contains("expectedSalaryMax: ruleMax ?? 0"),
                "payload rule 上限写用户输入或 0（留空=不设上限）");
        assertTrue(editor.contains("留空=不设上限"), "必须说明上限留空=不设上限");
        assertTrue(!editor.contains("bossForm.expectedSalary"), "BOSS 表单不得再持有 A 区薪资");

        // 模板卡仍从规则/摘要显示下限：xxK 起（上限回退分支已移除）
        String setup = Files.readString(FRONT.resolve("app/boss/setup/page.tsx"));
        assertTrue(setup.contains("期望月薪下限"), "卡片字段名应为期望月薪下限");
        assertTrue(setup.contains("`${selected.expectedSalaryMin}K 起`"),
                "卡片应显示「minK 起」");
        assertFalse(setup.contains("不超过"),
                "模板卡不得再有「不超过 maxK」兼容分支");
        assertTrue(setup.contains("未设置"), "无有效薪资时应显示未设置");

        // ProfileTemplateService.toView 用规则薪资生成模板摘要
        String templateService = Files.readString(Path.of("src", "main", "java",
                "com/offerchaser/application/templates/ProfileTemplateService.java"));
        int toViewStart = templateService.indexOf("private TemplateView toView(");
        int toViewEnd = templateService.indexOf("/** 0/负数视为未设置", toViewStart);
        assertTrue(toViewStart > 0 && toViewEnd > toViewStart, "缺少 toView 实现");
        String toView = templateService.substring(toViewStart, toViewEnd);
        assertTrue(toView.contains("parseRulePayload(template)"), "toView 必须从模板规则取薪资");
        assertTrue(toView.indexOf("parseRulePayload(template)") > toView.indexOf("if (template.bossConfigJson()"),
                "rule 薪资解析必须独立于 bossConfig 是否有值");
        assertTrue(toView.contains("rules.expectedSalaryMin()") && toView.contains("rules.expectedSalaryMax()"),
                "toView 必须使用规则 expectedSalaryMin/Max");
        assertTrue(toView.contains("positiveOrNull"), "0/负数应按未设置处理");
        assertTrue(templateService.contains("A 区期望月薪已废弃"), "应注明 A 区薪资已废弃");

        // AI 向导已同步支持薪资范围（上下限 + BOSS 薪资档位）；规则层仍只写下限
        String wizard = Files.readString(FRONT.resolve("app/components/setup/AiTemplateWizard.tsx"));
        assertTrue(wizard.contains("期望薪资范围是多少？"), "AI 向导薪资问题应为期望薪资范围");
        assertTrue(wizard.contains("BOSS 薪资范围"), "向导应提供 BOSS 薪资档位选择");
        assertTrue(wizard.contains("expectedSalaryMax: numberOrNull(answers.salaryMax)"),
                "请求应提交期望月薪上限");
        assertTrue(wizard.contains("salary: answers.salary"), "请求应提交 BOSS 薪资档位");
        assertFalse(wizard.contains("expectedSalaryMax: null"), "请求不应再把上限写死为 null");
        assertTrue(wizard.contains("salaryRangeText"), "预览应按区间展示期望月薪");

        // 规则服务允许 0=未设置
        String ruleService = Files.readString(Path.of("src", "main", "java",
                "com/offerchaser/application/rules/RuleConfigService.java"));
        assertTrue(ruleService.contains("boolean hasMin = min != null && min > 0")
                        && ruleService.contains("boolean hasMax = max != null && max > 0"),
                "规则服务应只比较有效正数，0 表示未设置");
        assertTrue(ruleService.contains("0 表示")
                        && ruleService.contains("允许模板只设下限"), "规则服务应注释 0=未设置语义");

        // dist 同步
        assertTrue(distContainsAny("规则期望月薪区间（K，留空=不限）"),
                "dist 应包含规则薪资区间 UI");
        assertTrue(distContainsAny("薪资上限不能低于下限。"), "dist 应包含上限校验提示");
        assertTrue(distContainsAny("K 起"), "dist 应包含卡片/预览 minK 起");
    }

    @Test
    void noMockDataInEditorAndShellSources() throws Exception {
        List<Path> sources = List.of(
                FRONT.resolve("app/boss/templates/TemplateConfigEditor.tsx"),
                FRONT.resolve("app/boss/setup/page.tsx"),
                FRONT.resolve("app/boss/page.tsx"),
                FRONT.resolve("app/components/shell/RunCapsule.tsx"),
                FRONT.resolve("app/components/shell/TopBar.tsx"));
        for (Path source : sources) {
            String content = Files.readString(source).toLowerCase(Locale.ROOT);
            for (String marker : List.of("mockdata", "mock-data", "mock_", "fakedata")) {
                assertFalse(content.contains(marker),
                        source.getFileName() + " 不得使用 mock 数据：" + marker);
            }
        }
    }

    private static boolean distContainsAny(String... needles) throws Exception {
        try (Stream<Path> stream = Files.walk(DIST)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".js") || name.endsWith(".html") || name.endsWith(".txt");
                    }).toList();
            for (Path file : files) {
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
