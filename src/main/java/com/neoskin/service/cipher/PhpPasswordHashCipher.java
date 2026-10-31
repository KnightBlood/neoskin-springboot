package com.neoskin.service.cipher;

/**
 * 对应 PHP 的 {@code password_hash($value, PASSWORD_DEFAULT)} /
 * {@code password_verify()}，即源项目的 {@code PHP_PASSWORD_HASH} 算法。
 *
 * <p>PHP 8.5 的 {@code PASSWORD_DEFAULT} 仍是 bcrypt，因此 <b>新哈希</b>按 bcrypt 生成；
 * 但 {@code password_verify()} 本身不关心算法，只要格式合法就会校验，所以
 * <b>历史哈希</b>可能是 {@code $argon2i$} / {@code $argon2id$}。这里两种都接受，
 * 否则从 PHP 版迁移过来的用户会突然登不进去。
 *
 * <p>本类的 {@link #verify} 与 {@link BcryptCipher} / {@link Argon2Cipher} 是<b>同一份</b>
 * 判定逻辑（都委托 {@link PhpPasswordVerify}），因为源项目里这三者的 {@code verify()}
 * 写的都是 {@code password_verify()}，行为完全一致。区别只在 {@link #hash}。
 */
public final class PhpPasswordHashCipher implements Cipher {

    private final BcryptCipher bcrypt;

    public PhpPasswordHashCipher(int rounds) {
        this.bcrypt = new BcryptCipher(rounds);
    }

    @Override
    public String hash(String value, String salt) {
        // PASSWORD_DEFAULT === PASSWORD_BCRYPT（PHP 8.5）
        return bcrypt.hash(value, salt);
    }

    @Override
    public boolean verify(String password, String hash, String salt) {
        // PHP_PASSWORD_HASH::verify() === password_verify()
        return PhpPasswordVerify.verify(password, hash);
    }
}
