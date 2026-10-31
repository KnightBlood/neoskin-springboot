package com.neoskin.service.cipher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.neoskin.conformance.Corpus;
import com.neoskin.support.ErrorException;
import com.neoskin.support.PhpJson;
import com.neoskin.web.cookie.CookieValuePrefix;
import com.neoskin.web.middleware.EncryptCookies;

import tools.jackson.databind.JsonNode;

/**
 * cookie 加密链路的语料驱动测试：{@code Encrypter}（Laravel 13 语义）+
 * {@link CookieValuePrefix} + {@link EncryptCookies}。
 *
 * <p>数据源 {@code conformance/data/cookie.json} 的
 * {@code constructor.*} / {@code prefix_*} / {@code encrypt.*} /
 * {@code decrypt.*} / {@code appears.*} / {@code encryptcookies_*}。
 */
class CookieEncrypterTest {

    /** 语料里的主密钥（const.keys：app_key 的 base64 解码，APP_KEY 带 `base64:` 前缀）。 */
    private static byte[] rawKey() {
        String appKey = Corpus.require("cookie").caseById("const.keys").get("app_key").asString();

        return Base64.getDecoder().decode(appKey.replaceFirst("^base64:", ""));
    }

    // ------------------------------------------------------------------ constructor

    /** {@code constructor.*}: 4 条 —— RuntimeException 的信息必须逐字一致。 */
    @TestFactory
    java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> corpusConstructor() {
        Corpus corpus = Corpus.require("cookie");
        return corpus.cases("constructor").stream()
                .map(c -> org.junit.jupiter.api.DynamicTest.dynamicTest(
                        Corpus.text(c, "id"), () -> {
                            String key = Corpus.textOrEmpty(c, "key");
                            String cipher = Corpus.text(c, "cipher");

                            RuntimeException thrown = null;

                            try {
                                new PhpEncrypter(key.getBytes(StandardCharsets.UTF_8), cipher);
                            } catch (RuntimeException e) {
                                thrown = e;
                            }

                            assertTrue(thrown != null, Corpus.text(c, "id") + " 应拒绝");
                            assertEquals(Corpus.text(c, "error_message"), thrown.getMessage(),
                                    Corpus.text(c, "id") + " message");
                        }));
    }

    // ------------------------------------------------------------------ 前缀

    /** {@code prefix_create.*}: 7 条。 */
    @TestFactory
    java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> corpusPrefixCreate() {
        Corpus corpus = Corpus.require("cookie");
        byte[] key = rawKey();

        return corpus.cases("prefix_create").stream()
                .map(c -> org.junit.jupiter.api.DynamicTest.dynamicTest(
                        Corpus.text(c, "id"), () -> {
                            String prefix = CookieValuePrefix.create(Corpus.text(c, "name"), key);

                            assertEquals(Corpus.text(c, "expected"), prefix, Corpus.text(c, "id"));
                            assertEquals(Corpus.integer(c, "expected_length", 0), prefix.length());
                        }));
    }

    /** {@code prefix_remove.*}: 3 条（remove 是纯 substr(41)，不校验）。 */
    @TestFactory
    java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> corpusPrefixRemove() {
        Corpus corpus = Corpus.require("cookie");

        return corpus.cases("prefix_remove").stream()
                .map(c -> org.junit.jupiter.api.DynamicTest.dynamicTest(
                        Corpus.text(c, "id"), () -> assertEquals(
                                Corpus.textOrEmpty(c, "expected"),
                                CookieValuePrefix.remove(Corpus.textOrEmpty(c, "input")),
                                Corpus.text(c, "id"))));
    }

