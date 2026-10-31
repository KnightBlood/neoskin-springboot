package com.neoskin.service.cipher;

/**
 * 密码算法抽象，对应源项目 {@code App\Services\Cipher\BaseCipher}。
 *
 * <p>源项目的密码算法由 {@code PWD_METHOD} 环境变量选择（见 {@code config/secure.php}），
 * 为了让 Java 版能直接接管 PHP 版已有的数据库，这里必须逐字节复刻 PHP 的行为：
 * <ul>
 *   <li>{@code BCRYPT} / {@code ARGON2I} / {@code PHP_PASSWORD_HASH} 走
 *       {@code password_hash()}，输出 {@code $2y$} / {@code $argon2i$} 等标准格式；</li>
 *   <li>{@code MD5} / {@code SHA256} / {@code SHA512} 是无盐十六进制摘要；</li>
 *   <li>{@code SALTED2*} 是 {@code hash(hash(value) . salt)} 的双重摘要。</li>
 * </ul>
 */
public interface Cipher {

    /** 生成密码哈希。{@code salt} 仅对 SALTED2* 系列生效。 */
    String hash(String value, String salt);

    /** 校验明文密码与哈希是否匹配。 */
    boolean verify(String password, String hash, String salt);
}
