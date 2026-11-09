package com.offerchaser.worker.utils;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * BOSS 搜索 URL 参数拼接与耗时格式化等纯工具方法。
 * <p>
 * 筛选参数约定：值等于 {@link Constant#UNLIMITED_CODE}（"0"）表示「不限」，不写进 URL。
 * 配置读取与定时任务类旧工具已移除，配置统一由各平台服务从数据库构建。
 */
public class JobUtils {

    private static final String PARAM_PREFIX = "&";
    private static final String PARAM_ASSIGN = "=";
    private static final String DURATION_FORMAT = "%d时%d分%d秒";
    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long SECONDS_PER_HOUR = 3600L;

    /**
     * 拼接单个查询参数。
     *
     * @return 形如 {@code &name=value}；空值或「不限」返回空字符串
     */
    public static String appendParam(String name, String value) {
        if (value == null || value.isEmpty() || Objects.equals(Constant.UNLIMITED_CODE, value)) {
            return "";
        }
        return PARAM_PREFIX + name + PARAM_ASSIGN + value;
    }

    /**
     * 拼接多选查询参数（逗号分隔）。
     *
     * @return 形如 {@code &name=a,b}；空列表或列表里含「不限」时整体跳过
     */
    public static String appendListParam(String name, List<String> values) {
        if (values == null || values.isEmpty() || containsUnlimited(values)) {
            return "";
        }
        return PARAM_PREFIX + name + PARAM_ASSIGN + String.join(",", values);
    }

    /**
     * 两个时刻之间的耗时，格式为「X时Y分Z秒」；不足一秒的余数直接丢弃（与旧实现一致）。
     */
    public static String formatDuration(Date startDate, Date endDate) {
        long elapsedMillis = endDate.getTime() - startDate.getTime();
        return formatDuration(elapsedMillis / 1000L);
    }

    /**
     * 把秒数格式化为「X时Y分Z秒」；小时不做 24 小时截断，负数沿用 Java 取余语义。
     */
    public static String formatDuration(long durationSeconds) {
        long hours = durationSeconds / SECONDS_PER_HOUR;
        long minutes = (durationSeconds / SECONDS_PER_MINUTE) % SECONDS_PER_MINUTE;
        long seconds = durationSeconds % SECONDS_PER_MINUTE;
        return String.format(DURATION_FORMAT, hours, minutes, seconds);
    }

    /**
     * 生成 [min, max] 闭区间内的随机整数。
     *
     * @throws IllegalArgumentException min 大于 max 时
     */
    public static int getRandomNumberInRange(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("max must be greater than or equal to min");
        }
        int candidateCount = (max - min) + 1;
        return ThreadLocalRandom.current().nextInt(candidateCount) + min;
    }

    /** 列表是否包含「不限」占位值；null 元素不参与。 */
    private static boolean containsUnlimited(List<String> values) {
        for (String value : values) {
            if (Objects.equals(Constant.UNLIMITED_CODE, value)) {
                return true;
            }
        }
        return false;
    }

    /** 手工验证入口：等待 3 秒并打印耗时。 */
    public static void main(String[] args) {
        long startedAt = System.currentTimeMillis();
        PlaywrightUtil.sleep(3);
        System.out.println(formatDuration(new Date(startedAt), new Date()));
    }
}
