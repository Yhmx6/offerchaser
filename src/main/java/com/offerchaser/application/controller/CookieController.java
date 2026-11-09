package com.offerchaser.application.controller;

import com.offerchaser.application.entity.CookieEntity;
import com.offerchaser.application.service.CookieService;
import com.offerchaser.worker.manager.PlaywrightManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BOSS Cookie 读/写接口（{@code /api/cookie}）。
 * <p>
 * 项目只剩 BOSS 一条链路：Cookie 读写固定作用在 {@code boss} 上，不再做多平台白名单校验。
 * {@code platform} 查询参数仅为兼容旧调用方保留（前端 dev 页面/脚本可能仍带），
 * 传入其他值也按 boss 处理并记一条 debug 日志。
 * <ul>
 *   <li>{@code GET /api/cookie?platform=boss}：读取数据库里的 BOSS Cookie 记录；</li>
 *   <li>{@code POST /api/cookie/save?platform=boss&remark=...}：把当前浏览器上下文 Cookie 落库。</li>
 * </ul>
 * 本接口任意方法都要求本机会话认证（{@code AuthInterceptor} 对 /api/cookie 前缀统一处理）。
 */
@Slf4j
@RestController
@RequestMapping("/api/cookie")
public class CookieController {

    /** 本项目唯一平台。 */
    private static final String PLATFORM = "boss";
    /** 默认备注（与旧接口默认值一致）。 */
    private static final String DEFAULT_REMARK = "manual save";

    private final CookieService cookieService;
    private final PlaywrightManager playwrightManager;

    public CookieController(CookieService cookieService, PlaywrightManager playwrightManager) {
        this.cookieService = cookieService;
        this.playwrightManager = playwrightManager;
    }

    /**
     * 读取 BOSS Cookie 记录。
     *
     * @param platform 兼容参数：仅 boss 有意义，其他值忽略
     * @return {success:true, data:{id,platform,cookie_value,remark,created_at,updated_at}}
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getCookie(@RequestParam("platform") String platform) {
        noteLegacyPlatform(platform);
        try {
            CookieEntity cookie = cookieService.getCookieByPlatform(PLATFORM);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("data", describeCookie(cookie));
            return ResponseEntity.ok(response);
        } catch (Exception readError) {
            log.error("读取Cookie记录失败", readError);
            return ResponseEntity.internalServerError()
                    .body(failure("读取Cookie记录失败: " + readError.getMessage()));
        }
    }

    /**
     * 把当前浏览器上下文里的 BOSS Cookie 主动保存到数据库。
     *
     * @param platform 兼容参数：仅 boss 有意义，其他值忽略
     * @param remark   备注
     * @return {success:true, message}
     */
    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> saveCookie(
            @RequestParam("platform") String platform,
            @RequestParam(value = "remark", defaultValue = DEFAULT_REMARK) String remark) {
        noteLegacyPlatform(platform);
        try {
            playwrightManager.saveCookiesToDb(PLATFORM, remark);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "已主动保存 " + PLATFORM + " Cookie 到数据库");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException invalidPlatform) {
            // PlaywrightManager 仍会对非 boss 平台抛该异常（保留给内部调用方）
            return ResponseEntity.badRequest().body(failure(invalidPlatform.getMessage()));
        } catch (Exception saveError) {
            log.error("保存Cookie失败", saveError);
            return ResponseEntity.internalServerError()
                    .body(failure("保存Cookie失败: " + saveError.getMessage()));
        }
    }

    /** 把 Cookie 实体整理成前端约定的字段名；没有记录时返回 null 值 + 说明。 */
    private Map<String, Object> describeCookie(CookieEntity cookie) {
        if (cookie == null) {
            return buildMissingCookiePayload();
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", cookie.getId());
        fields.put("platform", cookie.getPlatform());
        fields.put("cookie_value", cookie.getCookieValue());
        fields.put("remark", cookie.getRemark());
        fields.put("created_at", cookie.getCreatedAt());
        fields.put("updated_at", cookie.getUpdatedAt());
        return fields;
    }

    /** 数据库里还没有 BOSS Cookie 时的占位结构（cookie_value 必须是 null）。 */
    private Map<String, Object> buildMissingCookiePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("platform", PLATFORM);
        payload.put("cookie_value", null);
        payload.put("message", "未找到Cookie记录");
        return payload;
    }

    /** 旧调用方可能还带其他平台名；记录但不拒绝（当前项目只有 boss）。 */
    private void noteLegacyPlatform(String platform) {
        if (platform != null && !PLATFORM.equals(platform)) {
            log.debug("忽略旧平台参数 platform={}，Cookie 读写固定按 boss 处理", platform);
        }
    }

    /** {success:false, message} */
    private Map<String, Object> failure(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }
}
