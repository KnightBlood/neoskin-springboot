package com.neoskin.service.cipher;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.security.crypto.bcrypt.BCrypt;

/**
 * PHP {@code password_verify()} 的等价实现，<b>算法无关</b>。
 *
 * <p>为什么需要这个类：源项目 {@code BCRYPT} / {@code ARGON2I} / {@code PHP_PASSWORD_HASH}
 * 三个算法的 {@code verify()} 都直接调用 {@code password_verify()}，而
 * {@code password_verify()} 不检查「当前配置的算法是什么」，它只看<b>哈希串本身</b>的格式。
 * 后果是：把 {@code PWD_METHOD} 从 {@code BCRYPT} 改成 {@code ARGON2I} 之后，库里原有的
 * {@code $2y$} 哈希依然能登录，反之亦然。语料库 {@code cipher.json} 里
 * 24 条 {@code *.cross.*} 用例（全部 {@code expected = true}）就是这条行为的规格。
 *
 * <p>因此这三个 Java 实现必须共用本类的判定，而不能各自按「自己的算法」判断。
 *
 * <h2>bcrypt 的接受条件（全部由 PHP 8.5.12 探针实测，见 {@code PORT-NOTES.md}）</h2>
 * <ul>
 *   <li>总长度必须<b>正好 60 字节</b>；59 / 61 / 62 一律 false；</li>
 *   <li>前缀 {@code $2} + minor + {@code $}，minor 只接受小写 {@code y} / {@code a} / {@code b} / {@code x}；
 *       {@code z}、大写、数字一律 false；</li>
 *   <li>随后两位必须是数字（cost），且 {@code 4 <= cost <= 31}；{@code 00}~{@code 03} 一律 false；</li>
 *   <li>密码先按<b>第一个 NUL 字节</b>截断，再按<b>字节</b>截断到 72（可能劈开多字节字符）。
 *       归一化由 {@link PhpBcrypt#normalize(String)} 统一实现，规则由 PHP 8.5.12 实测确定
 *       （{@code "ab\0cd"} 与 {@code "ab"} 验通同一个哈希）。</li>
 * </ul>
 *
 * <p><b>为什么不用 {@code BCryptPasswordEncoder#matches}</b>：它的
 * {@code BCRYPT_PATTERN} 是 {@code \A\$2(a|y|b)?\$(\d\d)\$[./0-9A-Za-z]{53}}，
 * 不接受 {@code $2x$}，而 PHP 接受。底层的 {@link BCrypt#checkpw} 反而支持
 * {@code x}（连带实现 sign-extension bug 与 {@code safety=0x10000}），所以直接调它。
 *
 * <h2>argon2 的接受条件</h2>
 * <ul>
 *   <li>只认 {@code $argon2i$} / {@code $argon2id$}；{@code $argon2d$} 一律 false；</li>
 *   <li>{@code v=} 只认 19（Argon2 1.3）与 16（Argon2 1.0，PHP 7.2 之前的产物）；</li>
 *   <li>{@code m} / {@code t} / {@code p} 全部取自哈希串本身，任何一个与计算不符都会 false；</li>
 *   <li>base64 必须<b>无填充</b>：补 {@code =} 或尾部加空格都 false；</li>
 *   <li>输出长度也取自哈希串（PHP 按解码后的字节数计算），不是固定 32 字节。</li>
 * </ul>
 *
 * <h2>与 PHP 的已知差异（均为不可达或更安全）</h2>
 * <ol>
 *   <li>bcrypt cost 大于 31 时本类返回 false，PHP 会真的去算 2^cost 轮（等于永远算不完）。
 *       真实的 {@code password_hash()} 不可能产出这种串（它只接受 4..31）。</li>
 *   <li>argon2 的 {@code m} / {@code t} / {@code p} 有上界（见常量），防止被篡改的哈希串把 JVM 内存打爆。
 *       PHP 没有上界，但同样会被拖死。真实的 PHP 站点只会产出 ini 默认值（{@code 65536 / 4 / 1}）。</li>
 *   <li>{@code password == null} 时本类按空串处理，PHP 会抛 {@code TypeError}。
 *       登录路径上抛异常等于 500，返回 false 更合适。</li>
 * </ol>
 */
public final class PhpPasswordVerify {

    /** PHP 的 {@code password_verify()} 对 bcrypt 的长度硬要求。 */
    private static final int BCRYPT_LENGTH = 60;

    /** PHP 的 {@code PASSWORD_BCRYPT} 合法 cost 区间。 */
    private static final int MIN_COST = 4;
    private static final int MAX_COST = 31;

    /**
     * bcrypt 结构：{@code $2<minor>$<cost>$<53 字节载荷>}。
     *
     * <p>载荷部分不校验字符集——PHP 也不校验，它只做 60 字节的整串比较，
     * 非法字符自然会导致不匹配。这里用 {@code .} + {@code DOTALL} 保证按「任意字节」计数。
     */
    private static final Pattern BCRYPT = Pattern.compile(
            "^\\$2([abxy])\\$([0-9]{2})\\$.{53}$", Pattern.DOTALL);

    /** argon2 结构；base64 段只允许 standard alphabet，从而天然排除 {@code =} 填充。 */
    private static final Pattern ARGON2 = Pattern.compile(
            "^\\$(argon2i|argon2id)\\$v=(\\d+)\\$m=(\\d+),t=(\\d+),p=(\\d+)"
                    + "\\$([A-Za-z0-9+/]+)\\$([A-Za-z0-9+/]+)$");

