package com.neoskin.web.error;

/**
 * PHP 字符串函数的等价实现（错误页专用，只覆盖渲染错误页需要的那几个）。
 *
 * <p>为什么不用 Thymeleaf 的 {@code th:utext} 直接输出：源模板里
 * {@code errors.exception.detail|nl2br} 的过滤器声明是
 * {@code ['pre_escape' => 'html', 'is_safe' => ['html']]} —— 含义是
 * <b>先 {@code htmlspecialchars}，再把换行换成 {@code <br />}</b>，
 * 最后整段当作 HTML 输出。顺序反过来就是一个 XSS 洞，所以必须自己算。</p>
 *
 * <p>{@code nl2br} 的实现照抄 PHP：正则
 * {@code /\r\n|\n\r|\n|\r/} 每处替换成 {@code "<br />" . $matches[0]}，
 * 也就是<b>保留原来的换行符</b>（{@code "a\r\nb"} → {@code "a<br />\r\nb"}）。</p>
 */
public final class PhpHtml {

    private PhpHtml() {
    }

    /**
     * {@code htmlspecialchars($s, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8')}。
     *
     * <p>五个实体：{@code &} → {@code &amp;}、{@code "} → {@code &quot;}、
     * {@code '} → {@code &#039;}、{@code <} → {@code &lt;}、{@code >} → {@code &gt;}。
     * 注意单引号是 {@code &#039;}（<b>不是</b> {@code &#39;} 也不是 {@code &apos;}），
     * 这是 PHP 的固有写法，源站输出里能直接看到。</p>
     */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#039;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** {@code nl2br($s)}：每个 {@code \r\n} / {@code \n\r} / {@code \n} / {@code \r} 前插 {@code <br />}。 */
    public static String nl2br(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n') {
                // 先确定这一处「换行序列」的长度：\r\n 与 \n\r 都算一个
                int length = 1;
                if (i + 1 < s.length()) {
                    char next = s.charAt(i + 1);
                    if ((c == '\r' && next == '\n') || (c == '\n' && next == '\r')) {
                        length = 2;
                    }
                }
                out.append("<br />");
                out.append(s, i, i + length);
                i += length;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 源模板的 {@code |nl2br}（pre_escape=html）：先转义再插 {@code <br />}。 */
    public static String escapeNl2br(String s) {
        return nl2br(escape(s));
    }
}
