package com.offerchaser.application.delivery;

/**
 * 薪资解析结果。
 * <p>
 * 约定：
 * <ul>
 *   <li>{@code parsable}：是否解析成功。为 false 时其余数值字段均为 null，{@code errorMessage} 说明原因。</li>
 *   <li>{@code negotiable}：是否「面议」。面议视为解析成功，但没有数值区间。</li>
 *   <li>{@code minK}/{@code maxK}：统一折算后的<b>月薪 K</b>（元/月 ÷ 1000）。
 *       日薪按每月 21.75 个工作日折算，年薪按 12 个月折算。上限未知（如「20K以上」）时 {@code maxK} 为 null。</li>
 *   <li>{@code months}：薪资文本里显式给出的薪数（如「·14薪」为 14）；未给出时普通月薪/日薪/年薪按 12 处理，
 *       面议或解析失败为 null。该字段只用于展示/年包估算，不参与 minK/maxK 折算。</li>
 *   <li>{@code period}：计薪周期，取值见 {@link SalaryParser} 的 PERIOD_* 常量。</li>
 *   <li>{@code normalized}：可直接展示的规范化区间文本（如「12.5-18.5K·14薪」），解析失败为 null。</li>
 *   <li>{@code errorMessage}：解析失败原因；成功为 null。</li>
 * </ul>
 */
public record SalaryInfo(boolean parsable, boolean negotiable, Double minK, Double maxK,
                         Integer months, String period, String normalized, String errorMessage) {
}
