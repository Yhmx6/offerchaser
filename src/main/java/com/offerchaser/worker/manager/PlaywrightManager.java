package com.offerchaser.worker.manager;

import com.offerchaser.application.entity.CookieEntity;
import com.offerchaser.application.service.CookieService;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.options.LoadState;
import jakarta.annotation.PreDestroy;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Playwright 管理器（BOSS 专用）。
 * <p>
 * Spring 管理的单例 Bean：启动唯一一个 BOSS Chrome 持久化上下文、注入 Cookie、维护登录状态，
 * 并把所有 Playwright 调用收敛到专用线程上执行。当前只服务 BOSS 直聘。
 */
@Component
@Lazy
@Slf4j
@Getter
public class PlaywrightManager {

    // ==================== 浏览器运行时 ====================

    /** Playwright 引擎；必须在专用线程上创建和关闭。 */
    private Playwright playwright;

    /** 持久化上下文代表整个浏览器（没有独立的 Browser 对象），单页签只服务 BOSS。 */
    private BrowserContext context;

    /** BOSS 工作页。 */
    private Page bossPage;

    // 浏览器是否已经关闭（用户手动关窗口、Chrome 崩溃等）。
    // 不检测的话字段还都非空，isInitialized() 依旧返回 true，但页面操作都会抛 TargetClosedError。
    private volatile boolean browserClosed = false;

    /** 生命周期状态：ensureReady 的状态迁移以此为准。 */
    @Getter(AccessLevel.NONE)
    private volatile LifecycleState lifecycleState = LifecycleState.UNINITIALIZED;

    /** 浏览器生命周期状态。 */
    private enum LifecycleState { UNINITIALIZED, READY, CLOSED }

    /** 登录状态变化事件（platform + 新状态 + 时间戳），推给监听器 / SSE。 */
    public record LoginStatusChange(String platform, boolean isLoggedIn, long timestamp) {
    }

    // ==================== 登录状态 ====================

    /** 登录状态追踪（平台 -> 是否已登录）；当前只有 BOSS。 */
    private final Map<String, Boolean> loginStatus = new ConcurrentHashMap<>();

    /** 登录状态监听器：状态变化时推给管理台 / SSE。 */
    private final List<Consumer<LoginStatusChange>> loginStatusListeners = new CopyOnWriteArrayList<>();

    /** 控制是否暂停对 bossPage 的后台监控，避免与任务执行并发访问同一页面。 */
    private volatile boolean bossMonitoringPaused = false;

    /** Cookie 读写（账号登录态持久化）。 */
    @Autowired
    private CookieService cookieService;

    // ==================== Playwright 单线程调度 ====================
    // 约束：Playwright Java 的 Playwright 对象及它派生出的 Page/BrowserContext 只能在创建它的
    // 线程上调用。历史实现里 main / ForkJoinPool / scheduling-1 / http-nio 会同时操作同一个
    // BrowserContext，随机出现 "Object doesn't exist: request@/response@" 和 net::ERR_ABORTED，
    // 一次导航被打断整个投递任务就挂掉。
    // 现在只留一条专用线程串行执行所有 Playwright 入口；已在该线程上的嵌套调用就地执行。

    /** 单线程执行器：Playwright 创建、导航、页面操作全部排在这一条线程上。 */
    private final ExecutorService playwrightExecutor = newPlaywrightExecutor();

    /** 排队中 + 执行中的任务数，给定时监控做"忙就跳过"判断，避免任务堆积。 */
    private final AtomicInteger playwrightQueueDepth = new AtomicInteger();

