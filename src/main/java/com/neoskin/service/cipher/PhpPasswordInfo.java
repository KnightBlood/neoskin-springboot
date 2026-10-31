package com.neoskin.service.cipher;

/**
 * PHP {@code password_get_info()} 与 {@code password_needs_rehash()} 的等价实现。
 *
 * <p>为什么单独一个类：这两个函数的判定规则和 {@code password_verify()} 完全不同，
 * 混在一起写最容易移植错。
 *
 * <table border="1">
 *   <caption>三者的 bcrypt 判定差异</caption>
 *   <tr><th>函数</th><th>接受的前缀</th><th>长度要求</th><th>cost 范围</th></tr>
 *   <tr><td>{@code password_verify()}</td><td>{@code $2a$} / {@code $2b$} / {@code $2x$} / {@code $2y$}</td><td>正好 60</td><td>4..31</td></tr>
 *   <tr><td>{@code password_get_info()}</td><td>只认 {@code $2y$}</td><td>正好 60</td><td>不检查</td></tr>
 *   <tr><td>{@code password_needs_rehash()}</td><td>只认 {@code $2y$}</td><td>正好 60</td><td>不检查</td></tr>
 * </table>
 *
 * <p>直接后果（源项目 {@code 2026_10_25_000001} 的 OAuth 密钥迁移依赖这条）：
 * 一个 {@code $2a$12$…} 密钥<b>能</b>通过 {@code Hash::check()}，
 * 但 {@code Hash::isHashed()} 会说「不是哈希」，于是被重新哈希成 {@code $2y$10$…}。
 * 这是 PHP 版的实际行为，Java 版必须照做，不能「顺手修好」。
 *
 * <h2>cost 的解析规则（{@code sscanf("%ld")}）</h2>
 *
 * <p>直觉上会以为 {@code password_get_info()} 用「第 4~5 位是两位数字」抽 cost。
 * 实测（{@code conformance/data/hash.json} 的 {@code get_info.costParse.*} 与
 * {@code get_info.structure.*}，共 24 条）它其实是：
 *
 * <pre>
 * $cost = 12;                                    // PASSWORD_BCRYPT 的默认 cost（PHP 8.4+）
 * sscanf(substr($hash, 4), '%ld', $cost);        // C 的整数扫描
 * </pre>
 *
 * <ul>
 *   <li>前导空白（空格 / {@code \t} / {@code \n} / {@code \v} / {@code \f} / {@code \r}）跳过；</li>
 *   <li>允许一个可选的正负号；</li>
 *   <li>遇到第一个非数字字符就停，已读到的数字生效；</li>
 *   <li>一位数字都读不到时<b>保持默认值 12</b>，不报错；</li>
 *   <li>不检查范围（{@code $2y$99$…} → cost 99，{@code $2y$-1$…} → cost -1）；</li>
 *   <li>超出 int64 时按 C 的 {@code strtol} 语义<b>饱和</b>到
 *       {@link Long#MAX_VALUE} / {@link Long#MIN_VALUE}，不回绕；</li>
 *   <li><b>不要求</b>后面跟 {@code $}（{@code $2y$12!…} 的 cost 仍是 12）；</li>
 *   <li>第 6 位是数字时会被一起读进 cost（{@code $2y$120…} → cost 120）。</li>
 * </ul>
 *
 * <p>所以一个「畸形但 60 字节、以 {@code $2y$} 开头」的串可能报告
 * {@code needsRehash = false}，从而被迁移脚本当成「无需重算」跳过。
 * 这看起来是 PHP 的 bug，但它是既定行为，四个迁移项目必须一致。
 *
 * <h2>argon2 的 options 解析</h2>
 *
 * <p>不是整段正则匹配，而是逐段扫描（{@code get_info.argon2Params.*}，共 78 条）：
 *
 * <pre>
 * v= &lt;long&gt; $      失败 → 整体保持 php.ini 默认
 * m= &lt;long&gt;        失败 → 整体保持默认；成功 → 覆盖 memory_cost
 * ,t= &lt;long&gt;       失败 → 停止（memory 已生效，其余默认）
 * ,p= &lt;long&gt;       失败 → 停止（memory/time 已生效，threads 默认）
 * </pre>
 *
 * <p>{@code <long>} 与上面 bcrypt cost 的扫描规则完全相同，因此
 * {@code m=-1} 会得到 {@code memory_cost = -1}（PHP 不做范围校验），
 * {@code m=0x10} 只读到 0，{@code m=1, t=3} 因为多一个空格而读不到 t。
 *
 * <p><b>注意</b>：本类的 argon2 参数解析只服务于 {@code get_info} 的返回值。
 * {@code password_verify()} 走 {@link PhpPasswordVerify}，那边对参数有范围校验
 * （见该类的「与 PHP 的已知差异」）。
 */
