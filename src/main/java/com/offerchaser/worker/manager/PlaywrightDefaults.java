package com.offerchaser.worker.manager;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Playwright / BOSS 浏览器相关的固定默认值。
 * <p>
 * 只放“不随运行实例变化”的常量：超时、站点地址、Cookie 域名、登录态 Cookie 名等。
 * 放在独立类里是为了让 manager、profile 维护、Cookie 解析三处共享同一份定义，
 * 避免同一个魔法数字散落在多个方法里。
 */
final class PlaywrightDefaults {

    private PlaywrightDefaults() {
    }

    // ==================== Playwright 行为默认值 ====================

    /** 页面默认操作超时（毫秒）。 */
    static final int DEFAULT_TIMEOUT = 30_000;

    /** Playwright 调试端口（对外 getCdpPort 的返回值保持这个值）。 */
    static final int CDP_PORT = 7866;

    /** 登录态探针超时（毫秒）：探针失败只代表“没看到”，不能拖住主流程。 */
    static final double LOCATOR_PROBE_TIMEOUT = 5_000;

    /**
     * waitForLoadState 超时（毫秒）。
     * <p>
     * BOSS 是 SPA + WebSocket 长连接（cookie 里就有 ws.zhipin.com），NETWORKIDLE 可能永远等不到；
     * 不给超时会直接把线程挂死，而且“卡住”不是异常，外面包多少层 try/catch 都没用。
     */
    static final double LOAD_STATE_TIMEOUT = 10_000;

    /** Playwright 专用调度线程名：重入判断依赖它，改名会破坏就地执行逻辑。 */
    static final String PW_THREAD_NAME = "playwright-thread";

    /** patchright driver 的启动参数名，与 gradle bootRun / Playwright Driver 约定一致。 */
    static final String PLAYWRIGHT_CLI_DIR_PROPERTY = "playwright.cli.dir";

    // ==================== BOSS 站点与登录 ====================

    /** BOSS 站点根地址。 */
    static final String BOSS_URL = "https://www.zhipin.com";

    /**
     * 初始化时打开的落地页。
     * <p>
     * 不要用首页：实测 navigate("https://www.zhipin.com") 要 60 秒才回来，页面标签一直转圈，
     * 期间任何 evaluate / locator 调用都会无限期阻塞（这两个 API 都没有默认超时），
     * 表现就是启动奇慢、投递卡死、管理页面点按钮没反应。岗位列表页在同一个浏览器里秒开。
     */
    static final String BOSS_ENTRY_URL = "https://www.zhipin.com/web/geek/jobs";

    /** BOSS 登录页（未登录时把页面引导到这里，并尝试切扫码模式）。 */
    static final String BOSS_LOGIN_URL = BOSS_URL + "/web/user/?ka=header-login";

    /** BOSS Cookie 域后缀：持久化/注入只认这个域及其子域。 */
    static final String BOSS_DOMAIN = "zhipin.com";

    /** BOSS 登录态 Cookie：出现任意一个即视为已登录。 */
    static final Set<String> BOSS_LOGIN_COOKIES = Set.of("bst", "wt2", "zp_at", "geek_zp_token");

    /** 持久化上下文（Chrome profile）的用户数据目录，登录态直接落在这里。 */
    static final Path USER_DATA_DIR = Paths.get(System.getProperty("user.dir"), "browser-data");
}
