package com.neoskin.oauth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.neoskin.support.PhpJson;

/**
 * JWT 的最小移植：RS256（access token）与 HS256（Passport 的 transient cookie）。
 */
public final class Jwts {

    private Jwts() {
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] unb64(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    /** RS256 编码（带 kid 便于外部验证）。 */
    public static String rs256(Map<String, Object> claims, OAuthKeys keys) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("typ", "JWT");
        header.put("alg", "RS256");
        String head = b64(PhpJson.encode(header, 0).getBytes(StandardCharsets.UTF_8));
        String body = b64(PhpJson.encode(claims, 0).getBytes(StandardCharsets.UTF_8));
        byte[] signingInput = (head + "." + body).getBytes(StandardCharsets.UTF_8);

        return head + "." + body + "." + b64(keys.sign(signingInput));
    }

    /** HS256 编码。 */
    public static String hs256(Map<String, Object> claims, byte[] key) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("typ", "JWT");
        header.put("alg", "HS256");
        String head = b64(PhpJson.encode(header, 0).getBytes(StandardCharsets.UTF_8));
        String body = b64(PhpJson.encode(claims, 0).getBytes(StandardCharsets.UTF_8));
        String input = head + "." + body;

        return input + "." + b64(hmac(input.getBytes(StandardCharsets.UTF_8), key));
    }

    /**
     * 校验并解析 JWT。签名/格式错误返回 null；exp/nbf 由调用方检查（返回 claims 供判断）。
     */
    public static Map<String, Object> decodeRs256(String token, OAuthKeys keys) {
        String[] parts = split(token);
        if (parts == null) {
            return null;
        }
        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);
        if (!keys.verify(signingInput, unb64(parts[2]))) {
            return null;
        }
        Object decoded = PhpJson.decode(new String(unb64(parts[1]), StandardCharsets.UTF_8));

        return decoded instanceof Map<?, ?> map ? copy(map) : null;
    }

    public static Map<String, Object> decodeHs256(String token, byte[] key) {
        String[] parts = split(token);
        if (parts == null) {
            return null;
        }
        byte[] expected = hmac((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8), key);
        if (!constantTimeEquals(expected, unb64(parts[2]))) {
            return null;
        }
        Object decoded = PhpJson.decode(new String(unb64(parts[1]), StandardCharsets.UTF_8));

        return decoded instanceof Map<?, ?> map ? copy(map) : null;
    }

    private static String[] split(String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }

        return parts;
    }

    private static byte[] hmac(byte[] data, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));

            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }

        return diff == 0;
    }

    private static Map<String, Object> copy(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, value) -> out.put(String.valueOf(key), value));

        return out;
    }
}
