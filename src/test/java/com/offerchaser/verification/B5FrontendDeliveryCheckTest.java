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
 * B5 前端认证交付检查（契约第 4 节）：
 * AuthGate 使用真实 session API 与 401 重登、令牌不落 localStorage/sessionStorage、
 * API base 按页面 host 推导（同源 Cookie）、TopBar 锁定退出、dist 含认证代码。
 */
class B5FrontendDeliveryCheckTest {

    private static final Path FRONT = Path.of("front");
    private static final Path DIST = Path.of("src", "main", "resources", "dist");

    @Test
    void authGateUsesRealSessionApiAndRelogin() throws Exception {
        Path gate = FRONT.resolve("app/components/AuthGate.tsx");
        assertTrue(Files.isRegularFile(gate), "front 缺少 AuthGate 登录门组件");
        assertTrue(Files.size(gate) > 4_000, "AuthGate 过小，可能不是完整登录层");
        String source = Files.readString(gate);

        for (String function : List.of("getSessionStatus", "login", "subscribeAuthRequired")) {
            assertTrue(source.contains(function), "AuthGate 必须使用真实认证 API：" + function);
        }
        assertTrue(source.contains("authEnabled") && source.contains("authenticated"),
                "AuthGate 必须根据 authEnabled/authenticated 决定是否弹登录");
        assertTrue(source.contains("tokenFileHint"), "登录层必须提示令牌文件路径");
        assertTrue(source.contains("data/local-session-token"), "必须提示本地令牌位置");
        assertTrue(source.contains("HttpOnly"), "登录层应说明令牌保存在 HttpOnly Cookie 中");
        assertTrue(source.contains("reload"), "登录成功后应刷新页面数据");
        assertTrue(source.contains("INVALID_TOKEN") || source.contains("message"),
                "登录失败必须展示后端 message（如 INVALID_TOKEN/TOO_MANY_ATTEMPTS）");
        assertTrue(source.contains("DialogShell"), "登录层应使用统一 DialogShell");
        assertTrue(source.contains("dismissable={false}"), "登录门不可通过遮罩/Esc 误关闭");

        // 根布局必须挂载 AuthGate
        String layout = Files.readString(FRONT.resolve("app/layout.tsx"));
        assertTrue(layout.contains("AuthGate"), "根布局必须挂载 AuthGate");
        assertTrue(layout.contains("<AuthGate"), "AuthGate 必须包住页面内容");
    }

    @Test
    void tokenIsNotPersistedToWebStorage() throws Exception {
        List<Path> files = List.of(
                FRONT.resolve("app/components/AuthGate.tsx"),
                FRONT.resolve("lib/auth.ts"),
                FRONT.resolve("lib/api-base.ts"),
                FRONT.resolve("lib/api.ts"));
        for (Path file : files) {
            String source = Files.readString(file);
            for (String forbidden : List.of("localStorage.setItem", "sessionStorage.setItem",
                    "localStorage.getItem", "sessionStorage.getItem", "document.cookie")) {
                assertFalse(source.contains(forbidden),
                        file.getFileName() + " 不得持久化令牌/直接写 document.cookie：" + forbidden);
            }
        }
    }

