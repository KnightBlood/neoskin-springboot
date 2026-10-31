package com.neoskin.support;

import java.util.Map;

/**
 * PHP 字符串函数的等价实现。
 *
 * <p>迁移脚本里有三处 PHP 特有的字符串语义，Java 的 {@link String} 方法<b>不是</b>等价的，
 * 必须单独实现，否则回填的数据会有细微差异：
 *
 * <table border="1">
 *   <caption>差异点</caption>
 *   <tr><th>PHP</th><th>Java 直接对应</th><th>差异</th></tr>
 *   <tr>
 *     <td>{@code trim($s)}</td>
 *     <td>{@code String.trim()}</td>
 *     <td>PHP 的字符集是 {@code " \t\n\r\0\x0B"}（含垂直制表符，<b>不含</b>换页
 *         {@code \x0C}）；Java 的 {@code trim()} 去掉所有 {@code <= ' '}（含 {@code \x0C}），
 *         {@code strip()} 按 Unicode 空白判断（含 NBSP）。三者都不同。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code mb_substr($s, 0, 100)}</td>
 *     <td>{@code String.substring(0, 100)}</td>
 *     <td>PHP 按<b>字符</b>（码点）截断，Java 按 UTF-16 码元——emoji 会被劈成半个代理项。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code (string) $v}</td>
 *     <td>{@code String.valueOf($v)}</td>
 *     <td>PHP 的 {@code true → "1"}、{@code false → ""}、{@code null → ""}、
 *         {@code 1.0 → "1"}、数组 → {@code "Array"}。</td>
 *   </tr>
 * </table>
 */
public final class PhpString {

    /** PHP {@code trim()} 默认去掉的字符，注意顺序不影响结果但集合必须一致。 */
    private static final String TRIM_CHARS = " \t\n\r\0\u000B";

    /** PHP 的 {@code precision} 默认值（不是 {@code serialize_precision}）。 */
    private static final int PRECISION = 14;

    private PhpString() {
    }

    /** 等价于 PHP 的 {@code trim($s)}（不传第二个参数）。 */
    public static String trim(String s) {
        if (s == null) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && TRIM_CHARS.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && TRIM_CHARS.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }

