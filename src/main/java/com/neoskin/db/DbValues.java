package com.neoskin.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * JDBC 返回值 → PHP 友好值的归一化。
 *
 * <p>PHP/PDO 读取 {@code TIMESTAMP}/{@code DATETIME} 时统一得到
 * {@code Y-m-d H:i:s} 文本；JDBC 驱动则可能返回
 * {@code 2026-11-05 14:00:00.0}、ISO 串或带时区的字符串。
 * 这里统一裁成 19 个字符的 PHP 格式。使用 {@link ResultSet#getString(int)}
 * 而不是 {@code getObject}，避免 JVM 默认时区把数据库 wall-clock 时间平移。</p>
 */
public final class DbValues {

    private DbValues() {
    }

    /** 读取时间列并归一化为 {@code Y-m-d H:i:s}（空值返回 {@code null}）。 */
    public static String dateTime(ResultSet rs, String column) throws SQLException {
        return normalizeText(rs.getString(column));
    }

    /** 列名是否是时间列（{@code *_at} / {@code last_modified}）。 */
    public static boolean isDateColumn(String column) {
        String name = column == null ? "" : column.toLowerCase(Locale.ROOT);
        return name.endsWith("_at") || "last_modified".equals(name);
    }

    private static String normalizeText(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        // SQL Server 可能返回 `2026-11-05 14:00:00.0000000`；ISO 串则换成空格。
        if (trimmed.length() > 10 && trimmed.charAt(10) == 'T') {
            trimmed = trimmed.substring(0, 10) + ' ' + trimmed.substring(11);
        }
        if (trimmed.length() > 19 && trimmed.charAt(10) == ' ') {
            return trimmed.substring(0, 19);
        }
        return trimmed;
    }
}