public final class PhpPasswordInfo {

    /** 识别成功时的算法标识符，与 PHP 的 {@code algo} 取值一致。 */
    public static final String BCRYPT = "2y";
    public static final String ARGON2I = "argon2i";
    public static final String ARGON2ID = "argon2id";

    /** {@code password_get_info()} 在 cost 段解析失败时回落的默认值。 */
    public static final long BCRYPT_DEFAULT_COST = 12;

    /** {@code password_get_info()} 的 bcrypt 长度硬要求。 */
    public static final int BCRYPT_LENGTH = 60;

    /** argon2 options 的 php.ini 默认值。 */
    public static final long ARGON2_DEFAULT_MEMORY = 65536;
    public static final long ARGON2_DEFAULT_TIME = 4;
    public static final long ARGON2_DEFAULT_THREADS = 1;

    private static final String BCRYPT_PREFIX = "$2y$";

    private PhpPasswordInfo() {
    }

    /**
     * 对应 {@code password_get_info($hash)['algo']}。
     *
     * @return {@code "2y"} / {@code "argon2i"} / {@code "argon2id"}；无法识别返回 {@code null}
     */
    public static String algorithm(String hash) {
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        if (hash.startsWith("$argon2id$")) {
            return ARGON2ID;
        }
        if (hash.startsWith("$argon2i$")) {
            return ARGON2I;
        }
        if (isBcryptShaped(hash)) {
            return BCRYPT;
        }
        return null;
    }

    /**
     * 对应 {@code password_get_info($hash)['algoName']}。
     *
     * @return {@code "bcrypt"} / {@code "argon2i"} / {@code "argon2id"} / {@code "unknown"}
     */
    public static String algorithmName(String hash) {
        String algo = algorithm(hash);
        if (algo == null) {
            return "unknown";
        }
        return BCRYPT.equals(algo) ? "bcrypt" : algo;
    }

    /**
     * {@code password_get_info()} 认不认这个串（即 Laravel 的 {@code Hash::isHashed()}）。
     *
     * <p>bcrypt 的条件只有两个：<b>总长正好 60 字节</b> + <b>以 {@code $2y$} 开头</b>。
     * 载荷内容完全不校验——53 个空格、53 个 NUL、56 个 {@code a} 都算 bcrypt；
     * 反过来 {@code $2a$} / {@code $2b$} / {@code $2x$} / {@code $2Y$} 一律不算。
     */
    public static boolean isHashed(String hash) {
        return algorithm(hash) != null;
    }

    /** bcrypt 的形态判定：长度 60 + 前缀 {@code $2y$}。 */
    private static boolean isBcryptShaped(String hash) {
        return hash.length() == BCRYPT_LENGTH && hash.startsWith(BCRYPT_PREFIX);
    }

    /**
     * 从哈希串里按 PHP 的规则抽取 bcrypt cost。
     *
     * <p>就是 {@code sscanf(substr($hash, 4), '%ld', $cost)}，失败保持默认 12。
     * 只有 {@link #isBcryptShaped(String)} 为真时才应该调用（PHP 对非 bcrypt 不会走到这一步）。
     */
    public static long bcryptCost(String hash) {
        if (hash == null) {
            return BCRYPT_DEFAULT_COST;
        }
        long[] value = scanLong(hash, 4);
        return value == null ? BCRYPT_DEFAULT_COST : value[0];
    }

    /**
     * 对应 {@code password_needs_rehash($hash, PASSWORD_BCRYPT, ['cost' => $cost])}。
     *
     * <p>判定：只有「{@code password_get_info()} 认成 bcrypt」且「抽出的 cost 等于目标」
     * 才返回 false；其余（明文、argon2、{@code $2a$}、长度不对）一律 true。
     *
     * @param hash 数据库里的值
     * @param cost 目标成本因子（Laravel 的 {@code BCRYPT_ROUNDS}，默认 10）
     */
    public static boolean needsRehash(String hash, long cost) {
        if (!isBcryptShaped(hash == null ? "" : hash)) {
            return true;
        }
        return bcryptCost(hash) != cost;
    }

