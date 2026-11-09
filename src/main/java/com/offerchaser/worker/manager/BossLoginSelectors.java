package com.offerchaser.worker.manager;

import java.util.List;

/**
 * BOSS 登录相关 DOM 选择器。
 * <p>
 * 集中放在这里，PlaywrightManager 只保留“怎么判定”，不再散落选择器字符串；
 * 后续 BOSS 改版时改这一处即可。
 */
final class BossLoginSelectors {

    private BossLoginSelectors() {
    }

    /** 已登录用户区候选：任意一个可见即可作为“已登录”证据。 */
    static final List<String> USER_AREA_CANDIDATES = List.of(
            "li.nav-figure span.label-text",
            "li.nav-figure");

    /** 页面导航区的登录入口（可见且含“登录”即认为未登录）。 */
    static final String LOGIN_ENTRY = "li.nav-sign a, .btns";

    /** 登录页“切换 App 扫码登录”的候选入口。 */
    static final List<String> SCAN_MODE_SWITCHES = List.of(
            ".btn-sign-switch.ewm-switch",
            "text=APP扫码登录",
            "li.sign-switch-tip");
}
