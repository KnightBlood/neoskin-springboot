package com.neoskin.support;

import java.util.Map;

/**
 * PHP 的 URL 编码辅助函数：{@code rawurlencode()} / {@code urlencode()} /
 * {@code http_build_query()}。
 *
 * <p>三个函数的差异只在「空格怎么处理」和「哪些字符转义」：
 *
 * <table border="1">
 *   <caption>编码规则</caption>
 *   <tr><th>函数</th><th>空格</th><th>转义范围</th></tr>
 *   <tr><td>{@code rawurlencode}</td><td>{@code %20}</td>
 *       <td>{@code A-Za-z0-9-_.~} 之外的<b>所有</b>字节（RFC 3986）</td></tr>
 *   <tr><td>{@code urlencode}</td><td>{@code +}</td>
 *       <td>同上（RFC 1738 的 form 变体）</td></tr>
 *   <tr><td>{@code http_build_query}</td><td>{@code +}</td>
 *       <td>键值各自 {@code urlencode}，用 {@code &} 连接</td></tr>
 * </table>
 *
 * <p>注意 {@link java.net.URLEncoder#encode} 与 {@code urlencode} 恰好等价
 * （空格变 {@code +}、{@code ~} 会被转义成 {@code %7E} 这一点两者不同），
 * 所以这里自己按字节实现，避免 JDK 版本差异带来的输出变化。
 */
public final class PhpUrl {

    /** RFC 3986 的 unreserved 字符集，{@code rawurlencode}/{{@code urlencode}} 都不转义它们。 */
    private static final String UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~";

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private PhpUrl() {
    }

    /**
     * PHP 的 {@code rawurlencode($s)}：按 UTF-8 字节编码，空格是 {@code %20}。
     */
    public static String rawUrlEncode(String s) {
        if (s == null) {
            return "";
        }
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (c < 0x80 && UNRESERVED.indexOf((char) c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
            }
        }
        return out.toString();
    }

    /** PHP 的 {@code urlencode($s)}：与 {@link #rawUrlEncode} 相同，但空格是 {@code +}。 */
    public static String urlEncode(String s) {
        return rawUrlEncode(s).replace("%20", "+");
    }

    /**
     * PHP 的 {@code urldecode($s)}：{@code %XX} 与 {@code +} 都还原，非法序列原样保留。
     *
     * <p>用于 {@code $_GET} / {@code $_POST} 的键值解析（{@code parse_str} 语义）。</p>
     */
    public static String decodeComponent(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        byte[] raw = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        for (int i = 0; i < raw.length; i++) {
            int c = raw[i] & 0xFF;

            if (c == '+') {
                out.write(' ');

                continue;
            }

            if (c == '%' && i + 2 < raw.length) {
                int high = hexDigit(raw[i + 1]);
                int low = hexDigit(raw[i + 2]);

                if (high >= 0 && low >= 0) {
                    out.write(high * 16 + low);
                    i += 2;

                    continue;
                }
            }

            out.write(c);
        }

        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int hexDigit(byte b) {
        int c = b & 0xFF;

        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }

        return -1;
    }

    /**
     * PHP 的 {@code http_build_query($array)}（无前缀、默认编码）。
     *
     * <p>只支持标量值：嵌套数组在源项目里没出现，遇到时按 {@code (string)} 语义
     * 转成 {@code "Array"}——与 PHP 一样是个「有损但不崩」的结果。
     * {@code null} 值会被 PHP <b>跳过</b>。
     */
    public static String buildQuery(Map<?, ?> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<?, ?> e : query.entrySet()) {
            Object value = e.getValue();
            if (value == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(urlEncode(PhpString.cast(e.getKey())));
            out.append('=');
            out.append(urlEncode(PhpString.cast(value)));
        }
        return out.toString();
    }
}
