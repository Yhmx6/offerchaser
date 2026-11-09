package com.offerchaser.worker.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * BOSS Cookie 的 JSON 转换与域过滤。
 * <p>
 * 从 PlaywrightManager 中拆出的纯数据职责：输入是数据库里的 JSON 字符串或
 * Playwright 的 Cookie 列表，输出是可直接注入/落库的对象，不接触浏览器上下文。
 */
@Slf4j
final class BossCookieJson {

    private static final ObjectMapper COOKIE_MAPPER = new ObjectMapper();

    private BossCookieJson() {
    }

    /**
     * 把数据库里的 Cookie JSON 解析成 Playwright Cookie 列表。
     * <p>
     * 约定只接受 JSON 数组；单条缺 name/value 的记录直接跳过。解析失败返回空列表
     * 并记录错误，由调用方按“没有可用 Cookie”处理，不影响启动。
     */
    static List<Cookie> parse(String cookieJson) {
        List<Cookie> cookies = new ArrayList<>();
        if (cookieJson == null || cookieJson.isBlank()) {
            return cookies;
        }
        try {
            JsonNode root = COOKIE_MAPPER.readTree(cookieJson);
            if (root == null || !root.isArray()) {
                log.warn("BOSS Cookie JSON 不是数组，忽略");
                return cookies;
            }
            for (JsonNode node : root) {
                Cookie cookie = toCookie(node);
                if (cookie != null) {
                    cookies.add(cookie);
                }
            }
            log.debug("已解析 BOSS Cookie {} 条", cookies.size());
        } catch (Exception parseError) {
            log.error("解析 BOSS Cookie JSON 失败: {}", parseError.getMessage());
        }
        return cookies;
    }

    /** 把 Cookie 列表序列化成数据库存储用的 JSON（失败抛运行时异常，由调用方兜底记日志）。 */
    static String serialize(List<Cookie> cookies) {
        try {
            return COOKIE_MAPPER.writeValueAsString(cookies);
        } catch (Exception serializeError) {
            throw new IllegalStateException("序列化 BOSS Cookie 失败", serializeError);
        }
    }

    /**
     * 只保留落在指定域后缀（含其子域）上的 Cookie。
     * <p>
     * 例：suffix=zhipin.com 时保留 domain=zhipin.com 与 *.zhipin.com；
     * 无 domain 的 Cookie 一律丢弃（注入时无法确认归属）。
     */
    static List<Cookie> filterByDomain(List<Cookie> cookies, String domainSuffix) {
        List<Cookie> filtered = new ArrayList<>();
        if (cookies == null || cookies.isEmpty()) {
            return filtered;
        }
        String suffix = domainSuffix == null ? "" : domainSuffix.toLowerCase(Locale.ROOT);
        for (Cookie cookie : cookies) {
            if (belongsToDomain(cookie, suffix)) {
                filtered.add(cookie);
            }
        }
        return filtered;
    }

    /** 单条 Cookie → Playwright Cookie；缺 name/value 返回 null。 */
    private static Cookie toCookie(JsonNode node) {
        if (node == null) {
            return null;
        }
        String name = node.path("name").asText("");
        String value = node.path("value").asText("");
        if (name.isBlank() || value.isBlank()) {
            return null;
        }
        Cookie cookie = new Cookie(name, value);
        if (node.hasNonNull("domain")) {
            cookie.domain = node.get("domain").asText();
        }
        if (node.hasNonNull("path")) {
            cookie.path = node.get("path").asText();
        }
        if (node.hasNonNull("expires")) {
            cookie.expires = node.get("expires").asDouble();
        }
        if (node.hasNonNull("httpOnly")) {
            cookie.httpOnly = node.get("httpOnly").asBoolean();
        }
        if (node.hasNonNull("secure")) {
            cookie.secure = node.get("secure").asBoolean();
        }
        applySameSite(cookie, node);
        return cookie;
    }

    /** SameSite 字段：未知枚举值忽略，保留 Cookie 其余属性。 */
    private static void applySameSite(Cookie cookie, JsonNode node) {
        if (!node.hasNonNull("sameSite")) {
            return;
        }
        String sameSite = node.get("sameSite").asText("").toUpperCase(Locale.ROOT);
        if (sameSite.isBlank()) {
            return;
        }
        try {
            cookie.sameSite = SameSiteAttribute.valueOf(sameSite);
        } catch (IllegalArgumentException unknownSameSite) {
            log.debug("忽略未知 SameSite: {}", sameSite);
        }
    }

    /** Cookie 的 domain 是否等于 suffix 或是它的子域。 */
    private static boolean belongsToDomain(Cookie cookie, String suffix) {
        if (cookie == null || cookie.domain == null || cookie.domain.isBlank()) {
            return false;
        }
        String domain = cookie.domain.toLowerCase(Locale.ROOT);
        return domain.equals(suffix) || domain.endsWith("." + suffix);
    }
}
