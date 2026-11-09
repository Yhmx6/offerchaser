package com.offerchaser.application.delivery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 薪资文本解析器：把 BOSS 直聘页面上各种写法的薪资统一折算成「月薪 K」。
 * <p>
 * 支持（含全角字符）：
 * <ul>
 *   <li>{@code 20-40K}、{@code 12.5K}、{@code 12.5-18.5K·14薪}、{@code 20K以上}；</li>
 *   <li>{@code 1.5-2.5万}（月薪）、{@code 20-40万/年}（年薪，按 12 个月折算）；</li>
 *   <li>{@code 300-500元/天}（日薪，按每月 21.75 个工作日折算）；</li>
 *   <li>{@code 面议}（解析成功，但无数值区间）。</li>
 * </ul>
 * 折算口径：{@code minK}/{@code maxK} = 月薪（元）÷ 1000；单一数值视为固定薪资（maxK = minK），
 * 「以上/起」这类只有下限的写法 maxK 为 null。非法格式返回 {@code parsable=false}。
 * <p>
 * 旧实现 {@code Boss.parseSalary/BossService.parseSalary} 用「去掉非数字字符」的方式兜底解析，
 * 会把 {@code 12.5K} 变成 {@code 125K}；本实现全程按正则匹配带小数的数字，不存在该问题。
 */
public final class SalaryParser {

    /** 计薪周期：月薪。 */
    public static final String PERIOD_MONTH = "MONTH";
    /** 计薪周期：日薪。 */
    public static final String PERIOD_DAY = "DAY";
    /** 计薪周期：年薪。 */
    public static final String PERIOD_YEAR = "YEAR";
    /** 计薪周期：面议。 */
    public static final String PERIOD_NEGOTIABLE = "NEGOTIABLE";
    /** 计薪周期：无法识别。 */
    public static final String PERIOD_UNKNOWN = "UNKNOWN";

    /** 日薪折算月薪使用的月均工作日（与旧 Boss 实现口径一致）。 */
    private static final BigDecimal WORK_DAYS_PER_MONTH = new BigDecimal("21.75");
    private static final BigDecimal TEN = BigDecimal.TEN;
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private static final BigDecimal TWELVE_MONTHS = new BigDecimal("12");
    /** 换算过程中的中间精度：保留 10 位小数，最终以 double 呈现，避免过早四舍五入。 */
    private static final int DIVIDE_SCALE = 10;
    /** 没有显式薪数时默认按 12 薪展示/估算。 */
    private static final int DEFAULT_MONTHS = 12;

    /** 数字：整数或小数。 */
    private static final String NUM = "(\\d+(?:\\.\\d+)?)";

    /** 薪数后缀，如「·14薪」「•13薪」；匹配后需要从原文中移除再解析区间。 */
    private static final Pattern MONTHS_PATTERN = Pattern.compile("(?:[·•・･])?(\\d{1,2})薪");
    /** 日薪：300-500元/天、300元/天。 */
    private static final Pattern DAY_PATTERN = Pattern.compile("^" + NUM + "(?:-" + NUM + ")?元/(?:天|日)$");
    /** 年薪：20-40万/年、25万每年。 */
    private static final Pattern YEAR_WAN_PATTERN = Pattern.compile("^" + NUM + "(?:-" + NUM + ")?万(?:/年|每年)$");
    /** 月薪（万）：1.5-2.5万、1.5-2.5万/月。 */
    private static final Pattern MONTH_WAN_PATTERN = Pattern.compile("^" + NUM + "(?:-" + NUM + ")?万(?:/月|每月)?$");
    /** 月薪（K）：20-40K、12.5K、20-40K/月、20K以上、20K起。 */
    private static final Pattern MONTH_K_PATTERN =
            Pattern.compile("^" + NUM + "(?:-" + NUM + ")?[Kk](?:/月|每月)?(以上|起)?$");
    /** 月薪（元）：20000元/月、20000元。 */
    private static final Pattern MONTH_YUAN_PATTERN = Pattern.compile("^" + NUM + "(?:-" + NUM + ")?元(?:/月|每月)?$");

    /** 各种连接符统一成半角减号（NFKC 后仍可能残留的波浪线等）。 */
    private static final char[] DASH_VARIANTS = {'–', '—', '−', '~', '～', '〜', '﹣'};

