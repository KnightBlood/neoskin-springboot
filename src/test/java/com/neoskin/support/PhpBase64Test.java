package com.neoskin.support;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PhpBase64} 的回归测试。
 *
 * <p>期望值全部来自 PHP 8.5 的实测（脚本见 {@code conformance/README.md} §8 的偏差记录）：
 * 用 {@code bin2hex(base64_decode($s, $strict))} 逐条打印，再抄进这里。
 *
 * <p>为什么要专门测「宽松模式」：cookie 链路上三处 {@code base64_decode()} 都没传
 * {@code $strict}，包括 {@code Encrypter::getJsonPayload()}。宽松模式会静默跳过
 * 空格、制表符、非法字符，并且在输入中部出现 {@code =} 之后还能继续吃数据
 * （{@code "YQ==A"} → {@code "a\0"}）——这些行为 {@link java.util.Base64} 全都拒绝。
 */
class PhpBase64Test {

    /** 实测样本：{输入, 宽松模式十六进制, 严格模式十六进制（{@code null} 表示 false）}。 */
    private static final List<String[]> SAMPLES = List.of(
            new String[] {"YQ==", "61", "61"},
            new String[] {"YQ", "61", "61"},
            new String[] {"ab==", "69", "69"},
            new String[] {"abc", "69b7", "69b7"},
            new String[] {"YYYYY", "618618", null},
            new String[] {"YQ==A", "6100", null},
            // NUL / \v / \f 都是 -2 档：宽松跳过，严格失败
            new String[] {"YQ==\u0000", "61", null},
            new String[] {"YQ==\u000B", "61", null},
            new String[] {"YQ==\u000C", "61", null},
            // 全部是填充符：一个字节都不产出
            new String[] {"====", "", null},
            new String[] {"=", "", null},
            // URL 安全字母表不属于标准表：- _ 都是 -2
            new String[] {"a-b_", "69", null},
            new String[] {"-_-_", "", null},
            // ' ' 是 -1 档，宽松跳过；后面的 x 被继续吃进去，于是多出一个 0x0c
            new String[] {"YQ==  x", "610C", null},
            new String[] {"YQ=", "61", null},
            // 尾组只有 1 个字符：case 0 只写不提交，所以是空串
            new String[] {"Q", "", null},
            new String[] {"QQ", "41", "41"},
            new String[] {"QUI", "4142", "4142"},
            new String[] {"QUJD", "414243", "414243"},
            new String[] {" YQ==", "61", "61"},
            new String[] {"", "", ""},
            new String[] {"QUJDRA==", "41424344", "41424344"},
            new String[] {"QUJDRA", "41424344", "41424344"},
            new String[] {"AAAA", "000000", "000000"},
            new String[] {"+/+/", "FBFFBF", "FBFFBF"});

    @Test
    @DisplayName("decode 宽松 / 严格两种模式的 24 组实测样本")
    void decodeMatchesPhp() {
        for (String[] sample : SAMPLES) {
            String input = sample[0];
            assertArrayEquals(bytes(sample[1]), PhpBase64.decode(input),
                    "宽松模式 " + describe(input));
            byte[] strict = PhpBase64.decode(input, true);
            if (sample[2] == null) {
                assertNull(strict, "严格模式应失败 " + describe(input));
                assertTrue(PhpBase64.failsStrictly(input), describe(input));
            } else {
                assertArrayEquals(bytes(sample[2]), strict,
                        "严格模式 " + describe(input));
                assertFalse(PhpBase64.failsStrictly(input), describe(input));
            }
        }
    }

    @Test
    @DisplayName("encode 带标准填充，长度是 4 的倍数")
    void encodeMatchesPhp() {
        assertEquals("", PhpBase64.encode(new byte[0]));
        assertEquals("YQ==", PhpBase64.encode("a"));
        assertEquals("YWI=", PhpBase64.encode("ab"));
        assertEquals("YWJj", PhpBase64.encode("abc"));
        // 密码按 UTF-8 字节编码：6 字节 → 8 个 base64 字符
        assertEquals("5a+G56CB", PhpBase64.encode("密码"));
        assertEquals("5a+G56CB", PhpBase64.encode("密码".getBytes(StandardCharsets.UTF_8)));
        // 高字节不落进 URL 安全字母表
        assertEquals("+/+/", PhpBase64.encode(new byte[] {(byte) 0xFB, (byte) 0xFF, (byte) 0xBF}));
    }

    @Test
    @DisplayName("encode 与 decode 往返：0..255 全字节")
    void roundTripAllBytes() {
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
        }
        String encoded = PhpBase64.encode(all);
        assertEquals(0, encoded.length() % 4);
        assertArrayEquals(all, PhpBase64.decode(encoded));
        assertArrayEquals(all, PhpBase64.decode(encoded, true));
    }

    @Test
    @DisplayName("严格模式的三条填充规则")
    void strictPaddingRules() {
        // (i + padding) % 4 != 0
        assertNull(PhpBase64.decode("YQ=", true));
        assertNull(PhpBase64.decode("YQ===", true));
        // padding > 2
        assertNull(PhpBase64.decode("Y===", true));
        // i % 4 == 1 即使没有填充也失败
        assertNull(PhpBase64.decode("Y", true));
        assertNull(PhpBase64.decode("YYYYY", true));
        // 合法
        assertEquals("a", PhpBase64.decode("YQ==", true) == null ? null
                : new String(PhpBase64.decode("YQ==", true), StandardCharsets.ISO_8859_1));
    }

    @Test
    @DisplayName("宽松模式永不失败：任意垃圾输入都返回字节")
    void looseNeverFails() {
        for (String garbage : List.of("!!!", "\u0000\u0001\u0002", "-_-_", "\uD83D\uDD10", "====")) {
            assertFalse(PhpBase64.decode(garbage).length > 0 && garbage.equals("!!!"));
        }
        // 非 ASCII 按 UTF-8 拆成多字节，全部是 -2 → 空
        assertEquals(0, PhpBase64.decode("\uD83D\uDD10").length);
        assertEquals(0, PhpBase64.decode("\u00FF").length);
    }

    @Test
    @DisplayName("null 输入按空串处理（PHP 侧是 TypeError，这里只求不炸）")
    void nullIsEmpty() {
        assertEquals(0, PhpBase64.decode(null).length);
        assertArrayEquals(new byte[0], PhpBase64.decode(null, true));
        assertEquals("", PhpBase64.encode((String) null));
    }

    // ------------------------------------------------------------------ 辅助

    private static byte[] bytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String describe(String input) {
        StringBuilder sb = new StringBuilder("<");
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                sb.append(String.format("\\x%02X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('>').toString();
    }
}
