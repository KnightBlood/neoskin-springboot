package com.neoskin.web.error;

import java.util.Map;

/**
 * Symfony {@code Response::$statusTexts} 的子集 + Laravel 视图选择的兜底规则。
 *
 * <p>只收录本工程会真正产出的状态码。405 页面的标题
 * （{@code An Error Occurred: Method Not Allowed}）与 Symfony 通用错误页正文
 * 都直接取这里的文案，所以名单必须和 Symfony 一字不差。</p>
 */
public final class HttpStatus {

    /** 状态码 → 英文短语（Symfony {@code Response::$statusTexts}）。 */
    private static final Map<Integer, String> TEXTS = Map.ofEntries(
            Map.entry(200, "OK"),
            Map.entry(201, "Created"),
            Map.entry(204, "No Content"),
            Map.entry(301, "Moved Permanently"),
            Map.entry(302, "Found"),
            Map.entry(304, "Not Modified"),
            Map.entry(400, "Bad Request"),
            Map.entry(401, "Unauthorized"),
            Map.entry(402, "Payment Required"),
            Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"),            Map.entry(405, "Method Not Allowed"),
            Map.entry(413, "Content Too Large"),
            Map.entry(422, "Unprocessable Content"),
            Map.entry(429, "Too Many Requests"),
            Map.entry(500, "Internal Server Error"),
            Map.entry(502, "Bad Gateway"),
            Map.entry(503, "Service Unavailable"),
            Map.entry(504, "Gateway Timeout"));

    private HttpStatus() {
    }

    /** {@code Response::$statusTexts[$code]}，未知码返回 {@code "unknown status"}。 */
    public static String text(int code) {
        return TEXTS.getOrDefault(code, "unknown status");
    }
}
