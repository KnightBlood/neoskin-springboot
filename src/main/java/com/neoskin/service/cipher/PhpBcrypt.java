package com.neoskin.service.cipher;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.springframework.security.crypto.bcrypt.BCrypt;

/**
 * PHP bcrypt 的密码归一化与哈希，<b>bcrypt 专用</b>。
 *
 * <p>这个类存在的唯一理由：{@code crypt(3)} 与 {@code password_hash()} 对「密码」做的
 * 两件事和「把密码当普通字符串」直觉不同，而这套差异只能靠实测确定。
 * 全部结论由 PHP 8.5.12 探针实测（{@code probe-bcrypt72.php} /
 * {@code probe-nul.php} / {@code probe-order.php} / {@code probe-nul-range.php}），
 * 并有语料库 {@code cipher.json} 的 {@code BCRYPT.normalize.*} 逐字节钉死。
 *
 * <h2>归一化规则（顺序不可颠倒）</h2>
 * <ol>
 *   <li>在<b>第一个 NUL 字节</b>处截断。bcrypt 的 C 实现收到的是 C 字符串，
 *       {@code "ab\0cd"} 与 {@code "ab"} 得到同一个哈希；</li>
 *   <li>剩下的部分按<b>字节</b>截断到 72。Blowfish 密钥调度只吃 72 字节，
 *       超出部分静默丢弃。</li>
 * </ol>
 *
 * <p>第 2 步是<b>按字节</b>而不是按字符：{@code 68 个 'x' + 3 个 '中'}（77 字节）
 * 截断后是 68 个 'x' + 一个完整的 '中' + 一个「中」的首字节，即产生非法 UTF-8。
 * PHP 就是这么干的，实测 {@code crypt()} 与 {@code crypt(substr($p, 0, 72))} 结果相同。
 * 移植时若「贴心地」退回字符边界，就会算出不同的哈希，存量用户直接登不进去。
 * 语料用例 {@code BCRYPT.normalize.splitMultibyte} 专门守这一格。
 *
 * <h2>{@code crypt()} 与 {@code password_hash()} 在 NUL 上的分歧</h2>
 *
 * <p>实测（{@code probe-nul-range.php}）：
 * <ul>
 *   <li>{@code crypt("ab\0cd", $salt)} → 正常返回哈希，等价于 {@code crypt("ab", $salt)}；</li>
 *   <li>{@code password_hash("ab\0cd", PASSWORD_BCRYPT)} → 抛
 *       {@code ValueError: Bcrypt password must not contain null character}。</li>
 * </ul>
 *
 * <p>所以 {@link #hash} 拒绝含 NUL 的输入（与 {@code password_hash()} 一致），
 * 而 {@link #check} 容忍 NUL（与 {@code password_verify()} 一致，它底层就是 {@code crypt}）。
 * 看起来自相矛盾，但源项目的 BCRYPT 驱动恰好同时用到这两条：
 * {@code hash()} 走 {@code password_hash()}，{@code verify()} 走 {@code password_verify()}。
 *
 * <p>顺带一句：NUL 检查覆盖<b>整个</b>密码，不是只看前 72 字节
 * （{@code str_repeat('a', 80) . "\0"} 同样抛错）。
 *
 * <h2>为什么不直接用 Spring 的 {@code BCryptPasswordEncoder}</h2>
 *
 * <p>{@code BCryptPasswordEncoder.encode()} 对超过 72 字节的输入直接抛
 * {@code IllegalArgumentException("password cannot be more than 72 bytes")}，
 * 而 PHP 是静默截断。{@code oauth_clients.secret} 里存的 argon2 哈希串有 97 字节，
 * 迁移脚本把它们重新哈希成 bcrypt 时正好踩到这条差异。同时
 * {@code BCrypt.hashpw(byte[], String)} 本身就接受字节数组，绕过编码问题，
 * 所以这里直接用底层 API。
 */
public final class PhpBcrypt {

    /** Blowfish 密钥调度只取前 72 字节。 */
    public static final int MAX_PASSWORD_BYTES = 72;

    /** bcrypt 允许的 cost 区间（{@code password_hash()} 的硬校验）。 */
    public static final int MIN_COST = 4;
    public static final int MAX_COST = 31;

    /** PHP 产出并接受的现代前缀。 */
    private static final String PREFIX = "$2y";

    private PhpBcrypt() {
    }

    /**
     * 按 PHP 的规则把密码归一化成字节序列。
     *
     * @param value 明文，{@code null} 视为空串
     * @return 长度不超过 72、且不含 NUL 的字节数组
     */
    public static byte[] normalize(String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);

        int end = bytes.length;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                end = i;
                break;
            }
        }
        if (end > MAX_PASSWORD_BYTES) {
            end = MAX_PASSWORD_BYTES;
        }
        return end == bytes.length ? bytes : Arrays.copyOf(bytes, end);
    }

    /** 密码里是否含 NUL 字节（{@code password_hash()} 的拒绝条件）。 */
    public static boolean containsNul(String value) {
        return value != null && value.indexOf('\0') >= 0;
    }

    /**
     * 对应 {@code password_hash($value, PASSWORD_BCRYPT, ['cost' => $cost])}。
     *
     * @param value 明文，{@code null} 视为空串
     * @param cost  成本因子，会被收敛到 4..31（PHP 对越界值抛 {@code ValueError}；
     *              这里收敛是因为成本因子来自配置，配置错不该让登录路径 500）
     * @return {@code $2y$<cost>$<22 字节盐 + 31 字节摘要>}，共 60 字节
     * @throws IllegalArgumentException 密码含 NUL（与 PHP 的 {@code ValueError} 对应）
     */
    public static String hash(String value, int cost) {
        if (containsNul(value)) {
            throw new IllegalArgumentException("Bcrypt password must not contain null character");
        }
        return hashWithSalt(value, BCrypt.gensalt(PREFIX, clampCost(cost)));
    }

    /**
     * 用指定盐生成哈希，等价于 {@code crypt($value, $salt)}。
     *
     * <p>只用于测试与语料对拍：{@code password_hash()} 不接受外部盐。
     * 传入的盐必须形如 {@code $2y$10$<22 字节>}。
     */
    public static String hashWithSalt(String value, String salt) {
        return BCrypt.hashpw(normalize(value), salt);
    }

    /**
     * 对应 {@code password_verify($value, $hash)} 的 bcrypt 分支。
     *
     * <p>底层是 {@code crypt()}，因此容忍 NUL（截断），也容忍超过 72 字节（截断）；
     * 哈希串本身的格式校验由调用方 {@link PhpPasswordVerify} 负责。
     */
    public static boolean check(String value, String hash) {
        return BCrypt.checkpw(normalize(value), hash);
    }

    private static int clampCost(int cost) {
        return Math.min(MAX_COST, Math.max(MIN_COST, cost));
    }
}
