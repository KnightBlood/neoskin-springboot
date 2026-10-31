package com.neoskin.support;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * PHP / Laravel 的时间格式工具。
 *
 * <p>源项目所有 {@code timestamp} / {@code dateTime} 列都用 Laravel 的
 * {@code Y-m-d H:i:s} 文本格式存储（{@code Model::$dateFormat} 的默认值），并且
 * {@code now()} 用的是 {@code config('app.timezone')}（源项目固定 {@code Asia/Shanghai}）。
 *
 * <p>因此 Java 版写库时必须：
 * <ol>
 *   <li>用应用时区而不是 JVM 默认时区（否则容器里 UTC 会差 8 小时）；</li>
 *   <li>用 {@code yyyy-MM-dd HH:mm:ss} 而不是 ISO-8601（否则 PHP 版读不了，
 *       而且字符串排序会错）。</li>
 * </ol>
 */
public final class PhpDate {

    /** Laravel 的默认 {@code $dateFormat}。 */
    public static final String FORMAT = "yyyy-MM-dd HH:mm:ss";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern(FORMAT);

    private PhpDate() {
    }

    /** 按应用时区取当前时间，格式化为 {@code Y-m-d H:i:s}。 */
    public static String now(String timezone) {
        return at(Instant.now(), timezone);
    }

    /** 把某个时刻按应用时区格式化为 {@code Y-m-d H:i:s}。 */
    public static String at(Instant instant, String timezone) {
        return LocalDateTime.ofInstant(instant, zone(timezone)).format(FORMATTER);
    }

    /** 把毫秒时间戳按应用时区格式化。 */
    public static String at(long epochMillis, String timezone) {
        return at(Instant.ofEpochMilli(epochMillis), timezone);
    }

    /** 解析 {@code Y-m-d H:i:s}；无法解析时返回 {@code null}。 */
    public static LocalDateTime parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim(), FORMATTER);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 取某时区的年份（对应 {@code Carbon::now()->year}）。 */
    public static int currentYear(String timezone) {
        return LocalDateTime.now(zone(timezone)).getYear();
    }

    /** 时区解析，非法值回退到 JVM 默认时区而不是抛异常。 */
    public static ZoneId zone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }
}
