package com.offerchaser.application.delivery;

/**
 * 黑名单匹配模式（阶段 B B2 可配置）。
 * <ul>
 *   <li>{@link #CONTAINS}：包含匹配（默认，和旧版黑名单行为一致）；</li>
 *   <li>{@link #EXACT}：去首尾空白后完全相等（适合「公司全名」这类精确名单）。</li>
 * </ul>
 * 白名单不受该模式影响，始终按「包含」匹配（见 {@link BlacklistPolicy#whitelist()}）。
 */
public enum BlacklistMatchMode {
    CONTAINS,
    EXACT
}
