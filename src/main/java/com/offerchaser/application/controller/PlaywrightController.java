package com.offerchaser.application.controller;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import com.offerchaser.worker.manager.PlaywrightManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Playwright 浏览器调试接口（仅本机使用；{@code /api/playwright/**} 属于敏感 GET，
 * 由 {@code AuthInterceptor} 强制要求本机会话认证）。
 * <p>
 * 四个端点只做“看”和“探测”，不参与投递业务：
 * <ul>
 *   <li>{@code /status}：浏览器/页面/登录态是否就绪；</li>
 *   <li>{@code /fingerprint}：当前页面的自动化指纹自检（webdriver/userAgentData/cdc_ 残留）；</li>
 *   <li>{@code /diagnose}：用全新受控标签页对照加载目标地址，隔离“常驻页卡死”问题；</li>
 *   <li>{@code /test-navigate}：最基础的导航探针。</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/playwright")
public class PlaywrightController {

    /** 指纹自检脚本：期望 webdriver=false、userAgentData 存在、无 cdc_ 残留。 */
    private static final String FINGERPRINT_SCRIPT = """
            () => {
              const ua = navigator.userAgentData;
              const brandText = ua && ua.brands
                ? ua.brands.map(b => b.brand + ' ' + b.version).join(', ')
                : '(缺失-异常)';
              const webdriverFlag = String(navigator.webdriver);
              return {
                'navigator.webdriver': webdriverFlag, 'userAgent': navigator.userAgent,
                'navigator.platform': navigator.platform,
                'uaData.platform': ua ? ua.platform : '(缺失-异常)',
                'uaData.brands': brandText,
                'cdc_残留': Object.keys(window).filter(k => k.startsWith('cdc_')).join(',') || '(无)',
                'url': location.href
              };
            }""";

    /** 诊断脚本：页面就绪度 + 岗位卡片数 + 未完成资源，用来判断“转圈卡住”卡在哪一步。 */
    private static final String PAGE_PROBE_SCRIPT = """
            () => {
              const resources = performance.getEntriesByType('resource');
              const jobCardSelector = 'li.job-card-box, li.job-card-wrapper';
              return {
                readyState: document.readyState, title: document.title, url: location.href,
                bodyLength: document.body ? document.body.innerHTML.length : 0,
                jobCards: document.querySelectorAll(jobCardSelector).length,
                listContainers: document.querySelectorAll('ul.rec-job-list, ul.job-list-box, .job-list-box').length,
                pending: resources.filter(r => r.responseEnd === 0).map(r => r.name).slice(0, 8)
              };
            }""";

    /** BOSS 首页（仅用于状态/导航探针）。 */
    private static final String BOSS_HOME_URL = "https://www.zhipin.com";
    /** 诊断端点默认对比的岗位列表页。 */
    private static final String DEFAULT_DIAGNOSE_URL =
            "https://www.zhipin.com/web/geek/jobs?query=Java&city=101020100";
    /** 探测页导航/默认超时（毫秒）；两个 API 都没有内置超时，必须显式给，避免请求挂死。 */
    private static final double PROBE_TIMEOUT_MS = 20_000;
    /** 导航后等待页面自渲染的时间（毫秒）。 */
    private static final long PROBE_SETTLE_MS = 5_000L;

    private final PlaywrightManager playwrightManager;

    // 所有 Playwright 操作都转发给 manager 的专用线程，本类只做 HTTP 包装
    public PlaywrightController(PlaywrightManager playwrightManager) {
        this.playwrightManager = playwrightManager;
    }

    /** 浏览器/页面/登录态概览。 */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("initialized", playwrightManager.isInitialized());
        status.put("cdpPort", playwrightManager.getCdpPort());
        status.put("hasBossPage", playwrightManager.getBossPage() != null);
        // 持久化上下文中 context 就是浏览器本身（没有独立的 Browser 对象）
        status.put("hasBrowser", playwrightManager.getContext() != null);
        status.put("bossLoggedIn", playwrightManager.isLoggedIn("boss"));
        return ResponseEntity.ok(status);
    }

    /**
     * 指纹自检：确认当前浏览器没有暴露自动化特征。
     * <p>
     * 期望值：{@code navigator.webdriver = false}（真实 Chrome 就是 false，undefined 反而是
     * 老式 stealth 脚本的痕迹）；{@code userAgentData} 存在且与 UA 自洽；{@code cdc_} 残留为空。
     */
    @GetMapping(path = "/fingerprint")
    public ResponseEntity<Map<String, Object>> fingerprint() {
        try {
            // 浏览器调用统一丢给 Playwright 专用线程，http-nio 线程不得直接操作页面
            Object fingerprint = playwrightManager.callOnPlaywright(
                    () -> playwrightManager.getBossPage().evaluate(FINGERPRINT_SCRIPT));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("fingerprint", fingerprint);
            return ResponseEntity.ok(response);
        } catch (Exception probeError) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("error", probeError.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 诊断：用全新受控标签页分别加载 BOSS 首页与目标地址，对比 readyState/标题/卡片数/未完成资源。
     * <p>
     * 刻意不碰常驻 bossPage：它一旦卡在“永久加载”，{@code evaluate} 没有默认超时会无限阻塞，
     * 连诊断请求也会一起挂死；这里只用新标签页做对照，做完立即关闭。
     */
    @GetMapping("/diagnose")
    public ResponseEntity<Map<String, Object>> diagnose(
            @RequestParam(defaultValue = DEFAULT_DIAGNOSE_URL) String url) {
        Map<String, Object> report = new LinkedHashMap<>();
        String[] targets = {BOSS_HOME_URL, url};
        try {
            playwrightManager.callOnPlaywright(() -> {
                for (String target : targets) {
                    report.put(target, probePage(target));
                }
                return null;
            });
            report.put("success", true);
        } catch (Exception diagnoseError) {
            report.put("success", false);
            report.put("error", diagnoseError.getMessage());
        }
        return ResponseEntity.ok(report);
    }

    /** 新开标签页加载一个地址并采集页面状态；任何异常都收敛进结果 map，不向上抛。 */
    private Map<String, Object> probePage(String target) {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        Page probe = null;
        try {
            probe = playwrightManager.getContext().newPage();
            probe.setDefaultTimeout(PROBE_TIMEOUT_MS);
            probe.navigate(target, new Page.NavigateOptions()
                    .setTimeout(PROBE_TIMEOUT_MS)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            result.put("navigateMs", System.currentTimeMillis() - start);

            probe.waitForTimeout(PROBE_SETTLE_MS);
            result.put("currentUrl", probe.url());
            result.put("probe", probe.evaluate(PAGE_PROBE_SCRIPT));
        } catch (Exception probeError) {
            result.put("elapsedMs", System.currentTimeMillis() - start);
            result.put("error", probeError.getMessage() == null
                    ? probeError.toString()
                    : probeError.getMessage().split("\n")[0]);
        } finally {
            closeQuietly(probe);
        }
        return result;
    }

    /** 把自动化 BOSS Chrome 窗口置前（用户可能只看到日常 Chrome）。 */
    @PostMapping("/focus")
    public ResponseEntity<Map<String, Object>> focus() {
        try {
            playwrightManager.requestFocusBossPage();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "已把自动化 BOSS Chrome 窗口切到前台");
            return ResponseEntity.ok(response);
        } catch (Exception focusError) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("message", "切换 BOSS 窗口失败：" + focusError.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** 基础导航探针：直接用常驻 bossPage 打开 BOSS 首页并回读标题/URL。 */
    @GetMapping("/test-navigate")
    public ResponseEntity<Map<String, String>> testNavigate() {
        try {
            playwrightManager.getBossPage().navigate(BOSS_HOME_URL);
            String title = playwrightManager.getBossPage().title();

            Map<String, String> result = new LinkedHashMap<>();
            result.put("success", "true");
            result.put("title", title);
            result.put("url", playwrightManager.getBossPage().url());
            return ResponseEntity.ok(result);
        } catch (Exception navigateError) {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("success", "false");
            result.put("error", navigateError.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    private void closeQuietly(Page page) {
        if (page == null) {
            return;
        }
        try {
            page.close();
        } catch (Exception ignored) {
            // 探测页可能已被浏览器关闭，忽略
        }
    }
}
