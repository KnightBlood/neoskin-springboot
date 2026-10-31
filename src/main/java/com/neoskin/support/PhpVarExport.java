package com.neoskin.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PHP {@code var_export()} 的读写工具。
 *
 * <p>源项目的 {@code storage/options.php} 是 {@code php artisan options:cache} 生成的
 * PHP 文件，内容是 {@code return array ( 'k' => 'v', ... );}。Java 版必须能读懂它，
 * 否则一个已经跑过 {@code options:cache} 的站点迁移过来后，选项会全部退回默认值。
 *
 * <p>反过来，Java 版也要能写出同样格式，让 PHP 版仍可读取（双向兼容）。
 *
 * <p>只支持 var_export 会产生的类型：字符串、整数、浮点数、布尔、{@code NULL}、数组。
 * 这正是 options 缓存可能出现的全部内容。
 */
public final class PhpVarExport {

    private PhpVarExport() {
    }

    // ------------------------------------------------------------------ 读

    /** 解析 {@code var_export()} 输出中的数组字面量。 */
    public static Map<String, Object> parseArray(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        // 跳过 <?php / 注释 / return 等前缀，直接定位到 array
        int array = text.indexOf("array", parser.pos);
        if (array < 0) {
            throw new IllegalArgumentException("not a PHP var_export array");
        }
        parser.pos = array;
        Object value = parser.parseValue();
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("top-level value is not an array");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= text.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            char c = text.charAt(pos);
            if (c == '\'') {
                return parseString();
            }
            if (c == '"') {
                return parseDoubleQuoted();
            }
            if (text.startsWith("array", pos)) {
                return parseArray();
            }
            if (startsWithIgnoreCase("true")) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (startsWithIgnoreCase("false")) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (startsWithIgnoreCase("null")) {
                pos += 4;
                return null;
            }
            return parseNumber();
        }

        private boolean startsWithIgnoreCase(String word) {
            return text.regionMatches(true, pos, word, 0, word.length());
        }

        private Map<Object, Object> parseArray() {
            pos += "array".length();
            skipWhitespace();
            expect('(');
            Map<Object, Object> map = new LinkedHashMap<>();
            int autoKey = 0;
            while (true) {
                skipWhitespace();
                if (pos < text.length() && text.charAt(pos) == ')') {
                    pos++;
                    return map;
                }
                Object key = parseValue();
                skipWhitespace();
                expect('=');
                expect('>');
                Object value = parseValue();
                skipWhitespace();
                if (pos < text.length() && text.charAt(pos) == ',') {
                    pos++;
                }
                if (key == null) {
                    key = autoKey++;
                } else if (key instanceof Number n) {
                    autoKey = n.intValue() + 1;
                }
                map.put(key, value);
            }
        }

        private String parseString() {
            expect('\'');
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '\\') {
                    // PHP 单引号串只转义 \' 和 \\
                    sb.append(text.charAt(pos++));
                } else if (c == '\'') {
                    return sb.toString();
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private String parseDoubleQuoted() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '\\') {
                    sb.append(text.charAt(pos++));
                } else if (c == '"') {
                    return sb.toString();
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Object parseNumber() {
            int start = pos;
            if (pos < text.length() && (text.charAt(pos) == '-' || text.charAt(pos) == '+')) {
                pos++;
            }
            boolean floating = false;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (Character.isDigit(c)) {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E') {
                    floating = true;
                    pos++;
                } else if ((c == '-' || c == '+') && (text.charAt(pos - 1) == 'e' || text.charAt(pos - 1) == 'E')) {
                    pos++;
                } else {
                    break;
                }
            }
            String token = text.substring(start, pos);
            if (token.isEmpty() || token.equals("-") || token.equals("+")) {
                throw new IllegalArgumentException("not a number at offset " + start);
            }
            return floating ? (Object) Double.parseDouble(token) : (Object) Long.parseLong(token);
        }

        private void expect(char expected) {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != expected) {
                throw new IllegalArgumentException(
                        "expected '" + expected + "' at offset " + pos);
            }
            pos++;
        }
    }

    // ------------------------------------------------------------------ 写

    /**
     * 生成与 {@code options:cache} 完全一致的 PHP 文件内容。
     *
     * <p>源命令格式：
     * <pre>
     * &lt;?php
     * // This is auto-generated. DO NOT edit manually.
     * return array (
     *   'k' =&gt; 'v',
     * );
     * </pre>
     */
    public static String toPhpFile(Map<String, ?> items) {
        StringBuilder sb = new StringBuilder("<?php\n");
        sb.append("// This is auto-generated. DO NOT edit manually.\n");
        sb.append("return array (\n");
        items.forEach((key, value) -> sb.append("  ")
                .append(export(key))
                .append(" => ")
                .append(export(value))
                .append(",\n"));
        sb.append(");");
        return sb.toString();
    }

    /** 单值导出，与 PHP {@code var_export()} 的写法一致。 */
    public static String export(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof Number n) {
            return n.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("array (\n");
            map.forEach((k, v) -> sb.append("    ")
                    .append(export(k))
                    .append(" => ")
                    .append(export(v))
                    .append(",\n"));
            return sb.append("  )").toString();
        }
        return "'" + value.toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
