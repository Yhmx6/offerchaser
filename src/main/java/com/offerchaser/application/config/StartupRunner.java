package com.offerchaser.application.config;

import com.offerchaser.worker.manager.PlaywrightManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 启动收尾动作：先给用户把管理台打开，再初始化浏览器自动化。
 *
 * <p>管理台地址按优先级选择：本机 6866 上的前端开发服务 → 后端自带静态资源（dist 已构建）
 * → 都没有则只记日志。浏览器打开失败不影响应用启动；Playwright 初始化失败则向上抛出，
 * 让启动显式失败而不是带着一个不可用的浏览器管理器继续跑。</p>
 */
@Slf4j
@Component
public class StartupRunner implements ApplicationRunner {

    /** 开发模式前端（静态服务器）固定端口。 */
    private static final int FRONTEND_PORT = 6866;
    /** 前端开发服务的访问地址。 */
    private static final String FRONTEND_URL = "http://localhost:" + FRONTEND_PORT;
    /** 后端管理台使用回环地址 + server.port。 */
    private static final String BACKEND_URL_PREFIX = "http://localhost:";
    /** 前端导出的静态资源目录（打包进 JAR 前的位置）。 */
    private static final String STATIC_BUNDLE_DIR = "src/main/resources/dist";
    /** 本机服务探测顺序：IPv4 → IPv6 → 主机名。 */
    private static final String[] PROBE_HOSTS = {"127.0.0.1", "[::1]", "localhost"};
    private static final int PROBE_TIMEOUT_MS = 2_000;

    private final PlaywrightManager playwrightManager;

    @Value("${server.port:9527}")
    private int backendPort;

    public StartupRunner(PlaywrightManager playwrightManager) {
        this.playwrightManager = playwrightManager;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String managementUrl = resolveManagementUrl();
        if (managementUrl == null) {
            log.info("未找到可用的管理页面，跳过自动打开浏览器");
        } else {
            openInDefaultBrowser(managementUrl);
        }

        // Playwright 初始化放在打开管理页之后：它拉起浏览器内核较慢，不应挡住用户看到界面。
        try {
            playwrightManager.init();
        } catch (Exception initFailure) {
            log.error("Playwright 初始化失败: {}", initFailure.getMessage());
            throw initFailure;
        }
    }

    /** 选择要打开的管理台地址：前端开发服务 > 后端静态资源 > null。 */
    private String resolveManagementUrl() {
        if (isLocalServiceListening(FRONTEND_PORT)) {
            log.debug("检测到前端开发服务（端口 {}），直接打开", FRONTEND_PORT);
            return FRONTEND_URL;
        }
        if (hasExportBundle()) {
            log.info("检测到后端静态资源，使用后端 URL");
            return BACKEND_URL_PREFIX + backendPort;
        }
        log.info("未检测到前端服务或静态资源");
        return null;
    }

    /** 探测本机端口上是否有 HTTP 服务：2xx/3xx/4xx 都算存活（本地管理台不需要 200）。 */
    private boolean isLocalServiceListening(int port) {
        for (String host : PROBE_HOSTS) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) URI.create("http://" + host + ":" + port)
                        .toURL()
                        .openConnection();
                connection.setConnectTimeout(PROBE_TIMEOUT_MS);
                connection.setReadTimeout(PROBE_TIMEOUT_MS);
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                int status = connection.getResponseCode();
                if (status >= 200 && status < 500) {
                    log.debug("检测到端口 {} 的服务 (地址: {})", port, host);
                    return true;
                }
            } catch (Exception probeFailure) {
                log.debug("端口 {} HTTP服务检测失败 ({}): {}", port, host, probeFailure.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        return false;
    }

    /** dist 目录存在且至少有一个可见文件，才认为后端能托管管理台。 */
    private boolean hasExportBundle() {
        Path distDir = Path.of(STATIC_BUNDLE_DIR);
        if (!Files.isDirectory(distDir)) {
            return false;
        }
        int visibleEntries = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(distDir)) {
            for (Path entry : entries) {
                if (!entry.getFileName().toString().startsWith(".")) {
                    visibleEntries++;
                }
            }
        } catch (IOException scanFailure) {
            log.debug("检查静态资源时出错: {}", scanFailure.getMessage());
            return false;
        }
        if (visibleEntries > 0) {
            log.info("发现静态资源文件夹: {}, 文件数: {}", distDir.toAbsolutePath(), visibleEntries);
            return true;
        }
        return false;
    }

    /** 按操作系统选择打开命令；未知系统只提示手动访问。 */
    private void openInDefaultBrowser(String url) {
        List<String> command = browserCommandFor(System.getProperty("os.name", ""), url);
        if (command == null) {
            log.warn("未知操作系统类型，无法自动打开浏览器，请手动访问: {}", url);
            return;
        }
        try {
            new ProcessBuilder(command).start();
            log.info("已打开浏览器: {}", url);
        } catch (IOException openFailure) {
            log.error("打开浏览器失败: {}", openFailure.getMessage());
            log.info("请手动访问管理页面: {}", url);
        }
    }

    /** 不同系统的“用默认浏览器打开 URL”命令；不认识的系统返回 null。 */
    private static List<String> browserCommandFor(String osName, String url) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            // 空字符串参数是 cmd start 用来区分“窗口标题”和 URL 的占位符。
            return List.of("cmd", "/c", "start", "", url);
        }
        if (os.contains("mac")) {
            return List.of("open", url);
        }
        if (os.contains("nix") || os.contains("nux")) {
            return List.of("xdg-open", url);
        }
        return null;
    }
}