    /** {@code prefix_validate.*}: 6 条 —— 密钥轮换里任何一条 key 对上都算合法。 */
    @TestFactory
    java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> corpusPrefixValidate() {
        Corpus corpus = Corpus.require("cookie");
        byte[] key = rawKey();
        byte[] otherKey = "o".repeat(32).getBytes(StandardCharsets.UTF_8);

        return corpus.cases("prefix_validate").stream()
                .map(c -> org.junit.jupiter.api.DynamicTest.dynamicTest(
                        Corpus.text(c, "id"), () -> {
                            List<byte[]> keys = Corpus.integer(c, "key_count", 1) == 2
                                    ? List.of(key, otherKey)
                                    : List.of(key);

                            String expected = Corpus.text(c, "expected");

                            String validated = CookieValuePrefix.validate(
                                    Corpus.text(c, "name"), Corpus.textOrEmpty(c, "value"), keys);

                            if (expected == null) {
                                assertEquals(null, validated, Corpus.text(c, "id"));
                            } else {
                                assertEquals(expected, validated, Corpus.text(c, "id"));
                            }
                        }));
    }

    /** {@code const.cookie_value_prefix}: formula 记录的是实现依据的口径。 */
    @Test
    void constFormulaMatchesImplementation() {
        assertEquals("hash_hmac('sha1', $name.'v2', $key).'|'",
                Corpus.require("cookie").caseById("const.cookie_value_prefix").get("formula").asString());
    }

    // ------------------------------------------------------------------ encrypt

    /** {@code encrypt.*}: 9 条 —— 固定 IV 逐字节复核。 */
    @TestFactory
    java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> corpusEncrypt() {
        Corpus corpus = Corpus.require("cookie");

        return corpus.cases("encrypt").stream()
                .map(c -> org.junit.jupiter.api.DynamicTest.dynamicTest(
                        Corpus.text(c, "id"), () -> {
                            boolean aes128 = "short".equals(Corpus.text(c, "key"));
                            PhpEncrypter encrypter = new PhpEncrypter(
                                    aes128 ? "NeoSkin16ByteKey".getBytes(StandardCharsets.UTF_8) : rawKey(),
                                    Corpus.textOrEmpty(c, "cipher"));

                            byte[] iv = Base64.getDecoder().decode(Corpus.textOrEmpty(c, "iv_b64"));

                            // 语料的 plain 在 serialize=true 时已是 serialize() 之后的字面量，
                            // 所以统一不包一层 serialize。
                            assertEquals(Corpus.textOrEmpty(c, "expected"),
                                    encrypter.encryptWithIv(iv, Corpus.textOrEmpty(c, "plain"), false),
                                    Corpus.text(c, "id"));

                            assertEquals(Corpus.textOrEmpty(c, "json_payload"),
                                    new String(Base64.getDecoder()
                                            .decode(encrypter.encryptWithIv(iv,
                                                    Corpus.textOrEmpty(c, "plain"), false)),
                                            StandardCharsets.ISO_8859_1),
                                    Corpus.text(c, "id") + " json_payload");
                        }));
    }

    /** {@code const.keys}: supported ciphers 清单逐字一致。 */
    @Test
    void constSupportedCiphers() {
        JsonNode supported = Corpus.require("cookie").caseById("const.keys").get("supported_ciphers");
        List<String> expected = new java.util.ArrayList<>();
        supported.forEach(n -> expected.add(n.asString()));

        assertEquals(expected, List.of("aes-128-cbc", "aes-256-cbc", "aes-128-gcm", "aes-256-gcm"));
    }

    // ------------------------------------------------------------------ decrypt

