package com.neoskin.support;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PHP {@code json_encode()} / {@code json_decode($json, true)} 的等价实现。
 *
 * <p>为什么不直接用 Jackson：写库内容要能被 PHP 版原样读回，而两者的默认行为差异很大：
 *
 * <table border="1">
 *   <caption>PHP 与 Jackson 的差异</caption>
 *   <tr><th>输入</th><th>PHP</th><th>Jackson</th></tr>
 *   <tr><td>{@code 1.0}</td><td>{@code 1}</td><td>{@code 1.0}</td></tr>
 *   <tr><td>{@code 1e20}</td><td>{@code 1.0e+20}</td><td>{@code 1.0E20}</td></tr>
 *   <tr><td>{@code "/"}</td><td>{@code "\/"}（默认）</td><td>{@code "/"}</td></tr>
 *   <tr><td>{@code "密"}</td><td>转义成 {@code U+5BC6} 形式（默认）</td><td>{@code "密"}</td></tr>
 *   <tr><td>{@code U+007F}（DEL）</td><td>原样输出</td><td>-</td></tr>
 *   <tr><td>空数组</td><td>{@code []}</td><td>{@code []}</td></tr>
 * </table>
 *
 * <p>关键规则（全部由 {@code conformance/data/json.json} 的 83 条用例锁定）：
 * <ol>
 *   <li>浮点用 {@code serialize_precision=-1} 的<b>最短往返</b>表示；定点还是科学计数法
 *       由小数点位置决定：{@code decpt < -3 || decpt > 17} 用科学计数法，
 *       且科学计数法的小数点后允许只有一位 {@code 0}（{@code 1.0e+20}）；</li>
 *   <li>{@code /} 默认转义成 {@code \/}；非 ASCII 默认转义成 {@code U+XXXX} 形式（emoji 是代理对），
 *       但 {@code 0x7F} 原样输出，{@code U+2028}/{@code U+2029} 即使开了
 *       {@code JSON_UNESCAPED_UNICODE} 也要转义；</li>
 *   <li>「数组还是对象」只看键是不是<b>从 0 开始的连续整数</b>；空数组永远是 {@code []}
 *       （这也是 PHP 里 {@code json_decode('{}', true)} 再编码会变成 {@code []} 的原因）。</li>
 * </ol>
 *
 * <p>Laravel 的 {@code response()->json()} 在 13.x 里用 <b>0</b>
 * （{@code JsonResponse::$encodingOptions}），即「PHP 裸默认」，已由语料用例
 * {@code const.encoding_options} 实测确认。只有迁移回写才显式传
 * {@code JSON_UNESCAPED_UNICODE}（见 {@code DataMigrator}）。
 */
public final class PhpJson {

    /** PHP 的 {@code JSON_HEX_TAG}：{@code <} / {@code >} 转成 {@code \u003C} / {@code \u003E}。 */
    public static final int JSON_HEX_TAG = 1;

    /** PHP 的 {@code JSON_HEX_AMP}：{@code &} 转成 {@code \u0026}。 */
    public static final int JSON_HEX_AMP = 2;

    /** PHP 的 {@code JSON_HEX_APOS}：{@code '} 转成 {@code \u0027}。 */
    public static final int JSON_HEX_APOS = 4;

    /** PHP 的 {@code JSON_HEX_QUOT}：{@code "} 转成 {@code \u0022}（覆盖默认的 {@code \"}）。 */
    public static final int JSON_HEX_QUOT = 8;

    /** PHP 的 {@code JSON_UNESCAPED_SLASHES}。 */
    public static final int JSON_UNESCAPED_SLASHES = 64;

    /** PHP 的 {@code JSON_UNESCAPED_UNICODE}。 */
    public static final int JSON_UNESCAPED_UNICODE = 256;

    /** PHP 数组在 Java 侧的统一表示：键是 {@link Long}（PHP 的 int 键）或 {@link String}。 */
    public static final class Array extends LinkedHashMap<Object, Object> {
        private static final long serialVersionUID = 1L;

        public Array() {
            super();
        }

        /** 浅拷贝（键已是归一化形态，直接走父类 put 避免二次归一化）。 */
        public Array(java.util.Map<Object, Object> source) {
            super(source);
        }

