package com.offerchaser.application.onboarding;

/**
 * AI 建议的薪资区间（单位 K/月）。
 *
 * @param label      区间名称（如“可冲刺”“稳妥区间”）
 * @param min        下限 K/月
 * @param max        上限 K/月
 * @param basis      依据说明
 * @param confidence 置信度 0~1（未知为 null）
 */
public record ResumeSalaryBand(String label, Integer min, Integer max,
                               String basis, Double confidence) {
}
