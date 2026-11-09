package com.offerchaser.application.config;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 静态资源服务器配置。
 * <p>
 * 前端 dev 服务未占用端口时，在配置端口（默认 6866）追加一个静态资源连接器，
 * 让后端可直接托管导出的 dist/static。端口与绑定地址来自
 * {@code offerchaser.static-server.port} / {@code offerchaser.static-server.address}，
 * 默认只监听 127.0.0.1，避免把管理台暴露到局域网。
 * <p>
 * dev 服务判断只看 TCP 端口是否可连接（不发 HTTP 请求）：本机已有进程监听该端口，
 * 就认为“前端 dev 服务在跑”，此时不挂静态连接器（避免端口冲突、也避免误抢 dev 服务）。
 */
@Slf4j
@Configuration
public class StaticServerConfiguration {

    /** 静态资源服务默认端口（与前端 dev 服务保持一致）。 */
    private static final int DEFAULT_FRONTEND_PORT = 6866;
    /** 默认绑定回环地址。 */
    private static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    private static final String DIST_PATH = "src/main/resources/dist";
    private static final String STATIC_PATH = "src/main/resources/static";

    /** dev 服务探测主机顺序：IPv4 → IPv6 → 主机名（前端可能只监听其中一种）。 */
    private static final String[] DEV_PROBE_HOSTS = {"127.0.0.1", "::1", "localhost"};
    /** 回环地址探测的显式超时（毫秒）：连不上应立刻失败，不能把应用启动拖住。 */
    private static final int DEV_PROBE_TIMEOUT_MS = 500;

    /** 静态资源服务端口（读配置，默认 6866）。 */
    @Value("${offerchaser.static-server.port:" + DEFAULT_FRONTEND_PORT + "}")
    private int frontendPort;

    /** 静态资源服务绑定地址（读配置，默认 127.0.0.1）。 */
    @Value("${offerchaser.static-server.address:" + DEFAULT_BIND_ADDRESS + "}")
    private String staticServerAddress;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> servletContainer() {
        return this::attachStaticConnectorWhenNeeded;
    }

    /** 前端 dev 服务未运行时，按需追加静态资源连接器。 */
    private void attachStaticConnectorWhenNeeded(TomcatServletWebServerFactory factory) {
        if (devServerListening()) {
            log.info("检测到前端开发服务运行在端口 {}", frontendPort);
            return;
        }
        if (!staticContentAvailable()) {
            log.warn("未检测到前端开发服务，也未找到静态资源");
            return;
        }
        String bindAddress = configuredBindAddress();
        log.info("未检测到前端开发服务，但找到静态资源");
        log.info("配置额外端口 {}（绑定地址 {}）用于提供静态资源", frontendPort, bindAddress);

        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setPort(frontendPort);
        // 绑定地址由 Tomcat protocol handler 的 address 控制，默认只监听回环地址
        applyBindAddress(connector, bindAddress);
        factory.addAdditionalTomcatConnectors(connector);
    }

    /** 配置为空时回退到默认回环地址，避免 Tomcat 默认监听所有网卡。 */
    private String configuredBindAddress() {
        if (staticServerAddress == null || staticServerAddress.isBlank()) {
            return DEFAULT_BIND_ADDRESS;
        }
        return staticServerAddress.trim();
    }

    /**
     * 给额外 Connector 设置绑定地址。
     * <p>
     * 不用 {@code connector.setProperty("address", ...)} 作为首选：Connector 的 address 属性最终落到
     * {@link AbstractProtocol#setAddress(InetAddress)}，字符串转 InetAddress 不一定生效；
     * 这里与 Spring Boot 处理 {@code server.address} 的方式一致，直接写到 protocol handler。
     */
    private void applyBindAddress(Connector connector, String bindAddress) {
        try {
            if (connector.getProtocolHandler() instanceof AbstractProtocol<?> protocol) {
                protocol.setAddress(InetAddress.getByName(bindAddress));
            } else {
                // 非标准协议处理器时退回属性设置，至少保留配置意图
                connector.setProperty("address", bindAddress);
            }
        } catch (UnknownHostException unresolved) {
            // 解析失败必须让启动失败：否则 Tomcat 退回监听所有网卡，管理台会暴露到局域网
            throw new IllegalStateException("无法解析静态资源服务绑定地址: " + bindAddress, unresolved);
        }
    }

    /** 探测前端 dev 服务是否已占用端口（TCP 可连接即视为在跑）。 */
    private boolean devServerListening() {
        for (String host : DEV_PROBE_HOSTS) {
            if (canConnect(host, frontendPort, DEV_PROBE_TIMEOUT_MS)) {
                log.debug("端口 {} 已有服务监听（host={}），按前端开发服务在运行处理", frontendPort, host);
                return true;
            }
        }
        log.debug("端口 {} 无服务监听，按无前端开发服务处理", frontendPort);
        return false;
    }

    /** 在给定超时内能建立到 host:port 的 TCP 连接即返回 true。 */
    private boolean canConnect(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception connectFailure) {
            log.debug("端口探测失败 ({}:{}): {}", host, port, connectFailure.getMessage());
            return false;
        }
    }

    /** dist 或 static 任一目录有可服务内容即返回 true。 */
    private boolean staticContentAvailable() {
        Path dist = Paths.get(DIST_PATH);
        Path staticDir = Paths.get(STATIC_PATH);

        log.info("检查静态资源路径:");
        log.info("  dist路径: {} (绝对路径: {})", DIST_PATH, dist.toAbsolutePath());
        log.info("  static路径: {} (绝对路径: {})", STATIC_PATH, staticDir.toAbsolutePath());

        boolean distPresent = directoryHasEntries(dist);
        boolean staticPresent = directoryHasEntries(staticDir);

        log.info("  dist存在: {}", distPresent);
        log.info("  static存在: {}", staticPresent);

        return distPresent || staticPresent;
    }

    /** 目录存在且至少有一个条目时返回 true；读取失败按“无内容”处理。 */
    private boolean directoryHasEntries(Path directory) {
        try {
            if (!Files.exists(directory)) {
                return false;
            }
            try (var entries = Files.list(directory)) {
                return entries.findAny().isPresent();
            }
        } catch (IOException inspectFailure) {
            log.error("检查路径失败: {}", directory, inspectFailure);
            return false;
        }
    }
}