        /**
         * 键归一化是本结构的不变量：任何 {@code put} 都过
         * {@link PhpJson#normalizeKey(Object)}（{@code "1"} 会变成 int 键 {@code 1}）。
         * 之前只有 {@link #with} 和 decode 路径归一化，{@link java.util.LinkedHashMap}
         * 直接 put 会漏——中间件递归构造数组时键就串型了。
         */
        @Override
        public Object put(Object key, Object value) {
            return super.put(normalizeKey(key), value);
        }

        /** 按 PHP 的键归一化规则放入（{@code "1"} 会变成 int 键 {@code 1}）。 */
        public Array with(Object key, Object value) {
            put(normalizeKey(key), value);
            return this;
        }
    }

    private PhpJson() {
    }

    // ================================================================== 编码

    /** 等价于 {@code json_encode($value)}（flags 为 0）。 */
    public static String encode(Object value) {
        return encode(value, 0);
    }

    /** 等价于 {@code json_encode($value, $flags)}。 */
    public static String encode(Object value, int flags) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, flags);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value, int flags) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
            return;
        }
        if (value instanceof String s) {
            writeString(sb, s, flags);
            return;
        }
        if (value instanceof Double || value instanceof Float) {
            sb.append(number(((Number) value).doubleValue()));
            return;
        }
        if (value instanceof BigDecimal bd) {
            if (bd.scale() <= 0) {
                sb.append(bd.toBigInteger().toString());
            } else {
                sb.append(number(bd.doubleValue()));
            }
            return;
        }
        if (value instanceof Number n) {
            sb.append(n.longValue());
            return;
        }
        if (value instanceof Map<?, ?> map) {
            writeArray(sb, map, flags);
            return;
        }
        if (value instanceof Iterable<?> list) {
            Array array = new Array();
            long i = 0;
            for (Object item : list) {
                array.put(i++, item);
            }
            writeArray(sb, array, flags);
            return;
        }
        writeString(sb, String.valueOf(value), flags);
    }

    private static void writeArray(StringBuilder sb, Map<?, ?> raw, int flags) {
        Array map = normalize(raw);
        boolean list = isList(map);
        if (map.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append(list ? '[' : '{');
        boolean first = true;
        for (Map.Entry<Object, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            if (!list) {
                writeString(sb, String.valueOf(e.getKey()), flags);
                sb.append(':');
            }
            write(sb, e.getValue(), flags);
        }
        sb.append(list ? ']' : '}');
    }

    /** 键是否恰为 {@code 0..n-1}（PHP 的 {@code array_is_list()}）。 */
    public static boolean isList(Map<?, ?> map) {
        long expected = 0;
        for (Object key : map.keySet()) {
            if (!(key instanceof Long l) || l != expected) {
                return false;
            }
            expected++;
        }
        return true;
    }

    // ================================================================== 数字

    /**
     * PHP {@code serialize_precision=-1} 的浮点格式化（{@code zend_gcvt} + json 调整）。
     *
     * <p>步骤：先求最短往返十进制串（Java 的 {@code Double.toString} 在 21 上不保证最短，
     * 例如 {@code Double.MIN_VALUE} 会得到 {@code 4.9E-324}，而 PHP 是 {@code 5.0E-324}，
     * 所以这里用「逐位精度 + 往返校验」自己算），再按小数点位置选择输出形式。
     */
    public static String number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("Inf and NaN cannot be JSON encoded");
        }
        if (d == 0.0) {
            return Double.doubleToRawLongBits(d) == 0x8000000000000000L ? "-0" : "0";
        }
        boolean negative = d < 0;
        BigDecimal shortest = shortest(Math.abs(d));
        String digits = shortest.unscaledValue().toString();
        int decpt = digits.length() - shortest.scale();
        // 去掉尾随零（BigDecimal.round 可能留下，如 1E+3 的 unscaled 是 1，但保险起见）
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') {
            end--;
        }
        digits = digits.substring(0, end);

        StringBuilder out = new StringBuilder();
        if (negative) {
            out.append('-');
        }
        if (decpt < -3 || decpt > 17) {
            out.append(digits.charAt(0)).append('.');
            out.append(digits.length() > 1 ? digits.substring(1) : "0");
            int exp = decpt - 1;
            out.append('e').append(exp < 0 ? '-' : '+').append(Math.abs(exp));
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

    /**
     * 最短的、能往返回同一个 {@code double} 的十进制表示。
     *
     * <p>逐个有效位精度四舍五入后回读，第一个能还原的即为最短。
     * 选用 {@link RoundingMode#HALF_EVEN} 是为了与 C 库 {@code dtoa} 的平局处理一致。
     */
    private static BigDecimal shortest(double positive) {
        BigDecimal exact = new BigDecimal(positive);
        for (int precision = 1; precision <= 17; precision++) {
            BigDecimal candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            if (Double.parseDouble(candidate.toString()) == positive) {
                return candidate;
            }
        }
        throw new IllegalStateException("no shortest representation for " + positive);
    }

    // ================================================================== 键

    /** PHP 的数组键归一化：十进制整数字符串会变成 int 键。 */
    public static Object normalizeKey(Object key) {
        if (key instanceof Number n) {
            return n.longValue();
        }
        if (key instanceof String s && isIntegerString(s)) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return s;
            }
        }
        return key;
    }

    /** {@code "0"}、{@code "12"}、{@code "-3"} 是整数键；{@code "01"}、{@code "+1"}、{@code "1.0"} 不是。 */
    private static boolean isIntegerString(String s) {
        if (s.isEmpty()) {
            return false;
        }
        int i = s.charAt(0) == '-' ? 1 : 0;
        if (i == 1 && s.length() == 1) {
            return false;
        }
        if (s.charAt(i) == '0' && s.length() - i > 1) {
            return false;
        }
        for (int j = i; j < s.length(); j++) {
            if (s.charAt(j) < '0' || s.charAt(j) > '9') {
                return false;
            }
        }
        return true;
    }

    private static Array normalize(Map<?, ?> raw) {
        Array map = new Array();
        raw.forEach((k, v) -> map.put(normalizeKey(k), v));
        return map;
    }

    // ================================================================== 字符串

    /**
     * PHP 的字符串转义。
     *
     * <p>逐条对照 {@code ext/json} 的 {@code php_json_escape_string}：
     * <ul>
     *   <li>{@code "} 与 {@code \} 一定转义；{@code /} 只有没开
     *       {@code JSON_UNESCAPED_SLASHES} 才转义；</li>
     *   <li>{@code \b \f \n \r \t} 用短形式，其余 {@code < 0x20} 用 {@code U+00XX}
     *       （<b>小写</b>十六进制）；</li>
     *   <li>{@code 0x7F}（DEL）<b>不转义</b>，直接输出；</li>
     *   <li>非 ASCII 在没开 {@code JSON_UNESCAPED_UNICODE} 时全部转成 {@code U+XXXX} 形式，
     *       补充平面用代理对；开了之后原样输出 UTF-8；</li>
     *   <li>{@code U+2028} / {@code U+2029} 是唯一「开了 UNESCAPED_UNICODE 也要转义」的字符
     *       —— 它们会被 JavaScript 当成换行，PHP 因此无条件转义。</li>
     *   <li>{@code JSON_HEX_TAG / HEX_AMP / HEX_APOS / HEX_QUOT} 把
     *       {@code < > & ' "} 转成<b>大写</b>十六进制的 {@code \u003C} 等形式，
     *       其余规则不变（{@code /} 仍按 UNESCAPED_SLASHES 处理）。</li>
     * </ul>
     */
    private static void writeString(StringBuilder sb, String s, int flags) {
        boolean unescapedUnicode = (flags & JSON_UNESCAPED_UNICODE) != 0;
        boolean unescapedSlashes = (flags & JSON_UNESCAPED_SLASHES) != 0;
        boolean hexTag = (flags & JSON_HEX_TAG) != 0;
        boolean hexAmp = (flags & JSON_HEX_AMP) != 0;
        boolean hexApos = (flags & JSON_HEX_APOS) != 0;
        boolean hexQuot = (flags & JSON_HEX_QUOT) != 0;
        sb.append('"');
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            // JSON_HEX_* 的四个字符用**大写**十六进制（ext/json 里是硬编码字面量
            // "\u003C" 之类），与下面控制字符/非 ASCII 的小写形式不同。
            if (c == '<' && hexTag) {
                sb.append("\\u003C");
                i++;
            } else if (c == '>' && hexTag) {
                sb.append("\\u003E");
                i++;
            } else if (c == '&' && hexAmp) {
                sb.append("\\u0026");
                i++;
            } else if (c == '\'' && hexApos) {
                sb.append("\\u0027");
                i++;
            } else if (c == '"' && hexQuot) {
                sb.append("\\u0022");
                i++;
            } else if (c == '"') {
                sb.append("\\\"");
                i++;
            } else if (c == '\\') {
                sb.append("\\\\");
                i++;
            } else if (c == '/') {
                sb.append(unescapedSlashes ? "/" : "\\/");
                i++;
            } else if (c == '\b') {
                sb.append("\\b");
                i++;
            } else if (c == '\f') {
                sb.append("\\f");
                i++;
            } else if (c == '\n') {
                sb.append("\\n");
                i++;
            } else if (c == '\r') {
                sb.append("\\r");
                i++;
            } else if (c == '\t') {
                sb.append("\\t");
                i++;
            } else if (c < 0x20) {
                appendUnicodeEscape(sb, c);
                i++;
            } else if (c <= 0x7F) {
                // 含 DEL（0x7F）：PHP 的 ext/json 只对 < 0x20 做 U+00XX 转义
                sb.append(c);
                i++;
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    throw malformed();
                }
                int cp = Character.toCodePoint(c, s.charAt(i + 1));
                if (unescapedUnicode) {
                    sb.appendCodePoint(cp);
                } else {
                    appendUnicodeEscape(sb, Character.highSurrogate(cp));
                    appendUnicodeEscape(sb, Character.lowSurrogate(cp));
                }
                i += 2;
            } else if (Character.isLowSurrogate(c)) {
                throw malformed();
            } else if (!unescapedUnicode || c == '\u2028' || c == '\u2029') {
                appendUnicodeEscape(sb, c);
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        sb.append('"');
    }

    private static void appendUnicodeEscape(StringBuilder sb, char c) {
        sb.append("\\u");
        for (int shift = 12; shift >= 0; shift -= 4) {
            sb.append(HEX[(c >> shift) & 0xF]);
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** PHP 在这种情况下返回 {@code false} 并设置 {@code JSON_ERROR_UTF8}。 */
    public static IllegalArgumentException malformed() {
        return new IllegalArgumentException(
                "Malformed UTF-8 characters, possibly incorrectly encoded");
    }

    // ================================================================== 解码

    /**
     * 等价于 {@code json_decode($json, true)}。
     *
     * <p>与 PHP 一致的三个细节（迁移回写依赖它们）：
     * <ul>
     *   <li>对象解成 {@code true} 关联数组 → 用 {@link Array}，数字字符串键变 int 键；</li>
     *   <li>空对象 {@code {}} 与空数组 {@code []} 解出来<b>无法区分</b>，重新编码都变 {@code []}；</li>
     *   <li>键顺序按首次出现位置，重复键保留<b>首次</b>位置但取<b>最后一次</b>的值
     *       （{@code {"a":1,"a":2}} → {@code {"a":2}}）。</li>
     * </ul>
     *
     * @return {@code null} / {@link Boolean} / {@link Long} / {@link Double} /
     *         {@link String} / {@link Array}
     * @throws IllegalArgumentException 语法错误、无法配对的代理项、无效 UTF-8
     */
    public static Object decode(String json) {
        if (json == null) {
            throw new IllegalArgumentException("Syntax error");
        }
        if (!json.isEmpty() && json.charAt(0) == '\uFEFF') {
            // PHP 把 BOM 当成非法字符
            throw new IllegalArgumentException("Syntax error");
        }
        Parser parser = new Parser(json);
        parser.skipWhitespace();
        Object value = parser.value();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("Syntax error");
        }
        return value;
    }

    /** 解码并要求顶层是对象/数组，其他一律返回 {@code null}（对应 {@code !is_array($data)}）。 */
    public static Array decodeArray(String json) {
        try {
            Object value = decode(json);
            return value instanceof Array array ? array : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value() {
            if (atEnd()) {
                throw new IllegalArgumentException("Syntax error");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return number();
            }
        }

        private void expect(String literal) {
            if (!s.startsWith(literal, pos)) {
                throw new IllegalArgumentException("Syntax error");
            }
            pos += literal.length();
        }

        private Array object() {
            Array out = new Array();
            pos++; // {
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != '"') {
                    throw new IllegalArgumentException("Syntax error");
                }
                String key = string();
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != ':') {
                    throw new IllegalArgumentException("Syntax error");
                }
                pos++;
                skipWhitespace();
                // 重复键：LinkedHashMap 保留首次插入位置，put 覆盖值 —— 与 PHP 相同
                out.put(normalizeKey(key), value());
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalArgumentException("Syntax error");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return out;
                } else {
                    throw new IllegalArgumentException("Syntax error");
                }
            }
        }

        private Array array() {
            Array out = new Array();
            pos++; // [
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == ']') {
                pos++;
                return out;
            }
            long index = 0;
            while (true) {
                skipWhitespace();
                out.put(index++, value());
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalArgumentException("Syntax error");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return out;
                } else {
                    throw new IllegalArgumentException("Syntax error");
                }
            }
        }

        private String string() {
            pos++; // "
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new IllegalArgumentException("Syntax error");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw new IllegalArgumentException("Syntax error");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> sb.append(unicodeEscape());
                        default -> throw new IllegalArgumentException("Syntax error");
                    }
                } else if (c < 0x20) {
                    throw new IllegalArgumentException("Syntax error");
                } else {
                    sb.append(c);
                }
            }
        }

        /** {@code U+XXXX} 转义，遇到高代理项时要求紧跟一个低代理项转义。 */
        private String unicodeEscape() {
            char high = hex4();
            if (Character.isLowSurrogate(high)) {
                throw new IllegalArgumentException(
                        "Single unpaired UTF-16 surrogate in unicode escape");
            }
            if (Character.isHighSurrogate(high)) {
                if (!s.startsWith("\\u", pos)) {
                    throw new IllegalArgumentException(
                            "Single unpaired UTF-16 surrogate in unicode escape");
                }
                pos += 2;
                char low = hex4();
                if (!Character.isLowSurrogate(low)) {
                    throw new IllegalArgumentException(
                            "Single unpaired UTF-16 surrogate in unicode escape");
                }
                return new String(new char[] { high, low });
            }
            return String.valueOf(high);
        }

        private char hex4() {
            if (pos + 4 > s.length()) {
                throw new IllegalArgumentException("Syntax error");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(s.charAt(pos + i), 16);
                if (digit < 0) {
                    throw new IllegalArgumentException("Syntax error");
                }
                value = value * 16 + digit;
            }
            pos += 4;
            return (char) value;
        }

        private Object number() {
            int start = pos;
            if (!atEnd() && s.charAt(pos) == '-') {
                pos++;
            }
            // 整数部分：0 或 [1-9][0-9]*
            if (atEnd()) {
                throw new IllegalArgumentException("Syntax error");
            }
            if (s.charAt(pos) == '0') {
                pos++;
            } else if (s.charAt(pos) >= '1' && s.charAt(pos) <= '9') {
                while (!atEnd() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            } else {
                throw new IllegalArgumentException("Syntax error");
            }
            boolean integer = true;
            if (!atEnd() && s.charAt(pos) == '.') {
                integer = false;
                pos++;
                if (atEnd() || !Character.isDigit(s.charAt(pos))) {
                    throw new IllegalArgumentException("Syntax error");
                }
                while (!atEnd() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (!atEnd() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                integer = false;
                pos++;
                if (!atEnd() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                if (atEnd() || !Character.isDigit(s.charAt(pos))) {
                    throw new IllegalArgumentException("Syntax error");
                }
                while (!atEnd() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            String text = s.substring(start, pos);
            if (integer) {
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException e) {
                    // PHP：超出 int64 的整数变成 float
                    return Double.parseDouble(text);
                }
            }
            return Double.parseDouble(text);
        }
    }
}
