package com.neoskin.support;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PHP {@code unserialize()} 的移植。
 *
 * <p>语料：{@code conformance/data/cookie.json} 的 11 条 {@code unserialize.*}，以及
 * {@code decrypt.valid-serialize} / {@code decrypt.unserialize-raw-string}。
 *
 * <h2>为什么失败也要带 offset</h2>
 *
 * <p>Laravel 的错误处理器会把 {@code unserialize()} 的 warning 提升成
 * {@code ErrorException}，消息里带着「<b>Error at offset N of M bytes</b>」。会话
 * （{@code config/session.php} 的 {@code serialization = 'php'}）读到损坏数据时抛出的
 * 就是这一条，行为必须一致才能让上层的异常处理、日志、诊断页面保持不变。所以移植
 * 不是「解析失败返回 false」这么简单，而是<b>必须复现 offset</b>。
 *
 * <h2>offset 是怎么算的</h2>
 *
 * <p>PHP 在 {@code php_var_unserialize()} 返回 0 之后打印
 * {@code (zend_long)(p - buf)}——<b>p 是解析器停下的位置</b>，不是出错字符的位置。
 * 有些分支根本不挪 p（例如在「值」的位置遇到 {@code }} 时），于是 offset 停在出事
 * 之前。本类逐分支照抄了这些差别：
 *
 * <table border="1">
 *   <caption>典型输入的停点</caption>
 *   <tr><th>输入</th><th>报的 offset</th></tr>
 *   <tr><td>{@code hello}</td><td>0（完全没匹配，p 不动）</td></tr>
 *   <tr><td>{@code a:2:{i:0;i:1;}}</td><td>13（先警告 Unexpected end，p 不动）</td></tr>
 *   <tr><td>{@code s:10:"ab";}</td><td>2（{@code $p = $start + 2}）</td></tr>
 *   <tr><td>{@code s:3:"ab";}</td><td>8（停在那个不是引号的字节上）</td></tr>
 *   <tr><td>{@code i:42}（缺分号）</td><td>0（语法没匹配，p 回退）</td></tr>
 * </table>
 *
 * <h2>警告不等于失败</h2>
 *
 * <p>整数溢出（Numerical result out of range）和尾随垃圾（Extra data starting at
 * offset N）都<b>只是警告</b>，值照样返回。{@link Result#ok()} 只在「值退化成 false」
 * 时为 {@code false}。
 *
 * <h2>已知不支持</h2>
 *
 * <p>枚举（{@code E:}）按失败处理——本应用的会话数据是纯标量加数组，永远不会产生它。
 * {@code O:} / {@code C:} 解析成 {@link PhpObject}：未知类退化成
 * {@code __PHP_Incomplete_Class}（C: 会发出裸 {@code zend_error} 警告）；属性名不做
 * 可见性还原（PHP 里那套 {@code \0Class\0prop} 编码只在对象属性上出现，这里原样保留）。
 *
 * <p>字符串按字节长度切，但用 UTF-8 解码成 {@link String}；非法字节序列会被替换成
 * U+FFFD（见 {@code PORT-NOTES.md}）。合法 UTF-8 与 PHP 完全一致。
 */
public final class PhpUnserialize {

    /** {@code HT_MAX_SIZE}：PHP 哈希表容量上限，元素数达到它就判非法。 */
    private static final long HT_MAX_SIZE = 1L << 30;

    /** {@code MAX_LENGTH_OF_LONG - 1}（64 位平台是 19）；数字位数超过它就是溢出。 */
    private static final int MAX_DIGITS = 19;

    /**
     * {@code E:} 会命中「is not an enum」警告的内置类型名（zend_lookup_class
     * 能解析到、但不是 enum 的内部 interface / 类）。除此之外的名字报「not found」。
     */
    private static final Set<String> KNOWN_INTERNAL_TYPES = Set.of(
            "UnitEnum", "BackedEnum", "Stringable", "Traversable", "Countable",
            "ArrayAccess", "Iterator", "IteratorAggregate", "IteratorIterator",
            "JsonSerializable", "Throwable", "Serializable", "DateTimeInterface",
            "Closure", "Generator", "WeakMap", "stdClass",
            "DateTime", "DateTimeImmutable", "ArrayObject", "SplObjectStorage");

    /**
     * 「已定义」类集合（ {@code O:} 类解析用）：本应用不加载用户类，表中名字才按
     * 原类名建对象；其余一律退化成 {@code __PHP_Incomplete_Class}。
     */
    private static final Set<String> KNOWN_CLASSES;

    static {
        Set<String> known = new java.util.HashSet<>(KNOWN_INTERNAL_TYPES);
        known.add("stdClass");
        KNOWN_CLASSES = Set.copyOf(known);
    }

    /**
     * 解析结果。
     *
     * @param ok       是否解析出一个值（{@code false} 时 {@link #value()} 是 {@code FALSE}）
     * @param value    解析出的值；数组是 {@link PhpJson.Array}，对象是 {@link PhpObject}
     * @param warnings 按触发顺序排列的警告文本，不含 {@code unserialize(): } 前缀
     */
    public record Result(boolean ok, Object value, List<String> warnings) {

        public Result {
            warnings = List.copyOf(warnings);
        }

        /** 第一条警告，没有则 {@code null}。Laravel 的错误处理器只把第一条变成异常。 */
        public String firstWarning() {
            return warnings.isEmpty() ? null : warnings.get(0);
        }
    }

    /** PHP 对象（{@code O:} 或 {@code C:}）。 */
    public record PhpObject(String className, PhpJson.Array properties) {
    }

    private final byte[] buf;

    private final List<String> warnings = new ArrayList<>();

    /**
     * 引用表（php-src 的 {@code var_hash → var_entries}）。
     *
     * <p>php-src 语义（{@code tools/ref-var_unserializer.re}）：每个解析进来的
     * 值（数组元素值 / 对象属性值 / 根容器）在<b>开始解析</b>时入库——</p>
     * <ul>
     *   <li>{@code if (var_hash && (*p)[0] != 'R') var_push(rval)}：只有以
     *       <b>大写 R 开头</b>的值不占 slot（小写 r 占！）。键不经过这条路径
     *       （打 {@code var_hash = NULL} 给键的解析）。</li>
     *   <li>{@code R:<n>;} / {@code r:<n>;} 的 {@code id = n - 1} 是 0 基表下标；
     *       表外或指到自己（{} &rarr; PHP 里 push 的就是自身那个 zval 槽位）
     *       都失败，且 p 已推到 {@code ;} 之后（报错 offset 停点）。</li>
     *   <li>容器入库的是 zval <b>指针</b>——内容解析过程会变化（未完成的数组
     *       也会被 {@code R:} 取到，观察者看到 *RECURSION*）。Java 侧放入
     *       {@link PhpJson.Array} 对象本身，让两个入口共享同一 LinkedHashMap。</li>
     * </ul>
     */
    private final List<Object> varTable = new ArrayList<>();

    /** 当前解析位置，对应 PHP 的 {@code *p}。 */
    private int p;

    private PhpUnserialize(byte[] buf) {
        this.buf = buf;
    }

    /** 等价于 {@code unserialize($input)}（默认选项）。 */
    public static Result parse(String input) {
        return parse(input.getBytes(StandardCharsets.UTF_8));
    }

    /** 等价于 {@code unserialize($input)}，输入是原始字节。 */
    public static Result parse(byte[] input) {
        // php_unserialize_with_options(): buf_len == 0 → RETURN_FALSE，一条警告都不报。
        if (input.length == 0) {
            return new Result(false, Boolean.FALSE, List.of());
        }

        PhpUnserialize parser = new PhpUnserialize(input);

        try {
            Object value = parser.parseOne();

            if (parser.p < input.length) {
                parser.warnings.add(
                        "Extra data starting at offset " + parser.p + " of " + input.length + " bytes");
            }

            return new Result(true, value, parser.warnings);
        } catch (Failure ignored) {
            parser.warnings.add("Error at offset " + parser.p + " of " + input.length + " bytes");

            return new Result(false, Boolean.FALSE, parser.warnings);
        }
    }

    // ================================================================== 解析

    /** 控制流用异常，不携带信息：p 已经停在 PHP 会停的位置。 */
    private static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Failure() {
            super(null, null, false, false);
        }
    }

    private Failure fail() {
        throw new Failure();
    }

    /** 读第 i 个字节；越界当作 C 字符串结尾的 NUL。 */
    private int at(int i) {
        return i >= 0 && i < buf.length ? buf[i] & 0xFF : 0;
    }

    private Object parseOne() {
        return parseOne(false);
    }

    /**
     * php_var_unserialize_internal 的骨架。
     *
     * <p>进门即 {@code if (var_hash && (*p)[0] != 'R') var_push(rval)}：</p>
     * <ul>
     *   <li>键位（{@code keyMode}）解析是 {@code var_hash == NULL} 调用——不占
     *       slot；</li>
     *   <li>以<b>大写 R 开头</b>的值不占 slot（小写 r 一样不占——但小写 r
     *       失败回滚的判定链不同，见 {@link #parseReference}）；</li>
     *   <li>其余值（含标量）push 一个 slot；容器在 parseArray / parseObject
     *       开头就把<b>同一个对象</b>覆盖到这个 slot 上，让进行中的容器能被
     *       {@code R:<n>} 观察到（PHP 的 push 是 zval 指针，Java 用共享对象
     *       复现同一语义）。</li>
     * </ul>
     */
    private Object parseOne(boolean keyMode) {
        // php_var_unserialize_internal(): YYCURSOR >= YYLIMIT → return 0，p 不动。
        if (p >= buf.length) {
            return fail();
        }

        // 键位解析的 var_hash == NULL：键不占 slot。
        // 以大写 R 开头的值不占 slot（php-src 的字符级判断，r 小写照占）。
        int slot = !keyMode && at(p) != 'R' ? varTable.size() : -1;

        if (slot >= 0) {
            varTable.add(null);
        }

        Object value = parseValue(keyMode);

        if (slot >= 0) {
            varTable.set(slot, value);
        }

        return value;
    }

    private Object parseValue(boolean keyMode) {
        int start = p;

        switch (at(p)) {
            case 'N':
                if (at(p + 1) == ';') {
                    p += 2;

                    return null;
                }

                return fail();

            case 'b':
                return parseBool(start);

            case 'i':
                return parseInt(start);

            case 'd':
                return parseDouble(start);

            case 's':
                return parseString(start);

            case 'S':
                return parseEscapedString(start);

            case 'a':
                return parseArray(start, keyMode);

            case 'O':
            case 'C':
                return parseObject(start, keyMode);

            case 'R':
            case 'r':
                return parseReference(start, keyMode, at(start) == 'r');

            case 'E':
                return parseEnum(start);

            case '}':
                // 语法里唯一匹配 '}' 的规则：这就是「数据比声明短」的情形。
                warnings.add("Unexpected end of serialized data");

                return fail();

            default:
                return fail();
        }
    }

    /**
     * {@code b:0;} / {@code b:1;}。
     *
     * <p>语法只有这两条规则，{@code b:2;} 与 {@code b:;} 都不匹配 → p 不动 → offset 0。
     */
    private Object parseBool(int start) {
        if (at(p + 1) != ':') {
            return fail();
        }

        int digit = at(p + 2);

        if (digit != '0' && digit != '1') {
            return fail();
        }

        if (at(p + 3) != ';') {
            return fail();
        }

        p += 4;

        return digit == '1';
    }

    /**
     * {@code i:<iv>;}，其中 {@code iv = [+-]?[0-9]+}。
     *
     * <p>前导零允许（{@code i:042;} → 42），显式 {@code +} 允许，{@code i:-0;} → 0。
     * 溢出时<b>报警告但返回饱和值</b>，不是失败。
     */
    private Object parseInt(int start) {
        int q = p + 2;

        if (at(q) != '+' && at(q) != '-' && !isDigit(at(q))) {
            return fail();
        }

        int cursor = q;
        boolean neg = false;

        if (at(cursor) == '-') {
            neg = true;
            cursor++;
        } else if (at(cursor) == '+') {
            cursor++;
        }

        if (!isDigit(at(cursor))) {
            return fail();
        }

        while (isDigit(at(cursor))) {
            cursor++;
        }

        if (at(cursor) != ';') {
            return fail();
        }

        p = cursor + 1;

        return parseIv(start + 2, cursor);
    }

    /**
     * PHP 的 {@code parse_iv2()}：先跳过符号与前导零，再从 {@code start} 逐位累加，
     * 最后判断是否超出 int64。
     *
     * <p>溢出条件与原码一致：<b>有效数字位数</b>（跳零之后）超过 18 位，或者累加结果
     * 超过 {@code LONG_MAX + neg}。溢出时返回饱和值并追加警告。
     *
     * @param begin 数字串起点（含符号）
     * @param end   数字串终点（不含分号）
     */
    private long parseIv(int begin, int end) {
        int cursor = begin;
        boolean neg = false;

        if (at(cursor) == '-') {
            neg = true;
            cursor++;
        } else if (at(cursor) == '+') {
            cursor++;
        }

        while (at(cursor) == '0') {
            cursor++;
        }

        int digits = cursor;

        // 用负数累加，才能表示 -9223372036854775808。
        long result = 0;
        boolean overflow = false;

        while (cursor < end) {
            int digit = at(cursor) - '0';

            // ZEND_LONG_MAX = 9223372036854775807
            if (result < (Long.MIN_VALUE + digit) / 10) {
                overflow = true;
            } else {
                result = result * 10 - digit;
            }

            cursor++;
        }

        int length = cursor - digits;

        if (length > MAX_DIGITS - 1) {
            overflow = true;
        } else if (!neg && result == Long.MIN_VALUE) {
            // 9223372036854775808（正值）装不进 long。
            overflow = true;
        }

        if (overflow) {
            warnings.add("Numerical result out of range");

            return neg ? Long.MIN_VALUE : Long.MAX_VALUE;
        }

        return neg ? result : -result;
    }

    /** {@code d:<iv|nv|nvexp>;}，外加 {@code d:NAN;} / {@code d:INF;} / {@code d:-INF;}。 */
    private Object parseDouble(int start) {
        int q = p + 2;

        if (at(q) == 'N' && at(q + 1) == 'A' && at(q + 2) == 'N' && at(q + 3) == ';') {
            p = q + 4;

            return Double.NaN;
        }

        if (at(q) == 'I' && at(q + 1) == 'N' && at(q + 2) == 'F' && at(q + 3) == ';') {
            p = q + 4;

            return Double.POSITIVE_INFINITY;
        }

        if (at(q) == '-' && at(q + 1) == 'I' && at(q + 2) == 'N' && at(q + 3) == 'F' && at(q + 4) == ';') {
            p = q + 5;

            return Double.NEGATIVE_INFINITY;
        }

        int cursor = q;

        if (at(cursor) == '+' || at(cursor) == '-') {
            cursor++;
        }

        int before = cursor;

        while (isDigit(at(cursor))) {
            cursor++;
        }

        boolean hasInt = cursor > before;
        boolean hasFraction = false;

        // nv = [+-]? ([0-9]* "." [0-9]+ | [0-9]+ "." [0-9]*)
        // 所以「小数点前没数字」只有在「小数点后有数字」时才合法（`.5` 行、`.` 不行）。
        if (at(cursor) == '.' && (hasInt || isDigit(at(cursor + 1)))) {
            int dot = cursor;
            cursor++;

            int fractionStart = cursor;

            while (isDigit(at(cursor))) {
                cursor++;
            }

            hasFraction = cursor > fractionStart;

            // nv 要求「至少一侧有数字」：`1.`、`.5` 都合法，`.` 不合法。
            if (!hasFraction && !hasInt) {
                cursor = dot;

                return fail();
            }
        }

        if (!hasInt && !hasFraction) {
            return fail();
        }

        // 指数部分：nvexp = (iv | nv) [eE] iv
        if (at(cursor) == 'e' || at(cursor) == 'E') {
            int expMark = cursor;
            cursor++;

            if (at(cursor) == '+' || at(cursor) == '-') {
                cursor++;
            }

            if (!isDigit(at(cursor))) {
                cursor = expMark;

                return fail();
            }

            while (isDigit(at(cursor))) {
                cursor++;
            }
        }

        if (at(cursor) != ';') {
            return fail();
        }

        String literal = new String(buf, q, cursor - q, StandardCharsets.ISO_8859_1);

        p = cursor + 1;

        return Double.parseDouble(literal);
    }

    /** {@code 1} 或 {@code 1.} 时返回 true；{@code .} 单独出现时返回 false。 */
    private static boolean hasIntOrEmpty(int from, int to, boolean hasInt) {
        return hasInt || to > from;
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    /**
     * {@code s:<len>:"<bytes>";}。
     *
     * <p>三个分支的停点各不相同（对着 {@code var_unserializer.re} 1128 行起）：
     * <ul>
     *   <li>剩余长度不够 → {@code $p = $start + 2}，offset 落在长度数字上；</li>
     *   <li>第 len 个字节不是引号 → p 停在那个字节；</li>
     *   <li>引号后面不是分号 → p 停在后一个字节。</li>
     * </ul>
     */
    private Object parseString(int start) {
        int q = p + 2;
        int lenStart = q;

        if (!isDigit(at(q))) {
            return fail();
        }

        long len = 0;

        while (isDigit(at(q))) {
            len = len * 10 + (at(q) - '0');
            q++;

            if (len > Integer.MAX_VALUE) {
                // 长度本身超过 int 就不可能塞进 Java 数组；原码会在下面的
                // maxlen 检查里失败。p 回到长度起点。
                p = lenStart;

                return fail();
            }
        }

        if (at(q) != ':' || at(q + 1) != '"') {
            return fail();
        }

        q += 2;

        int maxlen = buf.length - q;

        if (maxlen < len) {
            p = lenStart;

            return fail();
        }

        int raw = q;
        q += (int) len;

        if (at(q) != '"') {
            p = q;

            return fail();
        }

        if (at(q + 1) != ';') {
            p = q + 1;

            return fail();
        }

        p = q + 2;

        return new String(buf, raw, (int) len, StandardCharsets.UTF_8);
    }

    /**
     * {@code S:<len>:"<escaped>";}（已废弃的转义格式，{@code \}} 后跟两位十六进制）。
     *
     * <p>本应用的序列化数据不会产生它，但解析要是不认，遇到就等于崩。保留支持并把
     * 那条 {@code E_DEPRECATED} 记成警告。
     */
    private Object parseEscapedString(int start) {
        int q = p + 2;
        int lenStart = q;

        if (!isDigit(at(q))) {
            return fail();
        }

        long len = 0;

        while (isDigit(at(q))) {
            len = len * 10 + (at(q) - '0');
            q++;
        }

        if (at(q) != ':' || at(q + 1) != '"') {
            return fail();
        }

        q += 2;

        int maxlen = buf.length - q;

        if (maxlen < len) {
            p = lenStart;

            return fail();
        }

        // S 的 len 是「解码后字节数」：unserialize_str 逐段解码（\xHH 占 3 个源字符）
        // 直到写满 len 个输出字节，源区可以读得比 len 长。
        byte[] out = new byte[(int) len];
        int written = 0;

        while (written < (int) len && q < buf.length) {
            if (at(q) != '\\') {
                out[written++] = buf[q++];

                continue;
            }

            int high = hexValue(at(q + 1));
            int low = hexValue(at(q + 2));

            if (high < 0 || low < 0) {
                return fail();
            }

            out[written++] = (byte) (high * 16 + low);
            q += 3;
        }

        if (at(q) != '"') {
            p = q;

            return fail();
        }

        if (at(q + 1) != ';') {
            p = q + 1;

            return fail();
        }

        p = q + 2;
        warnings.add("Unserializing the 'S' format is deprecated");

        return new String(out, 0, written, StandardCharsets.UTF_8);
    }

    private static int hexValue(int c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }

        return -1;
    }

    /** {@code a:<n>:{key value ...}}。 */
    private Object parseArray(int start, boolean keyMode) {
        int q = p + 2;

        // 计数的语法是 uiv = [0-9]+：不许有符号。
        if (!isDigit(at(q))) {
            return fail();
        }

        int countStart = q;

        while (isDigit(at(q))) {
            q++;
        }

        long elements = parseIv(countStart, q);

        if (at(q) != ':' || at(q + 1) != '{') {
            return fail();
        }

        // 内容起点。PHP 的 *p = YYCURSOR 在各合法性检查**之前**，失败 offset 落在这里。
        q += 2;
        p = q;

        if (elements >= HT_MAX_SIZE) {
            return fail();
        }

        // IS_FAKE_ELEM_COUNT：每个元素至少占 2 字节，声明数超过剩余一半就是伪造。
        if (elements > (buf.length - q) / 2) {
            return fail();
        }

        // 键的位置不允许数组（process_nested_data 的 var_hash == null 分支）。
        if (keyMode) {
            return fail();
        }

        PhpJson.Array array = new PhpJson.Array();

        // 容器在开始解析内容前就写入自己的 slot（PHP push 的是 zval 指针，
        // 进行中的容器能被 R: 观察到 —— *RECURSION* 语义）。本值的 slot 就是
        // parseOne 刚 push 的最后一个表项；此时还没有任何子值入表。
        // （keyMode 在上方已提前失败，不会走到这里。）
        varTable.set(varTable.size() - 1, array);

        for (long i = 0; i < elements; i++) {
            Object key = parseOne(true);

            if (!(key instanceof Long) && !(key instanceof String)) {
                return fail();
            }

            Object value = parseOne();

            // 值的最后一个字节必须是 ';' 或 '}'，否则回退一格再失败
            // （对应 process_nested_array_data 的 *(*p-1) 检查）。
            if (elements > 0 && p > 0) {
                int last = at(p - 1);

                if (last != ';' && last != '}') {
                    p--;

                    return fail();
                }
            }

            array.put(PhpJson.normalizeKey(key), value);
        }

        if (at(p) != '}') {
            return fail();
        }

        p++;

        return array;
    }

    /** {@code O:<len>:"<class>":<n>:{...}} 与 {@code C:<len>:"<class>":<len>:{...}}。 */
    private Object parseObject(int start, boolean keyMode) {
        boolean custom = at(start) == 'C';
        int q = p + 2;

        if (!isDigit(at(q))) {
            return fail();
        }

        int lenStart = q;
        int len = 0;

        while (isDigit(at(q))) {
            len = len * 10 + (at(q) - '0');
            q++;
        }

        if (len == 0) {
            return fail();
        }

        int maxlen = buf.length - q;

        if (maxlen < len) {
            p = lenStart;

            return fail();
        }

        if (at(q) != ':' || at(q + 1) != '"') {
            return fail();
        }

        q += 2;

        maxlen = buf.length - q;
        int nameStart = q;

        if (at(nameStart) == 0 || at(nameStart) == '\\') {
            return fail();
        }

        q = nameStart + len;

        if (at(q) != '"') {
            p = q;

            return fail();
        }

        if (at(q + 1) != ':') {
            p = q + 1;

            return fail();
        }

        String className = new String(buf, nameStart, len, StandardCharsets.UTF_8);

        // 类解析（原码 zend_lookup_class / allowed_classes 链）：本应用不加载用户
        // 自定义类，只有内置类型算「已定义」；其余一律退化成 __PHP_Incomplete_Class，
        // 退化成 __PHP_Incomplete_Class，并把原始类名写进保留成员
        // __PHP_Incomplete_Class_Name（PHP 侧该成员插在最前）。
        boolean incomplete = !KNOWN_CLASSES.contains(className);
        String resolvedName = incomplete ? "__PHP_Incomplete_Class" : className;

        q++;

        if (custom) {
            return parseCustomObject(start, resolvedName, className, q, keyMode);
        }

        if (keyMode) {
            // 键的位置不允许对象：offset 停在对象起点。
            return fail();
        }

        // O: 剩下的是 <n>:{...}，n 是 parse_iv2（可带符号），溢出只报警告。
        int countStart = q + 1;
        int cursor = countStart;

        if (at(cursor) == '-' || at(cursor) == '+') {
            cursor++;
        }

        if (!isDigit(at(cursor))) {
            p = cursor;

            return fail();
        }

        while (isDigit(at(cursor))) {
            cursor++;
        }

        long elements = parseIv(countStart, cursor);

        if (at(cursor) != ':' || at(cursor + 1) != '{') {
            p = cursor;

            return fail();
        }

        if (elements < 0 || elements >= HT_MAX_SIZE) {
            // 溢出饱和值也在这里拦截；offset = 数字串终点（原码此时 p 未动）。
            p = cursor;

            return fail();
        }

        p = cursor + 2;

        // 与数组相同的 IS_FAKE_ELEM_COUNT 检查（process_nested_data）。
        if (elements > (buf.length - p) / 2) {
            return fail();
        }

        // 对象同数组：容器先写入自己的 slot，进行中即可被 R:/r: 观察到
        // （prop-r-self 语料：属性 r:1 指向尚未完成的外层对象 → 递归对象）。
        PhpObject object = new PhpObject(resolvedName, new PhpJson.Array());
        PhpJson.Array props = object.properties();

        if (incomplete) {
            props.put("__PHP_Incomplete_Class_Name", className);
        }

        varTable.set(varTable.size() - 1, object);

        for (long i = 0; i < elements; i++) {
            Object key = parseOne(true);

            if (!(key instanceof Long) && !(key instanceof String)) {
                return fail();
            }

            Object value = parseOne();
            props.put(PhpJson.normalizeKey(key), value);
        }

        if (at(p) != '}') {
            return fail();
        }

        p++;

        return object;
    }

    /**
     * {@code C:} 的载荷是「长度 + {…}」，里面不是序列化结构，只做跳过。
     * 本移植没有 {@code Serializable::unserialize()} 的 Java 对应物，任何 C: 都走
     * 「has no unserializer」的裸 {@code zend_error} 警告（类名按解析结果：已知类用
     * 原名，未知类用 {@code __PHP_Incomplete_Class}）。
     */
    private Object parseCustomObject(int start, String resolvedName, String className, int q, boolean keyMode) {
        if (keyMode) {
            return fail();
        }

        if (at(q) != ':') {
            p = q;

            return fail();
        }

        int lenStart = q + 1;
        int cursor = lenStart;

        if (!isDigit(at(cursor))) {
            p = cursor;

            return fail();
        }

        long payloadLen = 0;

        while (isDigit(at(cursor))) {
            payloadLen = payloadLen * 10 + (at(cursor) - '0');
            cursor++;
        }

        long overflowPayload = parseIv(lenStart, cursor);

        if (at(cursor) != ':') {
            p = cursor;

            return fail();
        }

        if (at(cursor + 1) != '{') {
            p = cursor + 1;

            return fail();
        }

        p = cursor + 2;

        // (max - p) <= datalen → 数据不足：警告带「required / present」，offset 停在 '{' 之后。
        long present = buf.length - p;

        if (present <= payloadLen) {
            warnings.add("Insufficient data for unserializing - " + overflowPayload
                    + " required, " + present + " present");

            return fail();
        }

        if (at((int) (p + payloadLen)) != '}') {
            p += (int) payloadLen;

            return fail();
        }

        p += (int) payloadLen + 1;

        // C: 永远成功（对象已建好）；警告是裸 zend_error（没有 unserialize(): 前缀），
        // 类名按解析结果——未知类是 __PHP_Incomplete_Class，不是 C: 里写的那个。
        warnings.add("Class " + resolvedName + " has no unserializer");

        return new PhpObject(resolvedName, new PhpJson.Array());
    }

    /**
     * {@code R:<id>;} / {@code r:<id>;}。
     *
     * <p>php-src 规则（逐条对应）：</p>
     * <ol>
     *   <li>{@code *p = YYCURSOR}：先推到 {@code ;} 之后（失败报错也停这里）；</li>
     *   <li>键位（var_hash == NULL）→ 失败；</li>
     *   <li>{@code id = parse_uiv(start + 2) - 1}；{@code id == -1}（即 R:0;）
     *       或表外 → 失败；</li>
     *   <li>{@code rval_ref == rval}（r 指到自己刚 push 的那个 slot）→ 失败；
     *       大写 R 没 push 过自己，此条对 R 不可能触发；</li>
     *   <li><b>r 的额外要求</b>：{@code ZVAL_DEREF(rval_ref); Z_TYPE == IS_OBJECT}
     *       ——目标必须是对象；指向数组/标量都失败（这就是 r 在数组元素位置
     *       全军覆没、而 R 决不失败 白名单不对称 的根源）。</li>
     * </ol>
     */
    private Object parseReference(int start, boolean keyMode, boolean lowercase) {
        int q = p + 2;
        int cursor = q;

        if (!isDigit(at(cursor))) {
            return fail();
        }

        while (isDigit(at(cursor))) {
            cursor++;
        }

        if (at(cursor) != ';') {
            return fail();
        }

        // 步骤 1：p 推到 token 之后（与 PHP 的 *p = YYCURSOR 相同）。
        p = cursor + 1;

        // 步骤 2：键位失败（调用键位解析时 var_hash 是 NULL）。
        if (keyMode) {
            return fail();
        }

        long id = parseUivDigits(q, cursor) - 1;

        // 步骤 3：R:0; → id == -1；表外（var_access == NULL）→ 失败。
        if (id == -1 || id >= varTable.size()) {
            return fail();
        }

        Object target = varTable.get((int) id);

        // 步骤 4（仅小写 r）：指到自己刚 push 的 slot。
        if (lowercase && id == varTable.size() - 1) {
            return fail();
        }

        // 步骤 5（仅小写 r）：目标必须是对象。
        if (lowercase && !(target instanceof PhpObject)) {
            return fail();
        }

        // PHP 的 ZVAL_NEW_REF 把目标槽转成引用再 ZVAL_COPY：值级等价于
        // 直接持有同一 PHP zval。Java 侧返回同一对象（容器 = 同一
        // LinkedHashMap，R: 指向进行中容器就是 *RECURSION*）。
        return target;
    }

    /** 解析 {@code q..cursor} 里的无符号数字（R: 的 id；速度不重要）。 */
    private long parseUivDigits(int q, int end) {
        long value = 0;

        for (int i = q; i < end; i++) {
            value = value * 10 + (at(i) - '0');
        }

        return value;
    }

    /** {@code E:<len>:"<Enum>:<Case>";}。不支持枚举，失败；但停点要照抄。 */
    private Object parseEnum(int start) {
        int q = p + 2;
        int lenStart = q;

        if (!isDigit(at(q))) {
            return fail();
        }

        int len = 0;

        while (isDigit(at(q))) {
            len = len * 10 + (at(q) - '0');
            q++;
        }

        if (len == 0) {
            return fail();
        }

        if (at(q) != ':' || at(q + 1) != '"') {
            return fail();
        }

        q += 2;

        if (buf.length - q < len) {
            p = lenStart;

            return fail();
        }

        int nameStart = q;
        q = nameStart + len;

        if (at(q) != '"') {
            p = q;

            return fail();
        }

        if (at(q + 1) != ';') {
            p = q + 1;

            return fail();
        }

        String body = new String(buf, nameStart, len, StandardCharsets.UTF_8);
        int colon = body.indexOf(':');

        if (colon < 0) {
            warnings.add("Invalid enum name '" + body + "' (missing colon)");

            return fail();
        }

        String className = body.substring(0, colon);

        // 对已知内部类型（php_var_unserialize_enum 里 zend_lookup_class 命中但
        // 不是 enum 的情形）报「is not an enum」；真正的自定义不存在类报
        // 「not found」。p 两种情况都不推进（E 的 token 分支失败就地返回）。
        warnings.add("Class '" + className + "' "
                + (KNOWN_INTERNAL_TYPES.contains(className) ? "is not an enum" : "not found"));

        return fail();
    }
}
