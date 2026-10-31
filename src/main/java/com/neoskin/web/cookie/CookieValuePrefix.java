package com.neoskin.web.cookie;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Laravel {@code Illuminate\Cookie\CookieValuePrefix} 的移植。
 *
 * <p>formula：{@code hash_hmac('sha1', $name . 'v2', $key) . '|'} —— 40 个小写
 * 十六进制字符加一个 {@code |}，共 41 字节（{@code prefix_length}）。密钥是
 * <b>原始字节</b>（APP_KEY base64 解码之后的那一串）。
 *
 * <p>这个前缀把「该值是本应用加密过」的信息带进密文之外：密钥轮换时只要前缀
 * 对得上就说明值确实出自自己人，{@link #remove} 再把前缀剥掉。
 *
 * <p>语料：{@code conformance/data/cookie.json} 的 {@code prefix_create.*} /
 * {@code prefix_remove.*} / {@code prefix_validate.*}。
 */
public final class CookieValuePrefix {

    private CookieValuePrefix() {
    }

    /** {@code create()}：{@code hash_hmac('sha1', name.'v2', key) . '|'}。 */
    public static String create(String cookieName, byte[] key) {
        return hmacSha1Hex(cookieName + "v2", key) + "|";
    }

    /** {@code remove()}：PHP 的 {@code substr($value, 41)} —— 不足 41 字节直接没了。 */
    public static String remove(String cookieValue) {
        return cookieValue.length() > 41 ? cookieValue.substring(41) : "";
    }

    /**
     * 前缀能对上就剥掉并返回 {@code null} 以外的值，否则 {@code null}（PHP 的
     * {@code return null} 隐式语义）。密钥轮换时任何一个 key 对上都算合法。
     */
    public static String validate(String cookieName, String cookieValue, List<byte[]> keys) {
        for (byte[] key : keys) {
            if (cookieValue.startsWith(create(cookieName, key))) {
                return remove(cookieValue);
            }
        }

        return null;
    }

    private static String hmacSha1Hex(String data, byte[] key) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA1");
            hmac.init(new SecretKeySpec(key, "HmacSHA1"));

            return hexOf(hmac.doFinal(data.getBytes(UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hexOf(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);

        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }
}
