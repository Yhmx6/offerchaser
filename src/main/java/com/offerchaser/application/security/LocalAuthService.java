package com.offerchaser.application.security;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阶段 B B5：本机会话认证服务。
 * <p>
 * 设计约束（见 B5 契约）：
 * <ul>
 *   <li>长期令牌只存在本机 data 目录文件中，不写入配置、数据库、日志或任何未认证读接口；</li>
 *   <li>登录成功后使用 HttpOnly + SameSite=Strict Cookie 保存内存会话（重启失效）；</li>
 *   <li>支持 {@code X-Session-Token: <本次会话ID>} 请求头（供本机 API 客户端/SSE 备用）；</li>
 *   <li>只保护写操作与 Cookie 接口；只读 GET 保持开放，便于本地回环下正常浏览与 SSE。</li>
 * </ul>
 */
@Slf4j
@Service
public class LocalAuthService {

    public static final String SESSION_COOKIE = "OFFERCHASER_SESSION";
    public static final String SESSION_HEADER = "X-Session-Token";
    public static final String CSRF_HEADER = "X-Requested-With";
    public static final String CSRF_HEADER_VALUE = "offerchaser";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_LOGIN_FAILURES = 5;
    private static final long FAILURE_WINDOW_SECONDS = 60L;

    @Value("${offerchaser.security.auth-enabled:true}")
    private boolean authEnabled = true;

    @Value("${offerchaser.security.session-ttl-hours:168}")
    private long sessionTtlHours = 168;

    @Value("${offerchaser.security.token-file:data/local-session-token}")
    private String tokenFile = "data/local-session-token";

    @Value("${offerchaser.security.require-csrf-header:true}")
    private boolean requireCsrfHeader = true;

    /** token 的 SHA-256（常量时间比较）；初始化失败时为 null。 */
    private volatile byte[] tokenHash;
    private volatile boolean initialized;
    private Path tokenPath;

    /** sessionId -> 到期时间 */
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    /** ip -> 最近失败时间戳 */
    private final Map<String, Deque<Long>> loginFailures = new ConcurrentHashMap<>();

    @PostConstruct
    void initialize() {
        Path path = Paths.get(tokenFile).toAbsolutePath().normalize();
        this.tokenPath = path;
        if (!authEnabled) {
            log.info("本地会话认证未启用（offerchaser.security.auth-enabled=false），管理台写操作不做认证");
            initialized = false;
            return;
        }
        try {
            String token = loadOrCreateToken(path);
            this.tokenHash = sha256(token);
            this.initialized = true;
            log.info("本地会话认证已启用；令牌文件：{}（请勿把该文件提交或外传）", path);
        } catch (Exception e) {
            initialized = false;
            log.error("本地会话令牌初始化失败，管理台写操作将被拒绝：{}", e.getMessage(), e);
        }
    }

    private String loadOrCreateToken(Path path) throws Exception {
        if (Files.isRegularFile(path)) {
            String existing = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (!existing.isEmpty()) {
                return existing;
            }
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, token, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignored) {
            // Windows 无 POSIX 权限；data 目录本身不纳入版本管理
        }
        log.info("首次启动：已生成新的本地会话令牌文件（内容不在日志输出）");
        return token;
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isRequireCsrfHeader() {
        return requireCsrfHeader;
    }

    /** 未认证读接口可返回的提示（路径，不含 token 内容）。 */
    public String tokenFileHint() {
        return tokenPath == null ? tokenFile : tokenPath.toString();
    }

    /** 常量时间校验本地长期令牌。 */
    public boolean verifyLocalToken(String candidate) {
        if (!authEnabled) {
            return true;
        }
        if (!initialized || tokenHash == null || candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(tokenHash, sha256(candidate.trim()));
    }

    /** 登录成功创建会话；返回 sessionId。 */
    public String createSession() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plusSeconds(Math.max(1, sessionTtlHours) * 3600L);
        sessions.put(sessionId, expiresAt);
        cleanupExpiredSessions();
        return sessionId;
    }

    public Instant sessionExpiresAt(String sessionId) {
        return sessionId == null ? null : sessions.get(sessionId);
    }

    public int sessionMaxAgeSeconds() {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, sessionTtlHours) * 3600L);
    }

    public void invalidateSession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    public boolean isSessionValid(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Instant expiresAt = sessions.get(sessionId);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt.isBefore(Instant.now())) {
            sessions.remove(sessionId);
            return false;
        }
        return true;
    }

    /** 从 Cookie 或 X-Session-Token 请求头读取会话并校验。 */
    public boolean isAuthenticated(HttpServletRequest request) {
        if (!authEnabled) {
            return true;
        }
        String headerSession = request.getHeader(SESSION_HEADER);
        if (isSessionValid(headerSession)) {
            return true;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (SESSION_COOKIE.equals(cookie.getName()) && isSessionValid(cookie.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    public String sessionIdFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    // ==================== 登录节流 ====================

    public boolean isLoginThrottled(String ip) {
        Deque<Long> failures = loginFailures.get(ipKey(ip));
        if (failures == null) {
            return false;
        }
        long cutoff = System.currentTimeMillis() - FAILURE_WINDOW_SECONDS * 1000L;
        synchronized (failures) {
            failures.removeIf(timestamp -> timestamp < cutoff);
            return failures.size() >= MAX_LOGIN_FAILURES;
        }
    }

    public void recordLoginFailure(String ip) {
        Deque<Long> failures = loginFailures.computeIfAbsent(ipKey(ip), key -> new ArrayDeque<>());
        synchronized (failures) {
            failures.addLast(System.currentTimeMillis());
            while (failures.size() > MAX_LOGIN_FAILURES * 2) {
                failures.removeFirst();
            }
        }
    }

    public void clearLoginFailures(String ip) {
        loginFailures.remove(ipKey(ip));
    }

    private static String ipKey(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip;
    }

    private void cleanupExpiredSessions() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