    /**
     * argon2 的参数段解析结果。
     *
     * @param memoryCost {@code memory_cost}（KiB）
     * @param timeCost   {@code time_cost}
     * @param threads    {@code threads}
     */
    public record Argon2Options(long memoryCost, long timeCost, long threads) {
    }

    /**
     * 对应 {@code password_get_info($hash)['options']} 里的 argon2 部分。
     *
     * <p>调用前应先确认 {@link #algorithm(String)} 是 argon2 系列；否则返回默认值。
     */
    public static Argon2Options argon2Options(String hash) {
        long memory = ARGON2_DEFAULT_MEMORY;
        long time = ARGON2_DEFAULT_TIME;
        long threads = ARGON2_DEFAULT_THREADS;
        if (hash == null) {
            return new Argon2Options(memory, time, threads);
        }
        int i;
        if (hash.startsWith("$argon2id$")) {
            i = "$argon2id$".length();
        } else if (hash.startsWith("$argon2i$")) {
            i = "$argon2i$".length();
        } else {
            return new Argon2Options(memory, time, threads);
        }

        // v= <long> $
        if (!hash.startsWith("v=", i)) {
            return new Argon2Options(memory, time, threads);
        }
        i += 2;
        long[] version = scanLong(hash, i);
        if (version == null) {
            return new Argon2Options(memory, time, threads);
        }
        i = (int) version[1];
        if (!hash.startsWith("$", i)) {
            return new Argon2Options(memory, time, threads);
        }
        i += 1;

        // m= <long>
        if (!hash.startsWith("m=", i)) {
            return new Argon2Options(memory, time, threads);
        }
        i += 2;
        long[] mem = scanLong(hash, i);
        if (mem == null) {
            return new Argon2Options(memory, time, threads);
        }
        memory = mem[0];
        i = (int) mem[1];

        // ,t= <long>
        if (!hash.startsWith(",t=", i)) {
            return new Argon2Options(memory, time, threads);
        }
        i += 3;
        long[] t = scanLong(hash, i);
        if (t == null) {
            return new Argon2Options(memory, time, threads);
        }
        time = t[0];
        i = (int) t[1];

        // ,p= <long>
        if (!hash.startsWith(",p=", i)) {
            return new Argon2Options(memory, time, threads);
        }
        i += 3;
        long[] p = scanLong(hash, i);
        if (p == null) {
            return new Argon2Options(memory, time, threads);
        }
        threads = p[0];

        return new Argon2Options(memory, time, threads);
    }

    /**
     * 复刻 C 的 {@code strtol} / {@code sscanf("%ld")} 首次转换。
     *
     * <p>语义（与 {@code probe-verify18.php} / {@code probe-verify22.php} 逐条对照过）：
     * <ol>
     *   <li>跳过前导空白 {@code ' ' \t \n \v \f \r}；</li>
     *   <li>可选一个 {@code +} 或 {@code -}；</li>
     *   <li>读十进制数字，遇到非数字即停；</li>
     *   <li>溢出按 {@code strtol} 饱和（正 → {@link Long#MAX_VALUE}，负 → {@link Long#MIN_VALUE}）。</li>
     * </ol>
     *
     * @return {@code [值, 结束下标]}；一位数字都没读到时返回 {@code null}（此时 C 侧保持原值）
     */
    static long[] scanLong(String s, int from) {
        int i = from;
        int n = s.length();
        while (i < n && isSpace(s.charAt(i))) {
            i++;
        }
        boolean negative = false;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            negative = s.charAt(i) == '-';
            i++;
        }
        int digitsStart = i;
        long value = 0;
        boolean overflow = false;
        while (i < n) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            int digit = c - '0';
            if (!overflow) {
                // 用负数域累加，才能覆盖 |Long.MIN_VALUE| 这个不对称边界
                if (value < (Long.MIN_VALUE + digit) / 10) {
                    overflow = true;
                } else {
                    value = value * 10 - digit;
                }
            }
            i++;
        }
        if (i == digitsStart) {
            return null;
        }
        long result;
        if (overflow) {
            result = negative ? Long.MIN_VALUE : Long.MAX_VALUE;
        } else if (negative) {
            result = value;
        } else if (value == Long.MIN_VALUE) {
            // 绝对值 9223372036854775808 只能作为 Long.MIN_VALUE 表示
            result = Long.MAX_VALUE;
        } else {
            result = -value;
        }
        return new long[] {result, i};
    }

    /** C 的 {@code isspace()}，只认 ASCII 的 6 个空白字符。 */
    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
    }
}
