package com.neoskin.service.cipher;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.neoskin.support.ErrorException;
import com.neoskin.support.PhpJson;
import com.neoskin.support.PhpSerialize;
import com.neoskin.support.PhpUnserialize;

/**
 * Laravel {@code Illuminate\Encryption\Encrypter} 的移植（13.x 版语义）。
 *
 * <p>语料：{@code conformance/data/cookie.json} 的 {@code constructor.*} /
 * {@code encrypt.*} / {@code decrypt.*} 用例；生成器 {@code gen-cookie.php}
 * 用的是容器里的真实 PHP 类，期望值没有「脚本另抄一份 HMAC」的同错风险。
 *
 * <h2>与 Laravel 12 之前的关键差别</h2>
 *
 * <ul>
 *   <li>MAC 直接用<b>原始密钥字节</b>算，没有 PBKDF2「钥匙盒」派生：
 *       {@code hash_hmac('sha256', $ivB64 . $valueB64, $key)}——两段都是
 *       <b>base64 字符串</b>，拼接的是文本而不是原始字节；</li>
 *   <li>AES-GCM 的 IV 是 <b>12</b> 字节（CBC 是 16），tag 16 字节进 payload
 *       的 {@code tag} 字段，{@code mac} 为空串；</li>
 *   <li>{@code openssl_encrypt} 调用没传 {@code OPENSSL_RAW_DATA}（flags=0）——
 *       CBC 用 PKCS7 padding，且输入输出都按 base64 处理，所以 payload 的
 *       {@code value} 本身就是 base64 字符串。</li>
 * </ul>
 *
 * <p>提供「注入 IV」的入口是因为 {@code encrypt()} 内部取随机 IV，语料从外部喂
 * 固定 {@code iv_b64} 才能逐字节复核。
 */
public final class PhpEncrypter {

