package com.offerchaser.application.persistence;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 持久化层通用工具：统一时间格式、时区解析与证据文本拼接。
 * <p>
 * 约定：数据库中的时间字段统一存本地时区的 {@code yyyy-MM-dd HH:mm:ss} 字符串，
 * 因此可以直接用字符串比较判断时间先后。
 */
final class PersistenceSupport {

    static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    static final String DATE_PATTERN = "yyyy-MM-dd";

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(DATE_PATTERN);

    private PersistenceSupport() {
    }

    /** 当前时间（本地时区的统一格式字符串）。 */
    static String now() {
        return LocalDateTime.now().format(DATE_TIME_FORMATTER);
    }

    /** 把 LocalDateTime 格式化为数据库统一格式；入参为 null 时返回 null。 */
    static String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME_FORMATTER);
    }

    /** 解析数据库时间字符串；为空或格式非法时返回 null，不抛异常。 */
    static LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim(), DATE_TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 解析配置时区；非法配置回退系统默认时区，避免因配置错误导致服务不可用。 */
    static ZoneId zone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    /** 配置时区下的今天，格式 yyyy-MM-dd（用于每日预算的 budget_date）。 */
    static String today(ZoneId zone) {
        return LocalDate.now(zone).format(DATE_FORMATTER);
    }

    /** 配置时区下今日 00:00 对应的字符串，用于与 started_at / created_at 做字符串比较。 */
    static String todayStart(ZoneId zone) {
        return LocalDate.now(zone).atStartOfDay().format(DATE_TIME_FORMATTER);
    }

    /** 组合证据等级与证据细节，两者都可能为空。 */
    static String evidence(String evidenceLevel, String detail) {
        String level = trimToNull(evidenceLevel);
        String value = trimToNull(detail);
        if (level == null) {
            return value;
        }
        if (value == null) {
            return level;
        }
        return level + ":" + value;
    }

    /** 去首尾空白；空字符串视为 null。 */
    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 配置值兜底：空白时返回 fallback。 */
    static String defaultIfBlank(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }
}