    /** {@code decrypt.*}: 22 条 —— ok / 异常类型 / 异常消息三样全对。 */
    @TestFactory
    java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> corpusDecrypt() {
        Corpus corpus = Corpus.require("cookie");

        return corpus.cases("decrypt").stream()
                .map(c -> org.junit.jupiter.api.DynamicTest.dynamicTest(
                        Corpus.text(c, "id"), () -> {
                            PhpEncrypter encrypter = "w".repeat(32).equals(Corpus.text(c, "key"))
                                    ? new PhpEncrypter("w".repeat(32).getBytes(StandardCharsets.UTF_8), "aes-256-cbc")
                                    : new PhpEncrypter(rawKey(), "aes-256-cbc");
                            String payload = Corpus.text(c, "payload") == null
                                    ? Corpus.text(c, "payload_repr") // 非字符串载荷按 PHP 直观的 repr
                                    : Corpus.text(c, "payload");
                            boolean unserialize = Corpus.bool(c, "unserialize", true);

                            boolean ok = Corpus.bool(c, "ok", true);

                            try {
                                Object value = encrypter.decrypt(payload, unserialize);
                                assertTrue(ok, Corpus.text(c, "id") + " 应抛异常");
                                assertTrue(matches(c.get("expected"), value),
                                        Corpus.text(c, "id") + " value=" + value);
                            } catch (RuntimeException e) {
                                assertFalse(ok, Corpus.text(c, "id") + " 不应抛异常: " + e);
                                String expectedClass = Corpus.text(c, "error_class");
                                String actualClass = e instanceof PhpEncrypter.DecryptException
                                        ? "DecryptException"
                                        : e.getClass().getSimpleName();
                                if ("Illuminate\\Contracts\\Encryption\\DecryptException".equals(expectedClass)) {
                                    assertTrue(e instanceof PhpEncrypter.DecryptException,
                                            Corpus.text(c, "id") + " 是 DecryptException，实际 " + actualClass);
                                } else if ("ErrorException".equals(expectedClass)) {
                                    assertTrue(e instanceof ErrorException,
                                            Corpus.text(c, "id") + " 是 ErrorException，实际 " + actualClass);
                                }
                                assertEquals(Corpus.text(c, "error_message"), e.getMessage(),
                                        Corpus.text(c, "id") + " message");
                            }
                        }));
    }

    /** 语料期望值 ↔ 解密结果。 */
    static boolean matches(JsonNode expected, Object actual) {
        if (expected == null || expected.isNull()) {
            return actual == null;
        }
        if (expected.isString()) {
            return expected.asString().equals(actual);
        }
        if (expected.isBoolean()) {
            return actual instanceof Boolean b && b == expected.asBoolean();
        }
        if (expected.isInt() || expected.isLong() || expected.isBigInteger()) {
            // PHP 数值标量在移植侧统一为 Long。
            return actual instanceof Long l && l == expected.asLong();
        }
        if (expected.isNumber()) {
            return actual instanceof Double d && d == expected.asDouble();
        }
        return actual instanceof PhpJson.Array array && sameJson(expected, array);
    }

    private static boolean sameJson(JsonNode expected, PhpJson.Array actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        if (expected.isArray()) {
            for (int i = 0; i < expected.size(); i++) {
                Object value = actual.get((long) i);

                if (!compare(expected.get(i), value)) {
                    return false;
                }
            }

            return true;
        }

        for (Map.Entry<String, JsonNode> field : expected.properties()) {
            if (!compare(field.getValue(), actual.get(field.getKey()))) {
                return false;
            }
        }

        return true;
    }

    private static boolean compare(JsonNode expected, Object actual) {
        return expected.isObject() || expected.isArray()
                ? actual instanceof PhpJson.Array child && sameJson(expected, child)
                : matches(expected, actual);
    }

    // ------------------------------------------------------------------ appears_encrypted

    /** {@code appears.*}: 9 条。 */
    @TestFactory
    java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> corpusAppearsEncrypted() {
        Corpus corpus = Corpus.require("cookie");

        return corpus.cases("appears_encrypted").stream()
                .map(c -> org.junit.jupiter.api.DynamicTest.dynamicTest(
                        Corpus.text(c, "id"), () -> {
                            Object value = Corpus.text(c, "value_php_type") != null && "int".equals(Corpus.text(c, "value_php_type"))
                                    ? Corpus.integer(c, "value_repr", 0)
                                    : (Object) Corpus.text(c, "value");

                            assertEquals(Corpus.bool(c, "expected", false),
                                    PhpEncrypter.appearsEncrypted(value), Corpus.text(c, "id"));
                        }));
    }

    // ------------------------------------------------------------------ EncryptCookies（值层）