    /** Argon2 1.3 与 1.0 的版本号。 */
    static final int ARGON2_VERSION_13 = 19;
    static final int ARGON2_VERSION_10 = 16;

    // 防呆上界，见类注释「与 PHP 的已知差异」第 2 条。
    private static final int MAX_ARGON2_MEMORY_KIB = 1 << 20;   // 1 GiB
    private static final int MAX_ARGON2_TIME = 1024;
    private static final int MAX_ARGON2_PARALLELISM = 255;
    private static final int MAX_ARGON2_HASH_BYTES = 1024;

    private PhpPasswordVerify() {
    }

    /**
     * 对应 {@code password_verify($password, $hash)}。
     *
     * @param password 明文密码，{@code null} 视为空串
     * @param hash     数据库里的哈希串
     * @return 是否匹配；任何格式问题都返回 false 而不抛异常
     */
    public static boolean verify(String password, String hash) {
        if (hash == null || hash.isEmpty()) {
            return false;
        }
        String plain = password == null ? "" : password;
        if (hash.startsWith("$2")) {
            return verifyBcrypt(plain, hash);
        }
        if (hash.startsWith("$argon2i$") || hash.startsWith("$argon2id$")) {
            return verifyArgon2(plain, hash);
        }
        // md5 / sha256 / 明文 / 其它算法：password_verify() 一律 false
        return false;
    }

    /** 当前哈希串看起来是不是 bcrypt（仅结构判断，不做计算）。 */
    public static boolean looksLikeBcrypt(String hash) {
        return hash != null && BCRYPT.matcher(hash).matches();
    }

    // ------------------------------------------------------------------ bcrypt

    private static boolean verifyBcrypt(String password, String hash) {
        if (hash.length() != BCRYPT_LENGTH) {
            return false;
        }
        Matcher m = BCRYPT.matcher(hash);
        if (!m.matches()) {
            return false;
        }
        int cost = Integer.parseInt(m.group(2));
        // PHP 对 00~03 直接 false；>31 本类主动 false（见类注释差异 1）
        if (cost < MIN_COST || cost > MAX_COST) {
            return false;
        }
        try {
            // checkpw 内部走 hashpw(for_check=true)：不做 72 字节上限检查、不做 cost 范围检查，
            // 并对 $2x$ 复刻 sign-extension bug、对 $2a$ 复刻 safety=0x10000，与 PHP 的 crypt 一致。
            // 密码先按 PHP 的规则归一化（NUL 截断 → 72 字节截断），见 PhpBcrypt。
            return BCrypt.checkpw(PhpBcrypt.normalize(password), hash);
        } catch (IllegalArgumentException e) {
            // 载荷段含非法 base64 字符等 → PHP 的 crypt 会返回 "*0"，即不匹配
            return false;
        }
    }

    // ------------------------------------------------------------------ argon2

    private static boolean verifyArgon2(String password, String hash) {
        Matcher m = ARGON2.matcher(hash);
        if (!m.matches()) {
            return false;
        }
        boolean id = "argon2id".equals(m.group(1));
        int version;
        switch (m.group(2)) {
            case "19" -> version = ARGON2_VERSION_13;
            case "16" -> version = ARGON2_VERSION_10;
            default -> {
                return false;
            }
        }
        int memory = parseIntOr(m.group(3), -1);
        int time = parseIntOr(m.group(4), -1);
        int parallelism = parseIntOr(m.group(5), -1);
        if (memory < 0 || time < 0 || parallelism < 0) {
            return false;
        }
        // libargon2 的硬性约束
        if (parallelism < 1 || parallelism > MAX_ARGON2_PARALLELISM) {
            return false;
        }
        if (memory < 8 * parallelism || memory > MAX_ARGON2_MEMORY_KIB) {
            return false;
        }
        if (time < 1 || time > MAX_ARGON2_TIME) {
            return false;
        }
        byte[] salt = decodePhpBase64(m.group(6));
        byte[] expected = decodePhpBase64(m.group(7));
        if (salt == null || expected == null) {
            return false;
        }
        if (salt.length < 8 || expected.length < 4 || expected.length > MAX_ARGON2_HASH_BYTES) {
            return false;
        }
        try {
            byte[] actual = Argon2Cipher.rawHash(
                    password, salt, memory, time, parallelism, id, version, expected.length);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            // 参数组合被 BouncyCastle 拒绝（例如 memory 不是 2 的幂）→ PHP 也是不匹配
            return false;
        }
    }

    /**
     * 解码 PHP 的 base64：<b>无填充</b>的 standard alphabet。
     *
     * <p>拒绝 {@code =}（PHP 的 libargon2 解码器不接受填充）和 {@code length % 4 == 1}
     * （任何编码都不可能产生）。
     */
    static byte[] decodePhpBase64(String s) {
        if (s == null || s.isEmpty() || s.indexOf('=') >= 0) {
            return null;
        }
        if (s.length() % 4 == 1) {
            return null;
        }
        int rem = s.length() % 4;
        String padded = rem == 0 ? s : s + "=".repeat(4 - rem);
        try {
            return Base64.getDecoder().decode(padded);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