    private SalaryParser() {
        // 纯静态工具类
    }

    /**
     * 解析薪资文本。
     *
     * @param raw 页面/详情接口返回的原始薪资文本，可为 null
     * @return 永远非 null；无法解析时 {@code parsable=false}
     */
    public static SalaryInfo parse(String raw) {
        if (raw == null) {
            return unparsable("薪资文本为空");
        }
        String text = normalize(raw);
        if (text.isEmpty()) {
            return unparsable("薪资文本为空");
        }
        // 面议：解析成功但无数值区间，由调用方决定要不要投递
        if (text.contains("面议")) {
            return new SalaryInfo(true, true, null, null, null, PERIOD_NEGOTIABLE, "面议", null);
        }

        // 先摘出薪数后缀（·14薪），剩下的部分才是薪资区间
        Integer explicitMonths = null;
        Matcher monthsMatcher = MONTHS_PATTERN.matcher(text);
        if (monthsMatcher.find()) {
            try {
                int parsedMonths = Integer.parseInt(monthsMatcher.group(1));
                if (parsedMonths >= 1 && parsedMonths <= 36) {
                    explicitMonths = parsedMonths;
                }
            } catch (NumberFormatException ignored) {
                // 两位数以内理论上不会失败，兜底按未配置处理
            }
            text = text.substring(0, monthsMatcher.start()) + text.substring(monthsMatcher.end());
        }
        if (text.isEmpty()) {
            return unparsable("薪资文本为空");
        }

        Matcher matcher = DAY_PATTERN.matcher(text);
        if (matcher.matches()) {
            BigDecimal minDay = new BigDecimal(matcher.group(1));
            BigDecimal maxDay = matcher.group(2) != null ? new BigDecimal(matcher.group(2)) : null;
            double minK = dayToMonthlyK(minDay);
            Double maxK = maxDay == null ? Double.valueOf(minK) : Double.valueOf(dayToMonthlyK(maxDay));
            String normalized = formatNumber(minDay)
                    + (maxDay != null ? "-" + formatNumber(maxDay) : "")
                    + "元/天";
            return parsed(minK, maxK, explicitMonths, PERIOD_DAY, normalized);
        }

        matcher = YEAR_WAN_PATTERN.matcher(text);
        if (matcher.matches()) {
            BigDecimal minWan = new BigDecimal(matcher.group(1));
            BigDecimal maxWan = matcher.group(2) != null ? new BigDecimal(matcher.group(2)) : null;
            double minK = annualWanToMonthlyK(minWan);
            Double maxK = maxWan == null ? Double.valueOf(minK) : Double.valueOf(annualWanToMonthlyK(maxWan));
            String normalized = formatNumber(minWan)
                    + (maxWan != null ? "-" + formatNumber(maxWan) : "")
                    + "万/年";
            return parsed(minK, maxK, explicitMonths, PERIOD_YEAR, normalized);
        }

        matcher = MONTH_WAN_PATTERN.matcher(text);
        if (matcher.matches()) {
            BigDecimal minWan = new BigDecimal(matcher.group(1));
            BigDecimal maxWan = matcher.group(2) != null ? new BigDecimal(matcher.group(2)) : null;
            double minK = minWan.multiply(TEN).doubleValue();
            Double maxK = maxWan == null ? Double.valueOf(minK) : Double.valueOf(maxWan.multiply(TEN).doubleValue());
            // 统一折算成 K 展示，便于和「20-40K」口径对比
            String normalized = formatNumber(minWan.multiply(TEN))
                    + (maxWan != null ? "-" + formatNumber(maxWan.multiply(TEN)) : "")
                    + "K" + monthsSuffix(explicitMonths);
            return parsed(minK, maxK, explicitMonths, PERIOD_MONTH, normalized);
        }

        matcher = MONTH_K_PATTERN.matcher(text);
        if (matcher.matches()) {
            BigDecimal minKValue = new BigDecimal(matcher.group(1));
            BigDecimal maxKValue = matcher.group(2) != null ? new BigDecimal(matcher.group(2)) : null;
            // group(3)：以上/起 表示只有下限
            String openEndedSuffix = matcher.group(3);
            double minK = minKValue.doubleValue();
            Double maxK;
            if (maxKValue != null) {
                maxK = maxKValue.doubleValue();
            } else if (openEndedSuffix != null) {
                maxK = null;
            } else {
                // 单一数值（如 12.5K）视为固定薪资
                maxK = minK;
            }
            String normalized = formatNumber(minKValue)
                    + (maxKValue != null ? "-" + formatNumber(maxKValue) : "")
                    + "K" + (openEndedSuffix != null ? openEndedSuffix : "") + monthsSuffix(explicitMonths);
            return parsed(minK, maxK, explicitMonths, PERIOD_MONTH, normalized);
        }

        matcher = MONTH_YUAN_PATTERN.matcher(text);
        if (matcher.matches()) {
            BigDecimal minYuan = new BigDecimal(matcher.group(1));
            BigDecimal maxYuan = matcher.group(2) != null ? new BigDecimal(matcher.group(2)) : null;
            double minK = yuanToMonthlyK(minYuan);
            Double maxK = maxYuan == null ? Double.valueOf(minK) : Double.valueOf(yuanToMonthlyK(maxYuan));
            String normalized = formatNumber(minYuan.divide(THOUSAND, DIVIDE_SCALE, RoundingMode.HALF_UP))
                    + (maxYuan != null
                    ? "-" + formatNumber(maxYuan.divide(THOUSAND, DIVIDE_SCALE, RoundingMode.HALF_UP)) : "")
                    + "K" + monthsSuffix(explicitMonths);
            return parsed(minK, maxK, explicitMonths, PERIOD_MONTH, normalized);
        }

        return unparsable("无法识别的薪资格式：" + raw.trim());
    }

