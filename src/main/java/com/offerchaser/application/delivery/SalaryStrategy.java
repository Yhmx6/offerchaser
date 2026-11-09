package com.offerchaser.application.delivery;

/**
 * 期望薪资过滤策略。
 * <ul>
 *   <li>{@link #INTERSECT}：岗位薪资区间与期望区间无交集才拒绝（默认，和旧 Boss 行为一致）；</li>
 *   <li>{@link #FLOOR}：只看下限，岗位薪资上限低于期望下限才拒绝（岗位开得高不拦）。</li>
 * </ul>
 */
public enum SalaryStrategy {
    INTERSECT,
    FLOOR
}
