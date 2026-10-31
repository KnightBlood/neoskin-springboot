package com.neoskin.support;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * PHP {@code base64_encode()} / {@code base64_decode($s)} / {@code base64_decode($s, true)} 的移植。
 *
 * <p>为什么不直接用 {@link java.util.Base64}：PHP 的<b>宽松</b>解码与 RFC 4648 差得很远，
 * 而 cookie 解密链路上每一步都用宽松模式（{@code base64_decode($payload)}、
 * {@code base64_decode($iv)}），只要有一点不同，同一个 cookie 在两个实现里就会解出不同的字节。
 *
 * <table border="1">
 *   <caption>PHP 宽松模式 vs Java 严格解码</caption>
 *   <tr><th>输入</th><th>PHP 宽松</th><th>Java {@code Base64.getDecoder()}</th></tr>
 *   <tr><td>{@code "YQ"}</td><td>{@code "a"}</td><td>抛异常（长度不是 4 的倍数）</td></tr>
 *   <tr><td>{@code "YQ==A"}</td><td>{@code "a\0"}</td><td>抛异常（填充后还有数据）</td></tr>
 *   <tr><td>{@code "a-b_"}</td><td>{@code "i"}</td><td>{@code "i\xbf"}（URL 字母表）</td></tr>
 *   <tr><td>{@code "YYYYY"}</td><td>{@code "a\xbf\x18"}</td><td>抛异常</td></tr>
 *   <tr><td>{@code ""}</td><td>{@code ""}</td><td>{@code ""}</td></tr>
 * </table>
 *
 * <p>实现逐行照抄 PHP 8.5 的 {@code ext/standard/base64.c} 里的
 * {@code php_base64_decode_impl()}（见 {@code conformance/README.md} 的偏差记录）：
 * <ol>
 *   <li>{@code =} 在任何查表之前就被拦下，只做 {@code padding++}；</li>
 *   <li>反向表里 {@code \t}(9)、{@code \n}(10)、{@code \r}(13)、空格(32) 是 <b>-1</b>，
 *       其余非法字节（含 {@code U+000B}、{@code U+000C}、NUL）是 <b>-2</b>；</li>
 *   <li>宽松模式跳过所有负值；严格模式只跳过 -1，遇到 -2 或「填充后仍有数据」立即失败；</li>
 *   <li>输出按 {@code i % 4} 累积：{@code case 0} 只写不提交，{@code case 1/2/3} 提交，
 *       所以尾组不完整时「多写的那个字节」不会被算进结果（{@code "Q"} 是空串，
 *       {@code "QQ"} 是一个字节）；</li>
 *   <li>{@code i} 只在喂入合法字符后自增，{@code =} 不计入 {@code i}。</li>
 * </ol>
 *
 * <p>严格模式额外要求 {@code i % 4 != 1}、{@code padding <= 2}、
 * {@code (i + padding) % 4 == 0}；任一不满足返回 {@code null}（对应 PHP 的 {@code false}）。
 *
 * <p>注意 {@code strlen(false) === 0}：把 {@code base64_decode($iv, true)} 的结果直接拿去比
 * IV 长度时，解码失败等价于「长度为 0」，因此只有 IV 长度本来就是 0 的密码算法才会放行。
 * 这一点在 {@link com.neoskin.web.cookie.PhpCookieEncrypter#validPayload} 里原样保留。
 */
public final class PhpBase64 {

    private static final char[] TABLE =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** 与 C 版的 {@code base64_reverse_table} 逐项一致。 */
    private static final short[] REVERSE = new short[256];

    static {
        Arrays.fill(REVERSE, (short) -2);
        for (int i = 0; i < TABLE.length; i++) {
            REVERSE[TABLE[i]] = (short) i;
        }
        // PHP 只把 \t \n \r 和空格当空白；U+000B / U+000C 都是 -2。
        REVERSE['\t'] = -1;
        REVERSE['\n'] = -1;
        REVERSE['\r'] = -1;
        REVERSE[' '] = -1;
    }

    private PhpBase64() {
    }

    // ================================================================== 编码

    /** 等价于 PHP 的 {@code base64_encode($bytes)}（标准字母表，带 {@code =} 填充）。 */
    public static String encode(byte[] in) {
        if (in == null || in.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder((in.length + 2) / 3 * 4);
        int i = 0;
        while (in.length - i > 2) {
            int b0 = in[i] & 0xFF;
            int b1 = in[i + 1] & 0xFF;
            int b2 = in[i + 2] & 0xFF;
            sb.append(TABLE[b0 >> 2]);
            sb.append(TABLE[((b0 & 0x03) << 4) + (b1 >> 4)]);
            sb.append(TABLE[((b1 & 0x0F) << 2) + (b2 >> 6)]);
            sb.append(TABLE[b2 & 0x3F]);
            i += 3;
        }
        int rest = in.length - i;
        if (rest != 0) {
            int b0 = in[i] & 0xFF;
            sb.append(TABLE[b0 >> 2]);
            if (rest > 1) {
                int b1 = in[i + 1] & 0xFF;
                sb.append(TABLE[((b0 & 0x03) << 4) + (b1 >> 4)]);
                sb.append(TABLE[(b1 & 0x0F) << 2]);
                sb.append('=');
            } else {
                sb.append(TABLE[(b0 & 0x03) << 4]);
                sb.append("==");
            }
        }
        return sb.toString();
    }

    /** 等价于 {@code base64_encode($s)}，其中 {@code $s} 是 UTF-8 字节串。 */
    public static String encode(String s) {
        return s == null ? "" : encode(s.getBytes(StandardCharsets.UTF_8));
    }

    // ================================================================== 解码

    /** 等价于 {@code base64_decode($s)}（宽松模式，永不失败）。 */
    public static byte[] decode(String s) {
        return decode(s, false);
    }

    /**
     * 等价于 {@code base64_decode($s, $strict)}。
     *
     * @param strict {@code true} 对应 PHP 的第二个参数；失败时返回 {@code null}
     *               （调用方负责把它当成 PHP 的 {@code false}）
     */
    public static byte[] decode(String s, boolean strict) {
        byte[] in = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        // 输出永远不超过输入长度；多留几个字节是因为 case 1/2 会「先写后提交」。
        byte[] out = new byte[in.length + 4];
        int i = 0;
        int padding = 0;
        int j = 0;

        for (byte raw : in) {
            int ch = raw & 0xFF;
            if (ch == '=') {
                padding++;
                continue;
            }
            int value = REVERSE[ch];
            if (!strict) {
                if (value < 0) {
                    continue;
                }
            } else {
                if (value == -1) {
                    continue;
                }
                if (value == -2 || padding != 0) {
                    return null;
                }
            }
            switch (i % 4) {
                case 0 -> out[j] = (byte) (value << 2);
                case 1 -> {
                    out[j] |= (byte) (value >> 4);
                    j++;
                    out[j] = (byte) ((value & 0x0F) << 4);
                }
                case 2 -> {
                    out[j] |= (byte) (value >> 2);
                    j++;
                    out[j] = (byte) ((value & 0x03) << 6);
                }
                default -> {
                    out[j] |= (byte) value;
                    j++;
                }
            }
            i++;
        }

        if (strict && i % 4 == 1) {
            return null;
        }
        if (strict && padding != 0 && (padding > 2 || (i + padding) % 4 != 0)) {
            return null;
        }
        return Arrays.copyOf(out, j);
    }

    /** 严格解码失败时返回 {@code true}（便于在 {@code if} 里直读 PHP 语义）。 */
    public static boolean failsStrictly(String s) {
        return decode(s, true) == null;
    }
}
