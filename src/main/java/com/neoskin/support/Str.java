package com.neoskin.support;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Laravel {@code Illuminate\Support\Str} 里被中间件用到的那几个方法。
 *
 * <p>与 {@link PhpString#trim(String)}（PHP 原生 {@code trim}）<b>不是一回事</b>：
 * Laravel 的 {@code Str::trim()} 会把「不可见字符」一起裁掉，字符集是
 * {@code INVISIBLE_CHARACTERS} 常量 + {@code " \n\r\t\v\0"}，并额外吃
 * {@code \s}（在 {@code /u} 下的 Unicode 空白）。语料外的这层差异直接决定
 * 「密码前后带 NBSP 时是否被裁」这类行为，必须逐字对齐。</p>
 *
 * <p>本类只收中间件链路（{@code TrimStrings} / {@code ConvertEmptyStringsToNull} /
 * {@code CheckRole}）实际要用的方法，不做全集移植。</p>
 */
public final class Str {

    /**
     * {@code Str::INVISIBLE_CHARACTERS}（原样逐字抄，含补充平面）。
     */
    private static final String INVISIBLE = "\u0009\u0020\u00A0\u00AD\u034F\u061C"
            + "\u115F\u1160\u17B4\u17B5\u180E\u2000\u2001\u2002\u2003\u2004\u2005"
            + "\u2006\u2007\u2008\u2009\u200A\u200B\u200C\u200D\u200E\u200F\u202F"
            + "\u205F\u2060\u2061\u2062\u2063\u2064\u2065\u206A\u206B\u206C\u206D"
            + "\u206E\u206F\u3000\u2800\u3164\uFEFF\uFFA0\uD834\uDD59"
            + "\uD834\uDD73\uD834\uDD74\uD834\uDD75\uD834\uDD76\uD834\uDD77"
            + "\uD834\uDD78\uD834\uDD79\uD834\uDD7A\uDB40\uDC20";

    /** 是否「可裁字符」的判定（按码点，含代理对）。 */
    private static final Pattern TRIM_CHARS = buildTrimPattern();

    private Str() {
    }

    private static Pattern buildTrimPattern() {
        // PHP 的 /u 模式下 \s 覆盖 Unicode 空白（不只是 ASCII），所以这里用
        // \p{IsWhite_Space}（等价于 PCRE 的 \s 在 /u 下的集合）。
        // 另外补 PHP 默认 trim 字符集里的 NUL（\0）——\s 不含它。
        StringBuilder sb = new StringBuilder("[\\p{IsWhite_Space}\\x{0000}");

        for (int i = 0; i < INVISIBLE.length(); ) {
            int cp = INVISIBLE.codePointAt(i);
            sb.append(String.format("\\x{%X}", cp));
            i += Character.charCount(cp);
        }

        sb.append("]");

        return Pattern.compile(sb.toString());
    }

    /**
     * {@code Str::trim($value)}：裁掉首尾的空白 + 不可见字符。
     *
     * <p>实现按<b>码点</b>走（不是 UTF-16 码元）：补充平面的不可见字符
     * （U+1D159 等）不能只裁半个代理对。</p>
     */
    public static String trim(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        int start = 0;
        int end = value.length();

        while (start < end) {
            int cp = value.codePointAt(start);

            if (!isTrimChar(cp)) {
                break;
            }

            start += Character.charCount(cp);
        }

        while (end > start) {
            int cp = value.codePointBefore(end);

            if (!isTrimChar(cp)) {
                break;
            }

            end -= Character.charCount(cp);
        }

        return value.substring(start, end);
    }

    private static boolean isTrimChar(int cp) {
        return TRIM_CHARS.matcher(new String(Character.toChars(cp))).matches();
    }

    /**
     * {@code Str::is($patterns, $value)}：支持 {@code *} 通配、大小写敏感。
     *
     * <p>用作 {@code TrimStrings} 的 {@code shouldSkip}（{@code Str::is($except, $key)}）
     * 与 {@code CheckRole} 的路径匹配。</p>
     */
    public static boolean is(String pattern, String value) {
        return is(List.of(pattern), value);
    }

    /** 多模式版本：任一命中即 true（{@code $pattern === '*'} 恒真）。 */
    public static boolean is(List<String> patterns, String value) {
        String target = value == null ? "" : value;

        for (String pattern : patterns) {
            String p = pattern == null ? "" : pattern;

            if ("*".equals(p) || p.equals(target)) {
                return true;
            }

            if (wildcardMatches(p, target)) {
                return true;
            }
        }

        return false;
    }

    /** {@code '*'} → {@code '.*'} 的整串匹配（PHP 的 {@code #^...\z#su}）。 */
    private static boolean wildcardMatches(String pattern, String value) {
        StringBuilder regex = new StringBuilder();

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);

            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }

        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(value).matches();
    }
}
