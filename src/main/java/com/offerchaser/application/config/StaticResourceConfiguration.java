package com.offerchaser.application.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 管理台静态资源托管。
 *
 * <p>同一个后端既可以服务「前端开发服务器（6866）」，也可以直接托管导出的 dist/static。
 * 这里把文件系统目录排在 classpath 前面，前端重新导出后刷新页面即可生效，不必重新打包。
 * 因为 Next.js 是静态导出，路径到文件的映射不完全等同于普通静态站点，所以额外补了
 * {@code .html}、{@code .txt}（RSC 数据）和 {@code index.html} 的回退规则。</p>
 */
@Slf4j
@Configuration
public class StaticResourceConfiguration implements WebMvcConfigurer {

    private static final String API_PATH_PREFIX = "api/";
    private static final int FRONTEND_PORT = 6866;
    private static final int PROBE_TIMEOUT_MS = 1_000;

    private static final String DIST_DIR = "src/main/resources/dist";
    private static final String STATIC_DIR = "src/main/resources/static";
    /** 静态资源查找顺序：文件系统优先，classpath（打包进 JAR）兜底。 */
    private static final String[] RESOURCE_LOCATIONS = {
            "file:src/main/resources/dist/",
            "file:src/main/resources/static/",
            "classpath:/dist/",
            "classpath:/static/"
    };
    private static final String[] PROBE_HOSTS = {"127.0.0.1", "[::1]", "localhost"};

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        boolean hasFrontendService = detectFrontendService();
        Path dist = Path.of(DIST_DIR);
        Path staticDir = Path.of(STATIC_DIR);
        boolean hasDist = Files.exists(dist);
        boolean hasStatic = Files.exists(staticDir);

        if (!hasDist && !hasStatic) {
            log.warn("未找到静态资源目录 (dist 或 static)");
            return;
        }

        log.info("配置静态资源服务:");
        if (hasFrontendService) {
            log.info(" 使用前端开发服务 (端口 {})", FRONTEND_PORT);
        } else {
            if (hasDist) {
                log.info("使用 dist 目录: {}", dist.toAbsolutePath());
            }
            if (hasStatic) {
                log.info("使用 static 目录: {}", staticDir.toAbsolutePath());
            }
        }

        registry.addResourceHandler("/**")
                .addResourceLocations(RESOURCE_LOCATIONS)
                .setCachePeriod(0)
                .resourceChain(true)
                .addResolver(new StaticExportResolver());
    }

    /** 根路径转给 index.html，保证直接访问域名根时进入管理台。 */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    /** 探测本机前端开发服务是否在运行（只影响日志展示，不改变资源注册）。 */
    private boolean detectFrontendService() {
        for (String host : PROBE_HOSTS) {
            HttpURLConnection connection = null;
            try {
                URL url = new URI("http", null, unbracket(host), FRONTEND_PORT, "/", null, null).toURL();
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(PROBE_TIMEOUT_MS);
                connection.setReadTimeout(PROBE_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(false);
                int status = connection.getResponseCode();
                if (status >= 200 && status < 500) {
                    return true;
                }
            } catch (Exception probeFailure) {
                // 端口没开/地址不可达都属正常，继续探测下一个地址
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        return false;
    }

    /** URI 的 host 参数不接受方括号形式的 IPv6 字面量。 */
    private static String unbracket(String host) {
        if (host.length() > 1 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /**
     * Next.js 静态导出的资源解析器：先找精确路径；无扩展名的页面路径再依次尝试
     * 同名 .html、同名 .txt（RSC 数据）和 index.html。API 路径不参与这些回退，
     * 避免把后端接口请求吞成首页。任何 IO 异常都按“没找到”处理。
     */
    private static final class StaticExportResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) {
            try {
                return resolvePageResource(resourcePath, location);
            } catch (IOException unreadable) {
                return null;
            }
        }

        private Resource resolvePageResource(String resourcePath, Resource location) throws IOException {
            Resource exact = location.createRelative(resourcePath);
            if (isReadable(exact)) {
                return exact;
            }
            if (resourcePath.startsWith(API_PATH_PREFIX) || resourcePath.contains(".")) {
                return null;
            }
            Resource html = location.createRelative(resourcePath + ".html");
            if (isReadable(html)) {
                return html;
            }
            Resource txt = location.createRelative(resourcePath + ".txt");
            if (isReadable(txt)) {
                return txt;
            }
            Resource index = location.createRelative("index.html");
            return isReadable(index) ? index : null;
        }

        /** 只有存在且可读的资源才算命中；读取属性异常按未命中处理。 */
        private static boolean isReadable(Resource resource) {
            try {
                return resource.exists() && resource.isReadable();
            } catch (RuntimeException unreadable) {
                return false;
            }
        }
    }
}