    /** {@code encryptcookies_decrypt.*}: 7 条。 */
    @TestFactory
    java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> corpusCookiesDecrypt() {
        Corpus corpus = Corpus.require("cookie");

        return corpus.cases("encryptcookies_decrypt").stream()
                .map(c -> org.junit.jupiter.api.DynamicTest.dynamicTest(
                        Corpus.text(c, "id"), () -> {
                            EncryptCookies middleware = new EncryptCookies(
                                    new PhpEncrypter(rawKey(), "aes-256-cbc"));

                            PhpJson.Array input = (PhpJson.Array) PhpJson.decode(
                                    c.get("input").toString());
                            PhpJson.Array result = middleware.decrypt(input);

                            assertTrue(matches(c.get("expected"), result), Corpus.text(c, "id"));
                        }));
    }

    /**
     * {@code encryptcookies_encrypt.*}: 5 条 —— 断言口径是三个布尔
     * （对齐 PHP：真实响应没法固定 IV，所以两侧都只看「像密文 & 不是明文」）。
     */
    @TestFactory
    java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> corpusCookiesEncrypt() {
        Corpus corpus = Corpus.require("cookie");

        return corpus.cases("encryptcookies_encrypt").stream()
                .map(c -> org.junit.jupiter.api.DynamicTest.dynamicTest(
                        Corpus.text(c, "id"), () -> {
                            EncryptCookies middleware = new EncryptCookies(
                                    new PhpEncrypter(rawKey(), "aes-256-cbc"));
                            String name = Corpus.text(c, "name");
                            String value = Corpus.textOrEmpty(c, "value");

                            assertEquals(Corpus.text(c, "prefix"),
                                    CookieValuePrefix.create(name, rawKey()), Corpus.text(c, "id"));

                            String output = middleware.encrypt(name, value);

                            assertEquals(Corpus.bool(c, "output_appears_encrypted", false),
                                    PhpEncrypter.appearsEncrypted(output), Corpus.text(c, "id"));

                            // expected_plain = prefix + value：解出来必须还原成它
                            assertEquals(Corpus.text(c, "expected_plain"),
                                    new PhpEncrypter(rawKey(), "aes-256-cbc")
                                            .decrypt(output, false),
                                    Corpus.text(c, "id") + " roundtrip");
                        }));
    }

    /** 往返：数组 cookie 用 "arr[0]" 名字加密，请求侧解回 ["a"]。 */
    @Test
    void decryptRoundTripsArrays() {
        PhpEncrypter encrypter = new PhpEncrypter(rawKey(), "aes-256-cbc");
        EncryptCookies middleware = new EncryptCookies(encrypter);

        String payload = middleware.encrypt("arr[0]", "a");

        PhpJson.Array cookies = new PhpJson.Array();
        PhpJson.Array arr = new PhpJson.Array();
        arr.put(0L, payload);
        cookies.put("arr", arr);

        Object decrypted = middleware.decrypt(cookies).get("arr");

        assertTrue(decrypted instanceof PhpJson.Array a && "a".equals(a.get(0L)),
                "应解回 'a'，实际：" + decrypted);
    }

    /** {@code isDisabled()}：实例与全局两个名单都生效。 */
    @Test
    void disabledCookiesSkipDecryption() {
        EncryptCookies.flushState();
        EncryptCookies middleware = new EncryptCookies(new PhpEncrypter(rawKey(), "aes-256-cbc"));

        middleware.disableFor("locale");
        EncryptCookies.except(List.of("XSRF-TOKEN"));

        assertTrue(middleware.isDisabled("locale"));
        assertTrue(middleware.isDisabled("XSRF-TOKEN"));
        assertFalse(middleware.isDisabled("BS_SESSION"));

        // flushState 只清静态名单；实例 except 随用例一起换新（Laravel 每个请求
        // 都是新实例，静态状态跨用例才需要等价于 flushState 的复位）。
        EncryptCookies.flushState();
        EncryptCookies fresh = new EncryptCookies(new PhpEncrypter(rawKey(), "aes-256-cbc"));
        assertFalse(fresh.isDisabled("locale"));
        assertFalse(fresh.isDisabled("XSRF-TOKEN"));
    }
}
