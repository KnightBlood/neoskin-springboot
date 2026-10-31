package com.neoskin.web;

import java.util.Locale;

import com.neoskin.support.PhpString;

/**
 * Laravel {@code UrlGenerator::to()} 的移植（只覆盖 Web 层用到的路径）。
 *
 * <p>源项目里 {@code url()} 用来生成语言切换链接与大量页面链接。这个方法有一串
 * 「看起来多余、实际决定输出」的步骤，必须照抄：
 *
 * <pre>
 * to($path, $extra = [], $secure = null):
 *   1. isValidUrl($path) → 原样返回（#、//、http(s)://、mailto/tel/sms:，或 filter_var 认为合法）
 *   2. $tail = implode('/', rawurlencode($extra))            ← extra 为空时是 ''
 *   3. $root = formatRoot(formatScheme($secure))
 *   4. [$path, $query] = extractQueryString($path)            ← 在第一个 '?' 处切
 *   5. return format($root, '/'.trim($path.'/'.$tail, '/')) . $query
 *
 * format($root, $path):
 *   $path = '/'.trim($path, '/')
 *   return trim($root.$path, '/')      ← 注意是 trim，不是 rtrim
 * </pre>
 *
 * <p>第 5 步的 {@code trim()} 解释了根路径为什么变成 {@code http://localhost?lang=xx}
 * 而不是 {@code http://localhost/?lang=xx}：{@code $path} 是 {@code "/"}，
 * 拼成 {@code "http://localhost/"} 之后末尾的 {@code /} 被 {@code trim} 掉了。
 * 语料用例 {@code menu.root} 锁定的就是这个结果。
 */
public final class PhpUrlGenerator {

    private final String root;
    private final String scheme;

    /**
     * @param root   请求根，形如 {@code http://localhost}（无尾斜杠）
     * @param scheme 当前 scheme，例如 {@code http}；用于把 root 的 scheme 替换掉
     */
    public PhpUrlGenerator(String root, String scheme) {
        this.root = root == null ? "" : root;
        this.scheme = (scheme == null ? "http" : scheme) + "://";
    }

    /** 以 {@code http} 为 scheme 的便捷构造。 */
    public static PhpUrlGenerator forHost(String host) {
        return new PhpUrlGenerator("http://" + host, "http");
    }

    /** {@code url($path)}：不带额外路径段、不强制 scheme。 */
    public String to(String path) {
        return to(path, java.util.List.of(), null);
    }

    /**
     * {@code UrlGenerator::to($path, $extra, $secure)}。
     *
     * @param path  路径，可含查询串
     * @param extra 追加到路径后的段（会逐个 {@code rawurlencode}）
     * @param secure {@code null} 表示用当前 scheme；{@code true}/{@code false} 强制
     */
    public String to(String path, java.util.List<String> extra, Boolean secure) {
        if (path == null) {
            path = "";
        }
        if (isValidUrl(path)) {
            return path;
        }

        StringBuilder tail = new StringBuilder();
        for (int i = 0; i < extra.size(); i++) {
            if (i > 0) {
                tail.append('/');
            }
            tail.append(com.neoskin.support.PhpUrl.rawUrlEncode(extra.get(i)));
        }

        String formattedRoot = formatRoot(formatScheme(secure));

        String[] split = extractQueryString(path);
        String formatted = format(formattedRoot, "/" + PhpString.trim(split[0] + "/" + tail, "/"));
        return formatted + split[1];
    }

    /** {@code isValidUrl()}：命中协议前缀直接为 true，否则交给 filter_var 等价规则。 */
    public static boolean isValidUrl(String path) {
        if (path == null) {
            return false;
        }
        if (path.startsWith("#") || path.startsWith("//") || path.startsWith("http://")
                || path.startsWith("https://") || path.startsWith("mailto:")
                || path.startsWith("tel:") || path.startsWith("sms:")) {
            return true;
        }
        return filterValidateUrl(path);
    }

    /**
     * {@code filter_var($s, FILTER_VALIDATE_URL)} 的近似实现。
     *
     * <p>PHP 的这条过滤器要求 {@code scheme://host[/path][?query]}, scheme 只允许
     * {@code [a-z][a-z0-9+.-]*}。源项目里只有 {@code site_url} 会被它检验，
     * 所以这里实现「协议 + 权威部分」两个必要条件即可，其余按 PHP 一样放行。
     */
    static boolean filterValidateUrl(String s) {
        int colon = s.indexOf("://");
        if (colon <= 0) {
            return false;
        }
        String schemePart = s.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!schemePart.matches("[a-z][a-z0-9+.\\-]*")) {
            return false;
        }
        String rest = s.substring(colon + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String authority = rest.substring(0, end);
        if (authority.isEmpty()) {
            return false;
        }
        if (authority.chars().anyMatch(c -> c <= ' ' || c == '"' || c == '<' || c == '>')) {
            return false;
        }
        return true;
    }

    /** {@code extractQueryString()}：在第一个 {@code ?} 处切成 [path, "?query"]。 */
    static String[] extractQueryString(String path) {
        int pos = path.indexOf('?');
        if (pos >= 0) {
            return new String[] {path.substring(0, pos), path.substring(pos)};
        }
        return new String[] {path, ""};
    }

    /** {@code format($root, $path)}。 */
    private String format(String formattedRoot, String path) {
        String normalized = "/" + PhpString.trim(path, "/");
        return PhpString.trim(formattedRoot + normalized, "/");
    }

    /** {@code formatRoot()}：把 root 的 scheme 换成给定 scheme（只换第一处）。 */
    private String formatRoot(String targetScheme) {
        String start = root.startsWith("http://") ? "http://" : "https://";
        if (root.startsWith(start)) {
            return targetScheme + root.substring(start.length());
        }
        return root;
    }

    /** {@code formatScheme($secure)}。 */
    private String formatScheme(Boolean secure) {
        if (secure != null) {
            return secure ? "https://" : "http://";
        }
        return scheme;
    }

    /** 请求根（无尾斜杠）。 */
    public String root() {
        return root;
    }
}