    private static ExecutorService newPlaywrightExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, PlaywrightDefaults.PW_THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(threadFactory);
    }

    /** 当前线程是否就是 Playwright 专用线程（用于判断嵌套调用要不要就地执行）。 */
    private boolean onPlaywrightThread() {
        Thread currentThread = Thread.currentThread();
        return PlaywrightDefaults.PW_THREAD_NAME.equals(currentThread.getName());
    }

    /**
     * 把任务放到 Playwright 专用线程上执行，并等待返回结果。
     * <p>
     * 若调用方本身就在专用线程上（例如页面事件回调里又调用业务方法），直接就地执行——
     * 否则它会把自己排到自己后面，形成永远等不到的死锁。
     */
    public <T> T callOnPlaywright(Callable<T> task) {
        Callable<T> safeTask = Objects.requireNonNull(task, "task 不能为空");
        if (onPlaywrightThread()) {
            // 重入：调用链已经在专用线程上，直接执行，不能再排队等自己
            return invokeInline(safeTask);
        }
        playwrightQueueDepth.incrementAndGet();
        FutureTask<T> future = new FutureTask<>(() -> {
            try {
                return safeTask.call();
            } finally {
                playwrightQueueDepth.decrementAndGet();
            }
        });
        if (!submitToPlaywrightThread(future)) {
            // 执行器已关闭：回滚计数，避免 isPlaywrightBusy() 永远为 true
            playwrightQueueDepth.decrementAndGet();
            throw new IllegalStateException("Playwright 调度线程已关闭，无法执行任务");
        }
        return awaitPlaywrightResult(future);
    }

    /** 就地执行任务（调用方已经站在 Playwright 专用线程上）。 */
    private <T> T invokeInline(Callable<T> task) {
        try {
            return task.call();
        } catch (Exception executionError) {
            throw toRuntime(executionError);
        }
    }

    /** 提交到专用线程；执行器已关闭时返回 false 而不是抛异常给业务线程。 */
    private boolean submitToPlaywrightThread(Runnable runnable) {
        try {
            playwrightExecutor.execute(runnable);
            return true;
        } catch (RejectedExecutionException rejected) {
            log.warn("Playwright 调度线程已关闭，任务被拒绝: {}", rejected.getMessage());
            return false;
        }
    }

    /** Playwright 线程上是否有前台任务在跑（投递、初始化等）。 */
    public boolean isPlaywrightBusy() {
        return playwrightQueueDepth.get() > 0;
    }

    /** 调试端口（历史配置项，保持返回值不变）。 */
    public int getCdpPort() {
        return PlaywrightDefaults.CDP_PORT;
    }

    /** 把受检异常翻译成调用方约定的运行时异常（运行时异常原样透传）。 */
    private static RuntimeException toRuntime(Throwable throwable) {
        return throwable instanceof RuntimeException runtimeException
                ? runtimeException
                : new RuntimeException(throwable);
    }

    /** 等待任务结果，并把中断/执行异常翻译成调用方约定好的运行时异常。 */
    private <T> T awaitPlaywrightResult(FutureTask<T> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("等待Playwright任务被中断", interrupted);
        } catch (ExecutionException executionError) {
            throw toRuntime(executionError.getCause());
        }
    }

    /** 在专用线程上执行并等待完成（无返回值版本）。 */
    public void runOnPlaywright(Runnable task) {
        callOnPlaywright(asCallable(Objects.requireNonNull(task, "task 不能为空")));
    }

    /** 把 Runnable 适配成 Callable<Void>，让 runOnPlaywright 复用统一调度入口。 */
    private static Callable<Void> asCallable(Runnable action) {
        return () -> {
            action.run();
            return null;
        };
    }

    /**
     * 给定时监控用：Playwright 线程正忙时直接跳过本轮，不排队、不阻塞调度线程。
     * <p>
     * 用 CAS 从 0 抢占计数位，等价于“当前没有前台任务才尝试”，但不会和并发
     * callOnPlaywright 产生“先查后加”的竞态。
     *
     * @return 是否真的提交了任务
     */
    public boolean tryRunOnPlaywright(Runnable task) {
        Runnable safeTask = Objects.requireNonNull(task, "task 不能为空");
        if (!playwrightQueueDepth.compareAndSet(0, 1)) {
            return false;
        }
        boolean submitted = submitToPlaywrightThread(() -> {
            try {
                safeTask.run();
            } catch (Exception backgroundError) {
                log.debug("Playwright后台任务异常: {}", backgroundError.getMessage());
            } finally {
                playwrightQueueDepth.decrementAndGet();
            }
        });
        if (!submitted) {
            playwrightQueueDepth.decrementAndGet();
        }
        return submitted;
    }

    /** 延迟初始化 Playwright；创建动作必须全部在专用线程上完成。 */
    public void init() {
        if (!isInitialized()) {
            runOnPlaywright(this::initInternal);
        }
    }

    /**
     * 把自动化 BOSS 浏览器窗口切到前台。
     * 持久化 Chrome 与日常 Chrome 是两个窗口，后者可能挡住前者。
     */
    public void focusBossPage() {
        callOnPlaywright(() -> {
            if (bossPage != null) {
                bossPage.bringToFront();
            }
            return null;
        });
    }

    /**
     * 异步请求把 BOSS 窗口置前：不能阻塞 HTTP 线程等待 Playwright 队列。
     * 用户点击“显示 BOSS 登录窗口”时只要求尽力置前，失败不影响页面。
     */
    public void requestFocusBossPage() {
        if (onPlaywrightThread()) {
            if (bossPage != null) bossPage.bringToFront();
            return;
        }
        submitToPlaywrightThread(() -> {
            try {
                if (bossPage != null) bossPage.bringToFront();
            } catch (Exception focusError) {
                log.debug("异步置前 BOSS 窗口失败: {}", focusError.getMessage());
            }
        });
    }

    private void initInternal() {
        if (isInitialized()) {
            log.debug("Playwright 已初始化，跳过重复初始化");
            return;
        }
        log.info("初始化 BOSS 浏览器环境");
        try {
            playwright = createPlaywright();
            log.info("Playwright 引擎已启动 | driver={}", describeDriver());

            // 文件系统维护与浏览器生命周期分离：先修 profile 的崩溃退出标记，再启动 Chrome
            ProfileMaintenance.sanitizeExitState(PlaywrightDefaults.USER_DATA_DIR);

            context = launchPersistentBrowser();
            browserClosed = false;
            context.onClose(ignored -> onBrowserClosed());

            prepareBossPage();
            log.info("开始初始化 BOSS 登录态与落地页");
            setupBossPlatform();
            lifecycleState = LifecycleState.READY;
            try {
                focusBossPage();
            } catch (Exception focusError) {
                log.debug("初始化后置前 BOSS 窗口失败: {}", focusError.getMessage());
            }
            log.info("浏览器自动化引擎初始化完成");
        } catch (Exception initError) {
            lifecycleState = LifecycleState.CLOSED;
            log.error("浏览器自动化引擎初始化失败", initError);
            throw new RuntimeException("Playwright初始化失败", initError);
        }
    }

    /** Chrome 窗口被用户关闭或崩溃：标记状态，下一次 ensureReady 会重建。 */
    private void onBrowserClosed() {
        browserClosed = true;
        lifecycleState = LifecycleState.CLOSED;
        log.warn("检测到浏览器已关闭；下次访问前会自动重建");
    }

    /** 用真实 Chrome + 持久化 profile 启动唯一 BOSS 上下文。 */
    private BrowserContext launchPersistentBrowser() {
        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setChannel("chrome")
                .setHeadless(false)
                .setSlowMo(50)
                .setViewportSize(null)
                .setChromiumSandbox(true)
                .setArgs(List.of(
                        "--start-maximized",
                        "--test-type",
                        "--disable-session-crashed-bubble",
                        "--hide-crash-restore-bubble",
                        "--no-first-run",
                        "--no-default-browser-check",
                        "--no-proxy-server",
                        "--proxy-bypass-list=*",
                        "--disable-blink-features=AutomationControlled"
                ));
        log.info("启动 Chromium 持久化上下文 | profile={}", PlaywrightDefaults.USER_DATA_DIR);
        return playwright.chromium().launchPersistentContext(PlaywrightDefaults.USER_DATA_DIR, options);
    }

    /** 创建 BOSS 工作页，并关闭持久化上下文自带的启动空白页。 */
    private void prepareBossPage() {
        List<Page> startupPages = new ArrayList<>(context.pages());
        bossPage = context.newPage();
        bossPage.setDefaultTimeout(PlaywrightDefaults.DEFAULT_TIMEOUT);
        for (Page page : startupPages) {
            try {
                page.close();
            } catch (Exception e) {
                log.debug("关闭浏览器启动空白页失败（忽略）: {}", e.getMessage());
            }
        }
        log.info("BOSS 工作页已就绪，关闭启动空白页 {} 个", startupPages.size());
    }

    /**
     * 创建 Playwright 实例，并优先装配 patchright driver。
     * <p>
     * 为什么要在代码里再找一遍：gradle bootRun 会通过 -Dplaywright.cli.dir 指定 driver，
     * 但从 IDEA 直接跑 main() 时没有这层配置，Playwright 会静默换成官方 driver-bundle，
     * 反检测能力随之失效。这里兜底查找 patchright，保证两种启动方式行为一致。
     */
    private Playwright createPlaywright() {
        boolean patchrightSelected = ensurePatchrightDriverSelected();
        Map<String, String> driverEnv = patchrightSelected ? buildPatchrightDriverEnv() : Map.of();
        return driverEnv.isEmpty()
                ? Playwright.create()
                : Playwright.create(new Playwright.CreateOptions().setEnv(driverEnv));
    }

    /**
     * 保证 driver 指向 patchright：已有启动参数就直接用，没有就自己找目录。
     *
     * @return 最终是否启用了 patchright（false 表示退回官方 driver-bundle）
     */
    private boolean ensurePatchrightDriverSelected() {
        String configuredCliDir = System.getProperty(PlaywrightDefaults.PLAYWRIGHT_CLI_DIR_PROPERTY);
        if (configuredCliDir != null && !configuredCliDir.isBlank()) {
            return true;
        }
        Path patchrightDir = findPatchrightDriverDir();
        if (patchrightDir == null) {
            log.warn("未找到 patchright driver，退回官方 driver-bundle；反检测能力会下降，"
                    + "请先执行 gradlew installPatchrightDriver");
            return false;
        }
        System.setProperty(PlaywrightDefaults.PLAYWRIGHT_CLI_DIR_PROPERTY, patchrightDir.toString());
        return true;
    }

    /** patchright driver 自身不带 node，需要把本机 node 路径通过环境变量交给它。 */
    private Map<String, String> buildPatchrightDriverEnv() {
        if (System.getenv("PLAYWRIGHT_NODEJS_PATH") != null) {
            return Map.of();
        }
        String nodeExecutable = findNodeExecutable();
        if (nodeExecutable == null) {
            log.warn("PATH 中没有 node，Patchright driver 可能无法启动");
            return Map.of();
        }
        log.info("Patchright 使用本机 node: {}", nodeExecutable);
        return Map.of("PLAYWRIGHT_NODEJS_PATH", nodeExecutable);
    }

    /** 找 patchright driver 目录（以 package/cli.js 为判定标准），找不到返回 null。 */
    private Path findPatchrightDriverDir() {
        Path projectDir = Paths.get(System.getProperty("user.dir"));
        return patchrightDriverCandidates(projectDir).stream()
                .filter(this::hasPatchrightCli)
                .findFirst()
                .map(Path::toAbsolutePath)
                .orElse(null);
    }

    /** 按“项目目录 → 上一级”的顺序列出 patchright driver 候选目录。 */
    private List<Path> patchrightDriverCandidates(Path projectDir) {
        List<Path> candidates = new ArrayList<>(2);
        candidates.add(projectDir.resolve("build").resolve("patchright-driver"));
        candidates.add(projectDir.resolve("patchright-driver"));
        // 从子目录启动时往上找一级，兼容 IDE 的工作目录设置
        Path parentDir = projectDir.getParent();
        if (parentDir != null) {
            candidates.add(parentDir.resolve("build").resolve("patchright-driver"));
        }
        return candidates;
    }

    /** 目录是否是装配完成的 patchright driver（只认 package/cli.js）。 */
    private boolean hasPatchrightCli(Path driverDir) {
        return driverDir != null
                && Files.isRegularFile(driverDir.resolve("package").resolve("cli.js"));
    }

    /** 从 PATH 里找 node 可执行文件；Windows 优先 node.exe，找不到再试无后缀 node。 */
    private String findNodeExecutable() {
        String rawPath = System.getenv("PATH");
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String[] executableNames = isWindowsPlatform() ? new String[]{"node.exe", "node"} : new String[]{"node"};
        for (String directoryEntry : rawPath.split(java.io.File.pathSeparator)) {
            Path executable = firstExecutableIn(directoryEntry, executableNames);
            if (executable != null) {
                return executable.toString();
            }
        }
        // PATH 里没有 node：返回 null，由调用方按降级处理
        return null;
    }

    /** 在一个 PATH 目录里按候选名找可执行文件；目录空白/非法时返回 null。 */
    private Path firstExecutableIn(String directoryEntry, String[] executableNames) {
        if (directoryEntry == null || directoryEntry.isBlank()) {
            return null;
        }
        for (String executableName : executableNames) {
            Path candidate = executablePath(directoryEntry.trim(), executableName);
            if (candidate != null) {
                return candidate;
            }
        }
        // 该目录下没有候选可执行文件
        return null;
    }

    /** 拼接并校验可执行文件路径；路径非法返回 null。 */
    private Path executablePath(String directory, String executableName) {
        try {
            Path candidate = Paths.get(directory).resolve(executableName);
            return Files.isRegularFile(candidate) ? candidate.toAbsolutePath() : null;
        } catch (Exception invalidPath) {
            return null;
        }
    }

    private static boolean isWindowsPlatform() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    /** driver 来源说明，仅用于启动日志，方便确认到底跑的是不是 patchright。 */
    private String describeDriver() {
        String cliDir = System.getProperty(PlaywrightDefaults.PLAYWRIGHT_CLI_DIR_PROPERTY);
        return (cliDir == null || cliDir.isBlank())
                ? "官方 driver-bundle（没找到 patchright，反检测能力下降）"
                : "patchright @ " + cliDir;
    }

    /**
     * 设置 BOSS 平台（加载 Cookie、导航、监控）
     */
    private void setupBossPlatform() {
        injectBossCookies();
        if (!navigateBossEntry()) {
            log.warn("BOSS 落地页导航失败，继续等待后续业务页面操作");
        }
        setLoginStatus(COOKIE_PLATFORM_BOSS, checkIfLoggedIn());
        setupLoginMonitoring(bossPage);
    }

    /** 从数据库恢复 BOSS Cookie，注入当前持久化上下文。 */
    private void injectBossCookies() {
        try {
            CookieEntity stored = cookieService.getCookieByPlatform(COOKIE_PLATFORM_BOSS);
            if (stored == null || stored.getCookieValue() == null || stored.getCookieValue().isBlank()) {
                log.info("数据库没有可用的 BOSS Cookie，跳过注入");
                return;
            }
            List<Cookie> cookies = BossCookieJson.filterByDomain(
                    BossCookieJson.parse(stored.getCookieValue()), PlaywrightDefaults.BOSS_DOMAIN);
            if (cookies.isEmpty()) {
                log.warn("BOSS Cookie 解析后为空，跳过注入");
                return;
            }
            context.addCookies(cookies);
            log.info("已注入 BOSS Cookie {} 条", cookies.size());
        } catch (Exception e) {
            log.warn("加载 BOSS Cookie 失败: {}", e.getMessage());
        }
    }

    /** 打开岗位列表落地页；失败时最多重试 3 次，页面已可达也算成功。 */
    private boolean navigateBossEntry() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                bossPage.navigate(PlaywrightDefaults.BOSS_ENTRY_URL, new Page.NavigateOptions()
                        .setTimeout(60_000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                waitBossLoadState();
                return true;
            } catch (Exception e) {
                if (pageLooksLikeBoss()) {
                    waitBossLoadState();
                    return true;
                }
                log.debug("BOSS 落地页第 {} 次导航失败: {}", attempt, e.getMessage());
                if (attempt < 3) sleepQuietly(1_000);
            }
        }
        return false;
    }

    private void waitBossLoadState() {
        try {
            bossPage.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(PlaywrightDefaults.LOAD_STATE_TIMEOUT));
        } catch (Exception e) {
            log.debug("等待 BOSS 网络空闲超时（SPA 常态）: {}", e.getMessage());
        }
    }

    private boolean pageLooksLikeBoss() {
        try {
            String url = bossPage.url();
            return url != null && url.contains("zhipin.com");
        } catch (Exception urlReadError) {
            return false;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 判定 BOSS 当前是否已登录：Cookie 是硬证据，页面用户区次之；
     * 两者都没有时看导航区有没有“登录”入口，没有入口按已登录处理（避免慢加载误报）。
     */
    private boolean checkIfLoggedIn() {
        // 登录页带有访客 Cookie/导航骨架，不能凭 Cookie 或导航区判断已登录。
        if (isBossLoginPage()) {
            return false;
        }
        if (hasLoginEntry()) {
            return false;
        }
        return hasVisibleBossUserArea() || hasBossLoginCookie();
    }

    /** 登录态 Cookie：出现任意一个非空值即视为已登录。 */
    private boolean hasBossLoginCookie() {
        try {
            for (Cookie cookie : context.cookies(PlaywrightDefaults.BOSS_URL)) {
                if (isBossLoginCookie(cookie)) {
                    return true;
                }
            }
        } catch (Exception cookieProbeError) {
            // Cookie 读取失败时继续看 DOM，不让探针异常打断初始化
            log.debug("读取 BOSS Cookie 失败: {}", cookieProbeError.getMessage());
        }
        return false;
    }

    /** 单条 Cookie 是否属于 BOSS 登录态 Cookie。 */
    private boolean isBossLoginCookie(Cookie cookie) {
        return cookie != null
                && PlaywrightDefaults.BOSS_LOGIN_COOKIES.contains(cookie.name)
                && cookie.value != null
                && !cookie.value.isBlank();
    }

    /** 页面右上角出现用户区（头像/昵称）也说明已登录。 */
    private boolean hasVisibleBossUserArea() {
        for (String selector : BossLoginSelectors.USER_AREA_CANDIDATES) {
            try {
                if (isVisibleQuick(bossPage.locator(selector).first())) {
                    return true;
                }
            } catch (Exception pageSwitching) {
                // 页面切换中，继续尝试下一种证据
            }
        }
        return false;
    }

    /** 导航区出现可见的“登录”入口说明未登录。 */
    private boolean hasLoginEntry() {
        try {
            Locator loginArea = bossPage.locator(BossLoginSelectors.LOGIN_ENTRY).first();
            return isVisibleQuick(loginArea) && loginTextSuggestsLogin(loginArea);
        } catch (Exception loginProbeError) {
            return false;
        }
    }

    /** 登录入口的文案是否包含“登录”关键字。 */
    private boolean loginTextSuggestsLogin(Locator loginArea) {
        String loginText = loginArea.textContent(new Locator.TextContentOptions()
                .setTimeout(PlaywrightDefaults.LOCATOR_PROBE_TIMEOUT));
        return loginText != null && loginText.contains("登录");
    }

    /**
     * 可见性探测（带超时；探测失败按“不可见”处理）。
     * <p>
     * 不能直接用 Locator.isVisible()：BOSS 首页会连续做客户端跳转，期间 frame 反复重建，
     * 这个调用会一直等下去（实测把初始化线程焊死了 2 分钟以上都不返回）。
     * 登录检测只是探针，等不到就当作“没看到”，绝不能阻塞主流程。
     */
    private boolean isVisibleQuick(Locator locator) {
        try {
            Locator.IsVisibleOptions probeOptions = new Locator.IsVisibleOptions()
                    .setTimeout(PlaywrightDefaults.LOCATOR_PROBE_TIMEOUT);
            return locator.isVisible(probeOptions);
        } catch (Exception probeTimeout) {
            return false;
        }
    }

    /** 设置登录状态监控：页面主导航完成后触发一次登录探针。 */
    private void setupLoginMonitoring(Page page) {
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) {
                onBossNavigated(page);
            }
        });
        log.info("{}平台登录状态监控已启用", COOKIE_PLATFORM_BOSS);
    }

    /** 页面导航事件回调：重入风险见下，只在空闲且未暂停时做一次登录探针。 */
    private void onBossNavigated(Page page) {
        // 事件回调是在别的调用“进行中”被派发的，此时再调 Playwright 属于重入，
        // 会打断正在进行的导航。所以除了暂停标志，还要避开前台任务运行期。
        if (bossMonitoringPaused || isPlaywrightBusy()) {
            return;
        }
        checkLoginStatus(page, COOKIE_PLATFORM_BOSS);
    }

    /** 当前实现只维护 BOSS 一个平台；Cookie 读写与登录回调都以这一行为准。 */
    private static final String COOKIE_PLATFORM_BOSS = "boss";

    /**
     * 保存 Cookie 到数据库
     *
     * @param platform 平台标识（当前仅支持 boss）
     * @param remark   备注
     */
    public void saveCookiesToDb(String platform, String remark) {
        requireBossPlatform(platform);
        persistBossCookies(remark);
    }

    /** 当前只接受 BOSS 平台；其它平台立即拒绝，避免静默写错 Cookie。 */
    private void requireBossPlatform(String platform) {
        if (!COOKIE_PLATFORM_BOSS.equals(platform)) {
            throw new IllegalArgumentException("Unsupported platform: " + platform);
        }
    }

    /** 主动保存 BOSS Cookie 到数据库（用于调试/验证）。 */
    public void saveBossCookiesToDb(String remark) {
        persistBossCookies(remark);
    }

    /**
     * 检查登录状态：只在“本次从未登录变成已登录”时触发一次登录成功回调，
     * 避免每次导航/定时探测都重复写库。
     *
     * @param page     页面实例（当前登录判定复用 checkIfLoggedIn，参数保留用于日志定位）
     * @param platform 平台名称（当前只有 boss）
     */
    private void checkLoginStatus(Page page, String platform) {
        try {
            if (!isBossLoginJustSucceeded(platform)) {
                return;
            }
            onLoginSuccess(platform);
        } catch (Exception loginCheckError) {
            // 登录探针异常不能影响页面导航与投递主流程
            log.debug("检查{}平台登录状态时发生异常: {}", platform, loginCheckError.getMessage());
        }
    }

    /** 本次检查是否构成“从未登录 → BOSS 已登录”的跃迁。 */
    private boolean isBossLoginJustSucceeded(String platform) {
        if (!COOKIE_PLATFORM_BOSS.equals(platform) || !checkIfLoggedIn()) {
            return false;
        }
        Boolean previousStatus = loginStatus.get(platform);
        return previousStatus == null || !previousStatus;
    }

    /**
     * 登录成功后的统一处理：先广播状态变化，再把 BOSS Cookie 落库（供下次启动注入）。
     */
    private void onLoginSuccess(String platform) {
        log.info("{}平台登录成功", platform);
        setLoginStatus(platform, true);
        if (COOKIE_PLATFORM_BOSS.equals(platform)) {
            persistBossCookies("login success");
        }
    }

    /**
     * 统一的 Boss Cookie 落库：只保存 zhipin.com 域下的 Cookie，JSON 序列化后写 cookie 表。
     *
     * @param remark 备注信息
     */
    private void persistBossCookies(String remark) {
        try {
            List<Cookie> bossCookies = collectBossCookiesForPersistence();
            String cookieJson = BossCookieJson.serialize(bossCookies);
            if (cookieService.saveOrUpdateCookie(COOKIE_PLATFORM_BOSS, cookieJson, remark)) {
                log.info("保存Boss Cookie成功，共 {} 条，remark={}", bossCookies.size(), remark);
            }
        } catch (Exception persistError) {
            log.warn("保存Boss Cookie失败: {}", persistError.getMessage());
        }
    }

    /** 取当前上下文里 zhipin.com 域下的 Cookie（持久化只认 BOSS 域）。 */
    private List<Cookie> collectBossCookiesForPersistence() {
        return BossCookieJson.filterByDomain(context.cookies(), PlaywrightDefaults.BOSS_DOMAIN);
    }

    /**
     * 退出登录时使用：清空当前共享上下文里的全部 Cookie。
     */
    public void clearBossCookies() {
        if (context == null) {
            log.warn("共享上下文不存在，无法清理Cookie");
            return;
        }
        try {
            context.clearCookies();
            log.info("已清理共享上下文中的所有Cookie");
        } catch (Exception clearError) {
            log.error("清理共享上下文Cookie失败: {}", clearError.getMessage(), clearError);
            throw new RuntimeException("清理共享上下文Cookie失败", clearError);
        }
    }

    /**
     * 定时登录检查（每 3 秒）。
     * <p>
     * 导航事件已经能覆盖大部分登录变化，这个轮询是兜底：某些情况下 DOM 状态变了但 URL 没变，
     * 只靠 onFrameNavigated 会漏。探测与上报拆开：这里只负责调度，{@link #probeLoginStatus()}
     * 负责在专用线程里做一次探针。线程正忙（例如正在投递）时直接跳过本轮，避免打断导航。
     */
    @Scheduled(fixedDelay = 3000)
    public void scheduledLoginCheck() {
        if (!isBrowserRuntimeUsable()) {
            return;
        }
        tryRunOnPlaywright(() -> {
            try {
                probeLoginStatus();
            } catch (Exception probeError) {
                log.debug("定时登录检测异常: {}", probeError.getMessage());
            }
        });
    }

    /** 浏览器运行时是否达到可以做探针的状态（未初始化/已销毁时不做无意义轮询）。 */
    private boolean isBrowserRuntimeUsable() {
        return playwright != null && context != null && lifecycleState != LifecycleState.CLOSED;
    }

    /** 在 Playwright 线程上执行一次登录探针；未暂停且页面存在时才检查。 */
    private void probeLoginStatus() {
        if (bossPage == null || bossMonitoringPaused) {
            return;
        }
        checkLoginStatus(bossPage, COOKIE_PLATFORM_BOSS);
    }

    /** 暂停 BOSS 页面后台登录监控，避免与业务流程并发访问同一页面。 */
    public void pauseBossMonitoring() {
        updateBossMonitoringPaused(true);
    }

    /** 恢复 BOSS 页面后台登录监控。 */
    public void resumeBossMonitoring() {
        updateBossMonitoringPaused(false);
    }

    private void updateBossMonitoringPaused(boolean paused) {
        bossMonitoringPaused = paused;
        log.debug(paused ? "BOSS登录监控已暂停" : "BOSS登录监控已恢复");
    }

    /**
     * 关闭 Playwright 实例（Spring 容器销毁前自动执行）。
     * <p>
     * 页面 / 上下文 / 引擎三级各自独立关闭：任意一级失败都不能阻碍后面级别的释放，
     * 否则会留下仍在运行的浏览器进程。关闭后状态置为 CLOSED，下一次 ensureReady 会重建。
     */
    @PreDestroy
    public void destroy() {
        log.info("开始关闭Playwright管理器...");
        closeQuietly("Boss直聘页面", bossPage == null ? null : bossPage::close);
        closeQuietly("共享BrowserContext（浏览器）", context == null ? null : context::close);
        closeQuietly("Playwright实例", playwright == null ? null : playwright::close);
        browserClosed = true;
        lifecycleState = LifecycleState.CLOSED;
        log.info("Playwright管理器关闭完成！");
    }

    /**
     * 关闭单个资源；失败只记日志，绝不让异常阻断后续资源释放。
     *
     * @param resourceName 资源名（写日志用）
     * @param closeAction  关闭动作；资源不存在时传 null
     */
    private void closeQuietly(String resourceName, Runnable closeAction) {
        if (closeAction == null) {
            return;
        }
        try {
            closeAction.run();
            log.info("{}已关闭", resourceName);
        } catch (Exception closeError) {
            log.warn("关闭{}失败（忽略，继续释放剩余资源）: {}", resourceName, closeError.getMessage());
        }
    }

    /**
     * 检查 Playwright 是否已初始化（READY 且运行时对象齐全、浏览器未关闭）。
     */
    public boolean isInitialized() {
        return lifecycleState == LifecycleState.READY
                && hasRuntimeObjects()
                && !browserClosed;
    }

    /** 运行时对象（引擎/上下文/页面）是否都还在引用上。 */
    private boolean hasRuntimeObjects() {
        return playwright != null && context != null && bossPage != null;
    }

    /**
     * 确保浏览器可用：已经关闭或未初始化就重新拉起来。
     * <p>
     * 浏览器被手动关掉或崩溃之后字段可能仍非空，界面上看什么都正常，但页面操作都会抛
     * TargetClosedError —— 投递任务卡死、按钮没反应。发起任务前先过一遍这里。
     * <p>
     * 状态机：UNINITIALIZED --init成功--> READY --窗口关闭/destroy--> CLOSED --ensureReady--> 重建回 READY。
     */
    public synchronized void ensureReady() {
        if (lifecycleState == LifecycleState.READY && isInitialized()) {
            return;
        }
        log.warn("浏览器不可用（state={}），正在重新初始化...", lifecycleState);
        // 旧对象已经失效：先释放再清引用，避免 init() 里的 isInitialized() 被旧状态短路
        discardStaleBrowser();
        init();
        if (isInitialized()) {
            lifecycleState = LifecycleState.READY;
        }
    }

    /** 丢弃上一代浏览器对象：关引擎、清引用、清登录态，并把状态退回 UNINITIALIZED。 */
    private void discardStaleBrowser() {
        closeQuietly("旧Playwright实例", playwright == null ? null : playwright::close);
        playwright = null;
        context = null;
        bossPage = null;
        browserClosed = false;
        loginStatus.clear();
        lifecycleState = LifecycleState.UNINITIALIZED;
    }

    /** 当前记录的登录状态；未知平台按未登录处理。 */
    public boolean isLoggedIn(String platform) {
        return loginStatus.getOrDefault(platform, false);
    }

    /** 注册登录状态监听器（管理台 / SSE 订阅用）。 */
    public void addLoginStatusListener(Consumer<LoginStatusChange> listener) {
        loginStatusListeners.add(Objects.requireNonNull(listener, "listener 不能为空"));
    }

    /** 注销登录状态监听器（不存在的监听器直接忽略）。 */
    public void removeLoginStatusListener(Consumer<LoginStatusChange> listener) {
        loginStatusListeners.remove(Objects.requireNonNull(listener, "listener 不能为空"));
    }

    /**
     * 更新登录状态：状态没变直接返回；变化时推送监听器，未登录时把 BOSS 页引导到登录页。
     */
    public void setLoginStatus(String platform, boolean isLoggedIn) {
        Boolean previousStatus = loginStatus.get(platform);
        if (previousStatus != null && previousStatus == isLoggedIn) {
            return;
        }
        loginStatus.put(platform, isLoggedIn);
        if (COOKIE_PLATFORM_BOSS.equals(platform) && !isLoggedIn) {
            guideBossToLoginPage();
        }
        notifyLoginStatusListeners(new LoginStatusChange(platform, isLoggedIn, System.currentTimeMillis()));
    }

    /** 逐个通知监听器；单个监听器异常不影响其他订阅者。 */
    private void notifyLoginStatusListeners(LoginStatusChange change) {
        for (Consumer<LoginStatusChange> listener : loginStatusListeners) {
            try {
                listener.accept(change);
            } catch (Exception notifyError) {
                log.warn("推送登录状态失败 | platform={} | loggedIn={} | {}",
                        change.platform(), change.isLoggedIn(), notifyError.getMessage());
            }
        }
    }

    /** 未登录时把 BOSS 页面带到登录页，并尽量切到扫码模式。 */
    private void guideBossToLoginPage() {
        if (bossPage == null) {
            return;
        }
        try {
            if (!isBossLoginPage()) {
                bossPage.navigate(PlaywrightDefaults.BOSS_LOGIN_URL);
                sleepQuietly(800);
            }
            boolean scanModeSwitched = clickFirstVisible(BossLoginSelectors.SCAN_MODE_SWITCHES);
            log.info(scanModeSwitched
                    ? "BOSS 登录页已切换到扫码模式"
                    : "BOSS 登录页未找到扫码切换入口，保持当前模式");
        } catch (Exception guideError) {
            log.debug("引导 BOSS 登录页失败: {}", guideError.getMessage());
        }
    }

    /** 当前页面是否已经在 BOSS 登录页（避免重复导航打断扫码）。 */
    private boolean isBossLoginPage() {
        try {
            String currentUrl = bossPage.url();
            return currentUrl != null && currentUrl.contains("/web/user/");
        } catch (Exception urlError) {
            return false;
        }
    }

    /** 依次尝试候选选择器，点到第一个可见的即返回 true。 */
    private boolean clickFirstVisible(List<String> selectors) {
        for (String selector : selectors) {
            try {
                Locator locator = toLoginLocator(selector);
                if (locator != null && isVisibleQuick(locator)) {
                    locator.click();
                    return true;
                }
            } catch (Exception selectorError) {
                log.debug("登录页选择器不可用: {} | {}", selector, selectorError.getMessage());
            }
        }
        return false;
    }

    /** 登录页选择器支持 text= 前缀写法（走 getByText），其余按 CSS 处理。 */
    private Locator toLoginLocator(String selector) {
        if (selector == null || selector.isBlank() || bossPage == null) {
            return null;
        }
        return selector.startsWith("text=")
                ? bossPage.getByText(selector.substring("text=".length())).first()
                : bossPage.locator(selector).first();
    }
}
