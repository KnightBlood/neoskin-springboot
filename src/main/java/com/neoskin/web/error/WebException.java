package com.neoskin.web.error;

import java.util.List;
import java.util.Map;

/**
 * 应用级异常的载体 —— 对应源里会被 Laravel 异常处理器渲染的那几种 {@code Throwable}。
 *
 * <p>源项目的渲染分流（{@code Handler::render → prepareException → …}）在 Java 侧
 * 走 <b>Vert.x 的 failure 通道</b>：中间件/控制器 {@code ctx.fail(throwable)}，
 * 由 {@link ErrorPages} 挂在 Router 末尾的 failureHandler 统一渲染。这样
 * 「谁抛、谁渲染」与 Laravel 一致，也不会在每个 handler 里重复写 try/catch。</p>
 *
 * <h2>两种形态（状态码行为不同，必须分开）</h2>
 * <ul>
 *   <li>{@link #http}：普通 HTTP 异常（404 / 405 / 403 …）。按状态码选模板
 *       {@code errors.<code>} → {@code errors.<xx>x} → Symfony 通用页，
 *       状态码如实返回；</li>
 *   <li>{@link #pretty}：源 {@code App\Exceptions\PrettyPageException}。它有
 *       {@code render()} 方法，Laravel 会<b>先</b>调它 —— 于是渲染
 *       {@code errors.pretty} 且状态码是 <b>200</b>（{@code response()->view()}
 *       默认 200，异常自身的 code 反而只作为「错误代码」显示在正文里）。</li>
 * </ul>
 *
 * <p>{@code Pretty} 的这个 200 很反直觉，但 PHP 实测如此（IE 拦截页、注册关闭页都是
 * 200 + {@code Error occurred} 标题），见 PORT-NOTES §13。</p>
 */
public final class WebException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 是否为 PrettyPage（决定状态码 200 与 errors.pretty 模板）。 */
    private final boolean pretty;

    /** 要返回的 HTTP 状态码（{@code pretty} 恒为 200）。 */
    private final int status;

    /** 响应头（405 的 {@code Allow} 走这里）。 */
    private final Map<String, String> headers;

    /** {@code PrettyPageException::$code}；普通 HTTP 异常恒为 0。 */
    private final int code;

    private WebException(boolean pretty, int status, int code, String message,
            Map<String, String> headers, Throwable cause) {
        super(message, cause);
        this.pretty = pretty;
        this.status = status;
        this.code = code;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** 普通 HTTP 异常：{@code abort(404)} / {@code NotFoundHttpException} 等。 */
    public static WebException http(int status, String message) {
        return http(status, message, Map.of(), null);
    }

    /** 普通 HTTP 异常（带响应头，如 405 的 {@code Allow}）。 */
    public static WebException http(int status, String message, Map<String, String> headers) {
        return http(status, message, headers, null);
    }

    /** 普通 HTTP 异常（带 cause）。 */
    public static WebException http(int status, String message, Map<String, String> headers,
            Throwable cause) {
        return new WebException(false, status, 0, message, headers, cause);
    }

    /**
     * {@code PrettyPageException} 的等价物：正文渲染 {@code errors.pretty}，
     * 状态码 <b>200</b>。
     *
     * @param code 显示在正文「错误代码: :code」里的数字（<b>不是</b> HTTP 状态码）
     */
    public static WebException pretty(String message, int code) {
        return new WebException(true, 200, code, message, Map.of(), null);
    }

    /** 404：{@code The route <path> could not be found.}。 */
    public static WebException notFound(String path) {
        return http(404, "The route " + path + " could not be found.");
    }

    /** 405：{@code The <M> method is not supported for route <path>. Supported methods: …}。 */
    public static WebException methodNotAllowed(String method, String path, List<String> allowed) {
        return http(405,
                "The " + method + " method is not supported for route " + path
                        + ". Supported methods: " + String.join(", ", allowed) + ".",
                Map.of("Allow", String.join(", ", allowed)));
    }

    public boolean isPretty() {
        return pretty;
    }

    public int status() {
        return status;
    }

    public Map<String, String> headers() {
        return headers;
    }

    /** {@code PrettyPageException::$code}。 */
    public int code() {
        return code;
    }
}
