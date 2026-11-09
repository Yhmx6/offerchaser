package com.offerchaser.application.init;

import com.offerchaser.application.entity.CookieEntity;
import com.offerchaser.application.service.CookieService;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时为每个平台补一条 Cookie 种子记录，保证后续保存始终是 UPDATE 同一行。
 * <p>
 * 目前只有 BOSS 平台。单个平台失败只记 warn，不影响应用启动。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CookieSeedInitializer implements CommandLineRunner {

    /** 需要种子记录的平台标识（与控制器、PlaywrightManager 使用的平台名一致）。 */
    private static final List<String> SEED_PLATFORMS = List.of("boss");

    private final CookieService cookieService;

    @Override
    public void run(String... args) {
        for (String platform : SEED_PLATFORMS) {
            try {
                seedCookieRow(platform);
            } catch (Exception seedFailure) {
                log.warn("为平台 {} 创建 Cookie 种子记录时发生异常: {}", platform, seedFailure.getMessage());
            }
        }
    }

    /** 平台没有 Cookie 行时插入一条 seed 占位记录。 */
    private void seedCookieRow(String platform) {
        CookieEntity existing = cookieService.getCookieByPlatform(platform);
        if (existing != null) {
            log.info("平台 {} 已存在 Cookie 记录 (id={}), 跳过种子创建", platform, existing.getId());
            return;
        }
        if (cookieService.saveOrUpdateCookie(platform, "", "seed")) {
            log.info("已为平台 {} 创建 Cookie 种子记录", platform);
        } else {
            log.warn("为平台 {} 创建 Cookie 种子记录失败（返回 false）", platform);
        }
    }
}