    /** {@code Illuminate\Contracts\Encryption\DecryptException}。 */
    public static final class DecryptException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DecryptException(String message) {
            super(message);
        }
    }

    /** {@code Illuminate\Contracts\Encryption\EncryptException}。 */
    public static final class EncryptException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public EncryptException(String message) {
            super(message);
        }
    }

    /** 算法规格：密钥字节数、是否 AEAD、IV 字节数。 */
    private record Spec(int keySize, boolean aead, int ivSize) {
    }

    private static final Map<String, Spec> SUPPORTED = Map.of(
            "aes-128-cbc", new Spec(16, false, 16),
            "aes-256-cbc", new Spec(32, false, 16),
            "aes-128-gcm", new Spec(16, true, 12),
            "aes-256-gcm", new Spec(32, true, 12));

    /** 报错信息里的算法规格清单顺序必须和 PHP 的 {@code array_keys} 一致。 */
    private static final String[] CIPHER_NAMES =
            {"aes-128-cbc", "aes-256-cbc", "aes-128-gcm", "aes-256-gcm"};

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;

    private final Spec spec;

    private final List<byte[]> previousKeys = new ArrayList<>();

    /** GCM 加密时 {@code openssl_encrypt} 的 tag out 参数。 */
    private byte[] lastTag;

    public PhpEncrypter(byte[] key, String cipher) {
        this.key = key;
        this.spec = SUPPORTED.get(cipher.toLowerCase());

        if (!supported(key, cipher)) {
            throw new RuntimeException("Unsupported cipher or incorrect key length. Supported ciphers are: "
                    + String.join(", ", CIPHER_NAMES) + ".");
        }
    }

    /** {@code Encrypter::supported()}：密钥字节数必须正好等于算法规格。 */
    public static boolean supported(byte[] key, String cipher) {
        Spec spec = SUPPORTED.get(cipher.toLowerCase());

        return spec != null && key.length == spec.keySize();
    }

    /** {@code getKey()}。 */
    public byte[] getKey() {
        return key;
    }

    /** {@code getAllKeys()}：主密钥在前，轮换密钥在后。 */
    public List<byte[]> getAllKeys() {
        List<byte[]> keys = new ArrayList<>();
        keys.add(key);
        keys.addAll(previousKeys);

        return keys;
    }

    /** {@code Encrypter::previousKeys()}：轮换密钥由新到旧排序。 */
    public void setPreviousKeys(byte[]... keys) {
        previousKeys.clear();
        previousKeys.addAll(List.of(keys));
    }

    // ================================================================== 加密

    /** 等价于 {@code encrypt($value, $serialize)}。 */
    public String encrypt(Object value, boolean serialize) {
        byte[] iv = new byte[spec.ivSize()];
        RANDOM.nextBytes(iv);

        return encryptWithIv(iv, value, serialize);
    }

    /**
     * 带固定 IV 的加密（语料入口；语义与 {@link #encrypt} 完全一致）。
     *
     * <p>{@code serialize=false} 时 {@code value} 必须是 {@link String}，
     * 直接按 UTF-8 字节加密；{@code serialize=true} 时先过 {@link PhpSerialize}。</p>
     */
    public String encryptWithIv(byte[] iv, Object value, boolean serialize) {
        String plain = serialize ? PhpSerialize.serialize(value) : (String) value;

        byte[] tag = null;
        byte[] cipherText;

        try {
            Cipher cipher = Cipher.getInstance(spec.aead() ? "AES/GCM/NoPadding" : "AES/CBC/PKCS5Padding");

            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), gcmOrIvParameter(iv));
            cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            if (spec.aead()) {
                int body = cipherText.length - 16;
                tag = new byte[16];
                System.arraycopy(cipherText, body, tag, 0, 16);
                cipherText = copyOf(cipherText, body);
            }
        } catch (Exception e) {
            throw new EncryptException("Could not encrypt the data.");
        }

        String ivB64 = base64(iv);
        String valueB64 = base64(cipherText);
        String mac = spec.aead() ? "" : mac(ivB64, valueB64, key);

        return base64((payload(ivB64, valueB64, mac, base64(tag == null ? new byte[0] : tag)))
                .getBytes(StandardCharsets.ISO_8859_1));
    }

    // ================================================================== 解密

    /** 等价于 {@code decrypt($payload, $unserialize)}；失败按 Laravel 逐条抛 {@link DecryptException}。 */
    public Object decrypt(String payload, boolean unserialize) {
        PhpJson.Array parsed = getJsonPayload(payload);
        byte[] iv = strictBase64(text(parsed, "iv"));
        Object rawTag = parsed.get("tag");

        byte[] tag = rawTag instanceof String s && !s.isEmpty() ? strictBase64(s) : null;

        ensureTagIsValid(tag);

        String valueB64 = text(parsed, "value");
        String mac = spec.aead() ? "" : mac(ivB64(text(parsed, "iv")), valueB64, key);

        byte[] validKey = null;

        if (shouldValidateMac()) {
            for (byte[] candidate : getAllKeys()) {
                if (mac(ivB64(text(parsed, "iv")), valueB64, candidate).equals(text(parsed, "mac"))) {
                    validKey = candidate;
                    break;
                }
            }

            if (validKey == null) {
                throw new DecryptException("The MAC is invalid.");
            }
        } else {
            validKey = null;
        }

        byte[] plain = null;

        for (byte[] candidate : getAllKeys()) {
            if (validKey != null && candidate != validKey) {
                continue;
            }

            try {
                plain = cipherDecrypt(Base64.getDecoder().decode(valueB64), candidate, iv, tag);
                break;
            } catch (Exception e) {
                // openssl_decrypt 返回 false → 'Could not decrypt the data.'
            }
        }

        if (plain == null) {
            throw new DecryptException("Could not decrypt the data.");
        }

        String decryptedText = new String(plain, StandardCharsets.UTF_8);

        if (!unserialize) {
            return decryptedText;
        }

        return unserializeOrThrow(decryptedText);
    }

    /** {@code unserialize($decrypted)}；warning 由 Laravel 的处理器转成 ErrorException。 */
    private static Object unserializeOrThrow(String s) {
        PhpUnserialize.Result result = PhpUnserialize.parse(s);

        if (!result.warnings().isEmpty()) {
            throw new ErrorException("unserialize(): " + result.firstWarning());
        }

        return result.value();
    }

    private PhpJson.Array getJsonPayload(String payload) {
        byte[] bytes = looseBase64(payload);
        PhpJson.Array map = bytes == null ? null : decodeToMap(bytes);

        if (map == null || !validPayload(map)) {
            throw new DecryptException("The payload is invalid.");
        }

        return map;
    }

    /** {@code json_decode()}：坏 JSON 返回 {@code null}（PHP 语义），Java 侧解析器会抛。 */
    private static PhpJson.Array decodeToMap(byte[] bytes) {
        try {
            return PhpJson.decode(new String(bytes, StandardCharsets.UTF_8)) instanceof PhpJson.Array a ? a : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code validPayload()}: iv/value/mac 都必须是非空字符串并且是 string 类型。 */
    private boolean validPayload(PhpJson.Array payload) {
        for (String item : List.of("iv", "value", "mac")) {
            if (!(payload.get(item) instanceof String)) {
                return false;
            }
        }

        Object tag = payload.get("tag");

        if (tag != null && !(tag instanceof String)) {
            return false;
        }

        byte[] iv = strictBase64(text(payload, "iv"));

        return iv != null && iv.length == spec.ivSize();
    }

    // ================================================================== 校验辅助

    private void ensureTagIsValid(byte[] tag) {
        if (spec.aead() && (tag == null || tag.length != 16)) {
            throw new DecryptException("Could not decrypt the data.");
        }

        if (!spec.aead() && tag != null) {
            throw new DecryptException(
                    "Unable to use tag because the cipher algorithm does not support AEAD.");
        }
    }

    private boolean shouldValidateMac() {
        return !spec.aead();
    }

    /** {@code appearsEncrypted()}: PHP 只判「结构、不管类型」——isset() 而非 is_string()。 */
    public static boolean appearsEncrypted(Object value) {
        if (!(value instanceof String s)) {
            return false;
        }

        byte[] decoded = strictBase64(s);

        if (decoded == null) {
            return false;
        }

        PhpJson.Array payload;

        try {
            payload = PhpJson.decode(new String(decoded, StandardCharsets.UTF_8)) instanceof PhpJson.Array a ? a : null;
        } catch (RuntimeException e) {
            payload = null;
        }

        return payload != null
                && payload.containsKey("iv") && payload.get("iv") != null
                && payload.containsKey("value") && payload.get("value") != null
                && payload.containsKey("mac") && payload.get("mac") != null;
    }

    // ================================================================== 工具

    private static String text(PhpJson.Array map, String key) {
        Object v = map.get(key);

        return v instanceof String s ? s : null;
    }

    private static String ivB64(String s) {
        return s == null ? "" : s;
    }

    /** {@code base64_decode($s, true)}：非法序列返回 {@code null}。 */
    private static byte[] strictBase64(String s) {
        if (s == null) {
            return null;
        }

        if (s.isEmpty()) {
            return new byte[0];
        }

        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code base64_decode($s, false)}：宽模式；无法解出（垃圾、4 的余数为 1）返回 {@code null}。 */
    private static byte[] looseBase64(String s) {
        String filtered = s.replaceAll("[^A-Za-z0-9+/]", "");
        int padding = filtered.indexOf('=');

        if (padding >= 0) {
            filtered = filtered.substring(0, padding);
        }

        int rest = filtered.length() % 4;

        if (rest == 1) {
            return null;
        }

        return Base64.getDecoder().decode(filtered + switch (rest) {
            case 2 -> "==";
            case 3 -> "=";
            default -> "";
        });
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] copyOf(byte[] source, int length) {
        byte[] out = new byte[length];
        System.arraycopy(source, 0, out, 0, length);

        return out;
    }

    private java.security.spec.AlgorithmParameterSpec gcmOrIvParameter(byte[] iv) {
        return spec.aead() ? new GCMParameterSpec(128, iv) : new IvParameterSpec(iv);
    }

    /** AES-CBC(PKCS5) 解密；GCM 用无填充并拼接 tag。 */
    private byte[] cipherDecrypt(byte[] data, byte[] aesKey, byte[] iv, byte[] tag)
            throws java.security.GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(spec.aead() ? "AES/GCM/NoPadding" : "AES/CBC/PKCS5Padding");

        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                spec.aead() ? new GCMParameterSpec(128, iv) : new IvParameterSpec(iv));

        if (spec.aead()) {
            byte[] joined = new byte[data.length + 16];
            System.arraycopy(data, 0, joined, 0, data.length);
            System.arraycopy(tag, 0, joined, data.length, 16);
            data = joined;
        }

        return cipher.doFinal(data);
    }

    /**
     * 生成 {@code {iv,value,mac,tag}} JSON。
     *
     * <p>全部字段是 base64/hex ASCII，手动拼接和
     * {@code json_encode(..., JSON_UNESCAPED_SLASHES)} 逐字节一致。</p>
     */
    private static String payload(String iv, String value, String mac, String tag) {
        return "{\"iv\":\"" + iv + "\",\"value\":\"" + value
                + "\",\"mac\":\"" + mac + "\",\"tag\":\"" + tag + "\"}";
    }

    /** MAC 用户接口（构造器保留下来的）：HMAC 输入是 base64 字符串文本。 */
    private static String mac(String ivB64, String valueB64, byte[] key) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));

            byte[] digest = hmac.doFinal((ivB64 + valueB64).getBytes(StandardCharsets.ISO_8859_1));

            StringBuilder sb = new StringBuilder(digest.length * 2);

            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }

            return sb.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
