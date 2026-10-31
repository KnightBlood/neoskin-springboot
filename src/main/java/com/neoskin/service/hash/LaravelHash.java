package com.neoskin.service.hash;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.neoskin.service.cipher.PhpBcrypt;
import com.neoskin.service.cipher.PhpPasswordInfo;
import com.neoskin.service.cipher.PhpPasswordVerify;

/**
 * Laravel {@code Hash} 门面的等价实现（bcrypt 驱动）。
 *
 * <p>这个类只服务于 {@code oauth_clients.secret}，<b>不要</b>用它处理用户密码。
 * 用户密码走 {@link com.neoskin.service.cipher.Cipher}，两者的成本因子不同，
 * 详见 {@link com.neoskin.config.NeoSkinSettings.Hashing} 的说明。
 *
 * <p>与 PHP 的对应关系：
 * <ul>
 *   <li>{@code Hash::make($v)} → {@code password_hash($v, PASSWORD_BCRYPT, ['cost' => $rounds])}</li>
 *   <li>{@code Hash::check($v, $hash)} → {@code password_verify($v, $hash)}</li>
 *   <li>{@code Hash::isHashed($v)} → {@code password_get_info($v)['algo'] !== null}</li>
 *   <li>{@code Hash::needsRehash($v)} → {@code password_needs_rehash($v, PASSWORD_BCRYPT, ['cost' => $rounds])}</li>
 * </ul>
 *
 * <p>为什么不用 {@link BCryptPasswordEncoder#upgradeEncoding(String)} 来实现
 * {@code needsRehash}？因为 Spring 的实现只要「哈希的 cost 与配置不同」就返回 true，
 * 而 PHP 的 {@code password_needs_rehash()} 还有一层算法判断：只要算法不是
 * {@code PASSWORD_BCRYPT}（例如 {@code $argon2id$}、{@code $2a$}、纯文本）就返回 true，
 * 只有算法相同且 cost 相同才返回 false。这里的差异会直接影响迁移脚本要不要重写该行，
 * 因此按 PHP 的判定逻辑逐字实现，见 {@link #needsRehash(String)}。
 */
public final class LaravelHash {

    /** PHP 的 {@code PASSWORD_BCRYPT} 允许的 cost 范围（只用于 {@code make()}）。 */
    private static final int MIN_COST = 4;
    private static final int MAX_COST = 31;

    private final int rounds;

    public LaravelHash(int rounds) {
        this.rounds = clamp(rounds);
    }

    /**
     * 对应 {@code Hash::make($value)}，产出 {@code $2y$<cost>$…}。
     *
     * <p>走 {@link PhpBcrypt#hash} 而不是 {@code BCryptPasswordEncoder.encode()}：
     * 后者对超过 72 字节的输入直接抛 {@code IllegalArgumentException}，
     * 而 PHP 的 {@code password_hash()} 是静默截断。{@code oauth_clients.secret}
     * 里存着 97 字节的 argon2 哈希串，迁移时正好会踩到这条差异。
     */
    public String make(String value) {
        return PhpBcrypt.hash(value, rounds);
    }

    /**
     * 对应 {@code Hash::check($value, $hash)}。
     *
     * <p>Laravel 的 {@code AbstractHasher::check()} 只有两步：{@code null} / 空串直接 false，
     * 否则 {@code password_verify()}。所以这里必须委托 {@link PhpPasswordVerify}
     * 而不是 {@link BCryptPasswordEncoder#matches} —— 后者不接受 {@code $2x$}。
     */
    public boolean check(String value, String hash) {
        if (hash == null || hash.isEmpty()) {
            return false;
        }
        return PhpPasswordVerify.verify(value, hash);
    }

    /**
     * 对应 {@code Hash::isHashed($value)}：{@code password_get_info()['algo'] !== null}。
     *
     * <p>规则见 {@link PhpPasswordInfo}：bcrypt 只认 {@code $2y$} + 60 字节，
     * 载荷内容不校验；argon2 只看前缀。{@code $2a$} / {@code $2b$} / {@code $2x$}
     * <b>不算</b>已哈希——它们能通过 {@code check()}，但会被 {@code needsRehash()} 判定为
     * 需要重算，这是 PHP 版的真实行为。
     */
    public static boolean isHashed(String value) {
        return PhpPasswordInfo.isHashed(value);
    }

    /**
     * 对应 {@code Hash::needsRehash($value)}，即
     * {@code password_needs_rehash($value, PASSWORD_BCRYPT, ['cost' => $rounds])}。
     *
     * <p>判定顺序（与 PHP 一致）：
     * <ol>
     *   <li>不是 {@code $2y$} 的 60 字节 bcrypt → true（包括明文、argon2、{@code $2a$}）；</li>
     *   <li>是 bcrypt 但 cost 与配置不同 → true；</li>
     *   <li>否则 false。</li>
     * </ol>
     *
     * <p>cost 按 PHP 的 {@code sscanf("%ld")} 规则抽取，因此
     * {@code $2y$1a$…} 的 cost 是 1、{@code $2y$12!…} 的 cost 是 12
     * —— 详见 {@link PhpPasswordInfo#bcryptCost(String)}。
     */
    public boolean needsRehash(String value) {
        return PhpPasswordInfo.needsRehash(value, rounds);
    }

    /** 从 {@code $2y$<cost>$…} 里取出 cost（PHP 的 {@code sscanf} 语义）。 */
    static long bcryptCost(String hash) {
        return PhpPasswordInfo.bcryptCost(hash);
    }

    /** 配置里的成本因子，已按 PHP 的合法范围收敛。 */
    public int rounds() {
        return rounds;
    }

    /** 当前实现是否使用 bcrypt（用于日志/自检）。 */
    public String driver() {
        return "bcrypt";
    }

    @Override
    public String toString() {
        return "LaravelHash[bcrypt, cost=" + rounds + "]";
    }

    private static int clamp(int rounds) {
        if (rounds < MIN_COST) {
            return MIN_COST;
        }
        return Math.min(rounds, MAX_COST);
    }
}