    /**
     * 文本归一化：全角转半角（NFKC）→ 连接符统一 → 去空白 → 两端带单位的区间统一成「只保留末尾单位」。
     */
    private static String normalize(String raw) {
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        for (char dash : DASH_VARIANTS) {
            text = text.replace(dash, '-');
        }
        // 去掉所有空白，BOSS 偶尔会把「20 - 40K」拆开写
        text = text.replaceAll("\\s+", "");
        // 「12.5K-18.5K」「1.5万-2.5万」统一成「12.5-18.5K」「1.5-2.5万」
        text = text.replaceAll("^(\\d+(?:\\.\\d+)?)[Kk]-(?=\\d)", "$1-");
        text = text.replaceAll("^(\\d+(?:\\.\\d+)?)万-(?=\\d)", "$1-");
        return text;
    }

    /** 日薪（元/天）折算为月薪 K：日薪 × 21.75 ÷ 1000。 */
    private static double dayToMonthlyK(BigDecimal dayYuan) {
        return dayYuan.multiply(WORK_DAYS_PER_MONTH)
                .divide(THOUSAND, DIVIDE_SCALE, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** 年薪（万/年）折算为月薪 K：多少万 × 10000 ÷ 12 ÷ 1000。 */
    private static double annualWanToMonthlyK(BigDecimal wan) {
        return wan.multiply(TEN_THOUSAND)
                .divide(TWELVE_MONTHS, DIVIDE_SCALE, RoundingMode.HALF_UP)
                .divide(THOUSAND, DIVIDE_SCALE, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** 月薪（元/月）折算为 K：元 ÷ 1000。 */
    private static double yuanToMonthlyK(BigDecimal yuan) {
        return yuan.divide(THOUSAND, DIVIDE_SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    private static SalaryInfo parsed(double minK, Double maxK, Integer explicitMonths, String period,
                                    String normalized) {
        int months = explicitMonths != null ? explicitMonths : DEFAULT_MONTHS;
        return new SalaryInfo(true, false, minK, maxK, months, period, normalized, null);
    }

    private static SalaryInfo unparsable(String errorMessage) {
        return new SalaryInfo(false, false, null, null, null, PERIOD_UNKNOWN, null, errorMessage);
    }

    private static String monthsSuffix(Integer explicitMonths) {
        return explicitMonths == null ? "" : "·" + explicitMonths + "薪";
    }

    /** 数字展示：去掉无意义的小数位与科学计数法（20.0 → 20，12.50 → 12.5）。 */
    private static String formatNumber(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }
}