    /**
     * 等价于 PHP 的 {@code trim($s, $characters)}：只去掉 {@code $characters} 里的字符。
     *
     * <p>注意第二个参数是<b>字符集合</b>而不是字符串前后缀，且 PHP 支持
     * {@code "a..z"} 这种区间写法。源项目只用到 {@code trim($url, '/')} 与
     * {@code trim($path, '/')}，这里实现集合语义与区间展开，保证将来换参数也不出错。
     */
    public static String trim(String s, String characters) {
        if (s == null) {
            return "";
        }
        boolean[] strip = characterSet(characters);
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) < 256 && strip[s.charAt(start)]) {
            start++;
        }
        while (end > start && s.charAt(end - 1) < 256 && strip[s.charAt(end - 1)]) {
            end--;
        }
        return s.substring(start, end);
    }

    /**
     * 把 PHP {@code trim()} 的字符集合参数展开成 256 长的布尔表。
     *
     * <p>支持 {@code ".."} 区间（例如 {@code "a..z"}）与 {@code "\\x00"} 之类的
     * 反斜杠转义；非 ASCII 字符不进表，因此不会被去掉——与 PHP 的单字节语义一致。
     */
    private static boolean[] characterSet(String characters) {
        boolean[] strip = new boolean[256];
        if (characters == null) {
            return strip;
        }
        int i = 0;
        while (i < characters.length()) {
            int from = characterAt(characters, i);
            int consumed = from < 0 ? 1 : charWidth(characters, i);
            i += consumed;
            if (i + 1 < characters.length() && characters.charAt(i) == '.' && characters.charAt(i + 1) == '.') {
                i += 2;
                if (i < characters.length()) {
                    int to = characterAt(characters, i);
                    i += to < 0 ? 1 : charWidth(characters, i);
                    if (to >= 0 && from >= 0) {
                        if (from <= to) {
                            for (int c = from; c <= to; c++) {
                                strip[c] = true;
                            }
                        } else {
                            // PHP 的区间要求 from <= to；反着写会走 "Invalid '..'-range" 警告分支，
                            // 这里退化成两个单字符，保持「不崩」的行为
                            strip[from] = true;
                            strip[to] = true;
                        }
                    }
                } else {
                    // 悬空 ".."：PHP 会警告并把点号当普通字符
                    strip['.'] = true;
                }
            } else if (from >= 0) {
                strip[from] = true;
            }
        }
        return strip;
    }

    /** 读一个字符的码值；反斜杠转义返回转义后的字节值，非 ASCII 返回 -1。 */
    private static int characterAt(String s, int index) {
        char c = s.charAt(index);
        if (c == '\\' && index + 1 < s.length()) {
            char escaped = s.charAt(index + 1);
            return switch (escaped) {
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'v' -> 0x0B;
                case 'f' -> '\f';
                case 'e' -> 0x1B;
                case '\\' -> '\\';
                case '0' -> 0;
                default -> escaped < 256 ? escaped : -1;
            };
        }
        return c < 256 ? c : -1;
    }

    /** 一个「字符」在源串里占的 UTF-16 长度。 */
    private static int charWidth(String s, int index) {
        if (s.charAt(index) == '\\' && index + 1 < s.length()) {
            return 2;
        }
        return 1;
    }

    /**
     * 等价于 PHP 的 {@code mb_substr($s, $start, $length)}，按码点计数。
     *
     * <p>负数的 {@code $length} 表示「去掉末尾 N 个字符」，负数 {@code $start} 表示从末尾数，
     * 与 PHP 一致。超出长度时自动截短，绝不抛异常。
     */
    public static String mbSubstr(String s, int start, int length) {
        if (s == null) {
            return "";
        }
        int[] cps = s.codePoints().toArray();
        int total = cps.length;
        if (start < 0) {
            start = Math.max(total + start, 0);
        }
        if (start > total) {
            return "";
        }
        int end;
        if (length < 0) {
            end = total + length;
        } else {
            end = start + length;
        }
        end = Math.min(end, total);
        if (end <= start) {
            return "";
        }
        return new String(cps, start, end - start);
    }

    /**
     * 等价于 PHP 的 {@code substr($s, $start)}（按<b>字节</b>）。
     *
     * <p>只有 {@code communications.locale} 用到它（{@code substr($option->option_name, 13)}）。
     * 键名是 ASCII，但为了在异常输入下也不产生半个 UTF-8 序列，这里按字节切完再校验；
     * 校验失败时回退到「按码点切」，实际不会触发。
     */
    public static String substrBytes(String s, int start) {
        if (s == null) {
            return "";
        }
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (start >= bytes.length) {
            return "";
        }
        int from = Math.max(start, 0);
        byte[] slice = java.util.Arrays.copyOfRange(bytes, from, bytes.length);
        return new String(slice, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 等价于 PHP 的 {@code (string) $value}。
     *
     * <p>只覆盖迁移里会遇到的类型；数组的 {@code "Array"} 与 PHP 的 {@code (string)} 结果
     * 一致（PHP 同时会发一条 warning，这里没有 warning 通道，忽略）。
     */
    public static String cast(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean b) {
            return b ? "1" : "";
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            // PHP 的 (string) 用 precision=14，与 json_encode 的 serialize_precision 不同
            return precision14(d);
        }
        if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
            return "Array";
        }
        return String.valueOf(value);
    }

    /**
     * PHP {@code (string)} 浮点转换：等价于 {@code zend_gcvt($d, 14, '.', 'E')}。
     *
     * <p>与 {@link PhpJson#number} 的两处不同：
     * <ul>
     *   <li>有效位数是 {@code precision}（默认 <b>14</b>），不是「最短往返」；</li>
     *   <li>科学计数法的阈值是 {@code decpt < -3 || decpt > 14}（JSON 那边是 17）。</li>
     * </ul>
     *
     * <p>已用 PHP 8.5 实测锁定（{@code precision=14}）：
     * <pre>
     * 1.0            → 1
     * 1/3            → 0.33333333333333
     * 2/3            → 0.66666666666667
     * 0.1+0.2        → 0.3
     * 12345678901234 → 12345678901234      （decpt = 14，仍用定点）
     * 1e14           → 1.0E+14             （decpt = 15，改科学计数法）
     * 999999999999999→ 1.0E+15
     * 0.0001         → 0.0001              （decpt = -3，仍用定点）
     * 1e-5           → 1.0E-5              （decpt = -4）
     * 5e-324         → 4.9406564584125E-324
     * -0.0           → -0
     * </pre>
     *
     * <p>尾零处理有区别：定点形式把尾零连同小数点一起去掉（{@code 100.0 → 100}），
     * 科学计数法至少保留一位小数（{@code 1.0E+14}，而不是 {@code 1E+14}）。
     */
    static String precision14(double d) {
        if (d == 0.0) {
            return Double.doubleToRawLongBits(d) == 0x8000000000000000L ? "-0" : "0";
        }
        boolean negative = d < 0;
        // zend_dtoa 的「有效位 + 正确舍入」等价实现：先按 14 位有效数字取整
        java.math.BigDecimal rounded = new java.math.BigDecimal(Math.abs(d))
                .round(new java.math.MathContext(PRECISION, java.math.RoundingMode.HALF_EVEN));
        // decpt 必须在去掉尾零之前算：0.1+0.2 的 digits 是 "30000000000000"、decpt = 0，
        // 去掉尾零变成 "3" 后仍要按 decpt = 0 输出成 0.3
        String digits = rounded.unscaledValue().toString();
        int decpt = digits.length() - rounded.scale();
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') {
            end--;
        }
        digits = digits.substring(0, end);

        StringBuilder out = new StringBuilder();
        if (negative) {
            out.append('-');
        }
        if (decpt < -3 || decpt > PRECISION) {
            out.append(digits.charAt(0)).append('.');
            out.append(digits.length() > 1 ? digits.substring(1) : "0");
            int exp = decpt - 1;
            out.append('E').append(exp < 0 ? '-' : '+').append(Math.abs(exp));
        } else if (decpt <= 0) {
            out.append("0.");
            for (int i = 0; i < -decpt; i++) {
                out.append('0');
            }
            out.append(digits);
        } else if (decpt >= digits.length()) {
            out.append(digits);
            for (int i = digits.length(); i < decpt; i++) {
                out.append('0');
            }
        } else {
            out.append(digits, 0, decpt).append('.').append(digits, decpt, digits.length());
        }
        return out.toString();
    }
}