    @Test
    void authClientAndApiWiringAreReal() throws Exception {
        String auth = Files.readString(FRONT.resolve("lib/auth.ts"));
        for (String function : List.of("getSessionStatus", "login", "logout")) {
            assertTrue(auth.contains("function " + function), "lib/auth.ts 缺少函数：" + function);
        }
        assertTrue(auth.contains("/api/session/status") && auth.contains("/api/session/login")
                        && auth.contains("/api/session/logout"),
                "lib/auth.ts 必须真实调用会话接口");
        assertTrue(auth.contains("AUTH_REQUIRED_EVENT") && auth.contains("offerchaser:auth-required"),
                "必须有 401 事件总线 offerchaser:auth-required");
        assertTrue(auth.contains("credentials: 'include'"), "会话请求必须携带 Cookie");

        String api = Files.readString(FRONT.resolve("lib/api.ts"));
        assertTrue(api.contains("credentials: 'include'"), "API 封装必须 credentials: include");
        assertTrue(api.contains("X-Requested-With") && api.contains("offerchaser"),
                "写操作必须带 X-Requested-With: offerchaser（CSRF 头）");
        assertTrue(api.contains("AuthRequiredError") && api.contains("dispatchAuthRequired"),
                "401/403 CSRF 必须派发 AuthRequired 供 AuthGate 重登");
        assertTrue(api.contains("CSRF_HEADER_MISSING"), "必须识别 403 CSRF_HEADER_MISSING 为认证需要");
    }

    @Test
    void apiBaseDerivesFromPageHostForSameSiteCookie() throws Exception {
        Path base = FRONT.resolve("lib/api-base.ts");
        assertTrue(Files.isRegularFile(base), "缺少 lib/api-base.ts（API base 统一入口）");
        String source = Files.readString(base);
        assertTrue(source.contains("NEXT_PUBLIC_API_BASE"), "必须支持显式 API base 覆盖");
        assertTrue(source.contains("window.location.hostname"), "必须按页面 host 推导 API base（Cookie 同源）");
        assertTrue(source.contains("isLoopbackHostname"), "只对 localhost/127.0.0.1 做同源推导");
        assertTrue(source.contains("9527"), "默认 API 端口应为 9527");
        assertTrue(source.contains("SameSite") || source.contains("同源") || source.contains("session"),
                "应说明同源推导是为了会话 Cookie 生效");
    }

    @Test
    void topBarHasLockLogoutEntry() throws Exception {
        // 5 空间 shell：锁定/退出入口从 Sidebar 迁到 TopBar（运行胶囊同层）。
        String topBar = Files.readString(FRONT.resolve("app/components/shell/TopBar.tsx"));
        assertTrue(topBar.contains("logout"), "TopBar 必须调用 logout()");
        assertTrue(topBar.contains("dispatchAuthRequired"), "退出后应弹登录层");
        assertTrue(topBar.contains("handleLock") && topBar.contains("锁定管理台"),
                "TopBar 必须提供锁定管理台入口");
        assertTrue(topBar.contains("authEnabled") || Files.readString(FRONT.resolve("app/components/AuthGate.tsx"))
                        .contains("authEnabled"),
                "认证开关应由会话状态驱动");
        assertTrue(Files.readString(FRONT.resolve("app/layout.tsx")).contains("AuthGate"),
                "根布局必须保留登录门");
        assertTrue(Files.readString(FRONT.resolve("app/components/shell/ThemeProvider.tsx"))
                        .contains("THEME_ORDER"),
                "TopBar 旁的主题入口应保留三套主题");
    }

    @Test
    void distContainsAuthCode() throws Exception {
        assertTrue(Files.isRegularFile(DIST.resolve("index.html")), "dist 缺少 index.html");
        assertTrue(distContainsAny("/api/session/status"), "dist 构建产物应包含会话状态接口调用");
        assertTrue(distContainsAny("offerchaser:auth-required"), "dist 应包含 401 重登事件");
        assertTrue(distContainsAny("X-Requested-With"), "dist 应包含 CSRF 请求头");
        long distFiles;
        try (Stream<Path> stream = Files.walk(DIST)) {
            distFiles = stream.filter(Files::isRegularFile).count();
        }
        assertTrue(distFiles >= 130, "dist 文件数量异常（移除三平台页面后应约 143）：" + distFiles);
    }

    private static boolean distContainsAny(String needle) throws Exception {
        try (Stream<Path> stream = Files.walk(DIST)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".js") || name.endsWith(".html") || name.endsWith(".txt");
                    }).toList();
            for (Path file : files) {
                if (Files.readString(file).contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }
}
