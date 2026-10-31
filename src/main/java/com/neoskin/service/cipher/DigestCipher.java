package com.neoskin.service.cipher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 无盐摘要算法，对应 PHP 的 {@code md5()} / {@code hash('sha256', …)} /
 * {@code hash('sha512', …)}，以及 {@code SALTED2*} 系列的双重摘要。
 *
 * <p>PHP 的 {@code hash_equals($hash, $this->hash($password, $salt))} 是「重新计算后
 * 定长比较」，不是 {@code password_verify}，因此这些算法都实现为
 * 「重算 + 常量时间比较」。
 */
public abstract class DigestCipher implements Cipher {

    protected abstract String algorithm();

    protected abstract int passes();

    /**
     * 单重算法返回 {@code digest(value)}；SALTED2 系列返回
     * {@code digest(digest(value) . salt)}，与 PHP 源码逐字一致。
     */
    @Override
    public String hash(String value, String salt) {
        String first = digest(value == null ? "" : value);
        if (passes() == 1) {
            return first;
        }
        return digest(first + (salt == null ? "" : salt));
    }

    @Override
    public boolean verify(String password, String hash, String salt) {
        return constantTimeEquals(hash, hash(password, salt));
    }

    private String digest(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm());
            byte[] out = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 具体算法

    /** {@code md5($value)}。 */
    public static final class Md5 extends DigestCipher {
        @Override protected String algorithm() { return "MD5"; }
        @Override protected int passes() { return 1; }
    }

    /** {@code md5(md5($value) . $salt)}。 */
    public static final class Salted2Md5 extends DigestCipher {
        @Override protected String algorithm() { return "MD5"; }
        @Override protected int passes() { return 2; }
    }

    /** {@code hash('sha256', $value)}。 */
    public static final class Sha256 extends DigestCipher {
        @Override protected String algorithm() { return "SHA-256"; }
        @Override protected int passes() { return 1; }
    }

    /** {@code hash('sha256', hash('sha256', $value) . $salt)}。 */
    public static final class Salted2Sha256 extends DigestCipher {
        @Override protected String algorithm() { return "SHA-256"; }
        @Override protected int passes() { return 2; }
    }

    /** {@code hash('sha512', $value)}。 */
    public static final class Sha512 extends DigestCipher {
        @Override protected String algorithm() { return "SHA-512"; }
        @Override protected int passes() { return 1; }
    }

    /** {@code hash('sha512', hash('sha512', $value) . $salt)}。 */
    public static final class Salted2Sha512 extends DigestCipher {
        @Override protected String algorithm() { return "SHA-512"; }
        @Override protected int passes() { return 2; }
    }
}
