package com.neoskin.web.error;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.neoskin.db.UserRepository;
import com.neoskin.service.Option;
import com.neoskin.service.session.SessionStore;
import com.neoskin.web.http.AcceptHeader;
import com.neoskin.web.http.AcceptHeaderItem;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.middleware.StartSession;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * 异常的 HTTP 出口（Router 末尾的 failureHandler）。
 *
 * <h2>与源的对照</h2>
 * <p>源里所有异常都由 {@code App\Exceptions\Handler::render()} 收口，里面有两条
 * 分支决定响应形态：</p>
 * <ol>
 *   <li>{@code $request->expectsJson()} →
 *       {@code prepareJsonResponse()}：pretty-print 的
 *       {@code {message, exception, trace}}；</li>
 *   <li>否则 → {@code renderHttpException()}：按状态码挑 HTML 模板
 *       （{@link ErrorPages}）。</li>
 * </ol>
 *
 * <p>{@code expectsJson()} 的定义是
 * {@code ($ajax && !$pjax && $this->acceptsAnyContentType()) || $this->wantsJson()}，
 * 三个条件都不能省：</p>
 * <ul>
 *   <li>{@code wantsJson()} 看 {@code Accept} <b>排序后第一项</b>是否含
 *       {@code /json} 或 {@code +json} —— 所以 {@code Accept: text/html,application/json}
 *       走 HTML（实测确认）；</li>
 *   <li>{@code acceptsAnyContentType()} 要求第一项就是 <code>*&#47;*</code>
 *       （或 {@code Accept} 头缺失）；</li>
 *   <li>{@code ajax()} 看 {@code X-Requested-With: XMLHttpRequest}。</li>
 * </ul>
 * <p>因此「XHR + {@code Accept: text/html}」仍然渲染 HTML，而
 * 「XHR + <code>Accept: *&#47;*</code>」走 JSON —— 这条差异由 {@link #expectsJson} 逐条复刻。</p>
 *
 * <h2>Cache-Control</h2>
 * <p>源的所有响应都带 {@code Cache-Control: no-cache, private}：这是 Symfony
 * {@code ResponseHeaderBag} 的保守默认值（见 {@code computeCacheControlValue}），
 * 在响应对象创建时就写死了。这里统一补上，包括 JSON 与 HTML 两条分支。</p>
 */
public final class ErrorHandler implements Handler<RoutingContext> {

    /** Symfony {@code ResponseHeaderBag} 的默认值。 */
    public static final String DEFAULT_CACHE_CONTROL = "no-cache, private";

    private final ErrorPages pages;

    private final UserRepository users;

    /** {@code app.debug}：只有调试模式才在 500 JSON 里带 trace（源行为）。 */
    private final boolean debug;

    public ErrorHandler(ErrorPages pages, UserRepository users) {
        this(pages, users, false);
    }

    public ErrorHandler(ErrorPages pages, UserRepository users, boolean debug) {
        this.pages = pages;
        this.users = users;
        this.debug = debug;
    }

    @Override
    public void handle(RoutingContext ctx) {
        Throwable failure = ctx.failure();
        WebException error = toWebException(ctx, failure);

        boolean expectsJson = expectsJson(ctx);

        applyCommonHeaders(ctx, error);

        if (expectsJson) {
            String body = jsonBody(error, expectsJson, failure, debug);
            ctx.response()
                    .setStatusCode(error.status())
                    .putHeader("Content-Type", "application/json")
                    .end(Buffer.buffer(body, "UTF-8"));
            return;
        }

        SessionStore session = ctx.get(StartSession.CTX_KEY);
        UserRepository.UserRow user = currentUser(session);

        ErrorPages.Rendered rendered = pages.render(error, urlRoot(ctx), laravelPath(ctx),
                query(ctx), session, user);

        io.vertx.core.http.HttpServerResponse response = ctx.response()
                .setStatusCode(rendered.status())
                .putHeader("Content-Type", "text/html; charset=utf-8");

        rendered.headers().forEach(response::putHeader);

        response.end(Buffer.buffer(rendered.body(), "UTF-8"));
    }

    // ------------------------------------------------------------------ 归一

    /** {@code ctx.failure()} → {@link WebException}（非 WebException 一律按 500）。 */
    static WebException toWebException(RoutingContext ctx, Throwable failure) {
        if (failure instanceof WebException web) {
            return web;
        }
        // Vert.x 的 ctx.fail(statusCode) 只设置状态码、failure 为 null；
        // Laravel 的 abort(404) 等正是这种「无异常对象的失败」。
        if (failure == null) {
            int status = ctx.statusCode();
            if (status >= 400) {
                return WebException.http(status, "");
            }
        }
        String message = failure == null ? "" : String.valueOf(failure.getMessage());
        return WebException.http(500, message);
    }

    /** 405/404 等自带的响应头 + Symfony 的默认 Cache-Control。 */
    private static void applyCommonHeaders(RoutingContext ctx, WebException error) {
        error.headers().forEach((name, value) -> ctx.response().putHeader(name, value));
        ctx.response().putHeader("Cache-Control", DEFAULT_CACHE_CONTROL);
    }

    /**
     * {@code $request->expectsJson()}。
     *
     * <p>{@code Accept} 的解析必须走 {@link AcceptHeader}（有序、去重、按 q 排序），
     * 不能简单 {@code contains("json")}：否则
     * {@code Accept: text/html,application/json} 会被误判成 JSON —— 源是 HTML。</p>
     */
    public static boolean expectsJson(RoutingContext ctx) {
        String accept = ctx.request().getHeader("Accept");

        // $this->ajax() && ! $this->pjax() && $this->acceptsAnyContentType()
        boolean ajax = "XMLHttpRequest".equals(ctx.request().getHeader("X-Requested-With"));
        boolean pjax = "true".equalsIgnoreCase(ctx.request().getHeader("X-PJAX"));
        boolean anyContentType = acceptsAnyContentType(accept);

        if (ajax && !pjax && anyContentType) {
            return true;
        }

        return wantsJson(accept);
    }

    /** {@code wantsJson()}：排序后第一项含 {@code /json} 或 {@code +json}。 */
    static boolean wantsJson(String accept) {
        List<String> acceptable = acceptableContentTypes(accept);

        return !acceptable.isEmpty()
                && containsJson(acceptable.get(0).toLowerCase(java.util.Locale.ROOT));
    }

    /** {@code acceptsAnyContentType()}：没有 Accept 头，或第一项是 {@code * / *} / {@code *}。 */
    static boolean acceptsAnyContentType(String accept) {
        List<String> acceptable = acceptableContentTypes(accept);

        if (acceptable.isEmpty()) {
            return true;
        }
        String first = acceptable.get(0);

        return "*/*".equals(first) || "*".equals(first);
    }

    /** {@code getAcceptableContentTypes()}：canonical key 按质量降序。 */
    static List<String> acceptableContentTypes(String accept) {
        if (accept == null || accept.isBlank()) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        for (AcceptHeaderItem item : AcceptHeader.fromString(accept).all()) {
            out.add(item.getValue());
        }
        return out;
    }

    private static boolean containsJson(String value) {
        return value.contains("/json") || value.contains("+json");
    }

    // ------------------------------------------------------------------ 变量

    /**
     * 当前登录用户（渲染错误页的 FAQ 链接要用）。
     *
     * <p>失败路径上会话可能压根没建立（例如全局栈早期失败），所以这里全部容错：
     * 读不到就当匿名。</p>
     */
    private UserRepository.UserRow currentUser(SessionStore session) {
        if (session == null || users == null) {
            return null;
        }
        Object uid = session.get(com.neoskin.web.auth.SessionGuard.SESSION_KEY);
        if (!(uid instanceof Number number)) {
            return null;
        }
        try {
            return users.findById(number.intValue());
        } catch (java.sql.SQLException e) {
            return null;
        }
    }

    /** 请求根（含 scheme 与 host）。 */
    static String urlRoot(RoutingContext ctx) {
        String host = ctx.request().getHeader("Host");

        return "http://" + (host == null || host.isEmpty() ? "localhost" : host);
    }

    /**
     * Laravel 的 {@code Request::path()}：无前导斜杠，根路径是 {@code "/"}。
     *
     * <p>注意尾斜杠会被保留（{@code /nope/} → {@code nope}，
     * {@code /nope//} 也是 {@code nope}），而中间的双斜杠<b>不</b>折叠
     * （{@code /auth//login} → {@code auth//login}）—— 与源实测一致。</p>
     */
    static String laravelPath(RoutingContext ctx) {
        String uri = ctx.request().path();
        if (uri == null || uri.isEmpty() || "/".equals(uri)) {
            return "/";
        }
        String trimmed = uri.replaceAll("/+$", "");
        if (trimmed.isEmpty()) {
            return "/";
        }
        return trimmed.replaceFirst("^/+", "");
    }

    /** 当前 query 参数（语言切换链接要保留）。 */
    static Map<String, String> query(RoutingContext ctx) {
        Object bag = ctx.get(RequestInput.QUERY_KEY);
        Map<String, String> out = new LinkedHashMap<>();
        if (bag instanceof com.neoskin.support.PhpJson.Array array) {
            array.forEach((k, v) -> out.put(String.valueOf(k),
                    v == null ? "" : com.neoskin.support.PhpString.cast(v)));
        }
        return out;
    }

    /**
     * JSON 错误体。
     *
     * <p>源的 {@code convertExceptionToArray()} 被项目覆写成恒返回
     * {@code {message, exception: true, trace: [...只留 app/ 下的帧]}}，
     * 与 {@code app.debug} 无关。这里保留同样三个键；trace 在 Java 侧没有可比的
     * 帧过滤语义（栈帧不是 PHP 的 {@code app/} 路径），固定为空表 —— 见 PORT-NOTES §13.3。</p>
     */
    static String jsonBody(WebException error, boolean expectsJson, Throwable failure) {
        return jsonBody(error, expectsJson, failure, true);
    }

    static String jsonBody(WebException error, boolean expectsJson, Throwable failure,
            boolean debug) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        // 源：404（路由未命中）trace 为空；500 仅在 app.debug=true 时输出应用栈帧
        if (!debug || failure == null || error.status() < 500) {
            return ErrorPages.jsonBody(message);
        }

        return ErrorPages.jsonBody(message, traceFrames(failure));
    }

    /**
     * 源 {@code Handler::convertExceptionToArray()} 的 trace 过滤：只保留
     * {@code app/} 下的帧，映射为 {@code [{file,line}]}。Java 帧按
     * {@code com.neoskin.} 前缀对应（插件/框架栈帧被过滤）。
     */
    static List<Map<String, Object>> traceFrames(Throwable failure) {
        List<Map<String, Object>> frames = new java.util.ArrayList<>();
        for (StackTraceElement frame : failure.getStackTrace()) {
            String className = frame.getClassName();
            if (!className.startsWith("com.neoskin.") || frame.getLineNumber() < 0) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("file", className.replace('.', '/') + ".java");
            item.put("line", frame.getLineNumber());
            frames.add(item);
        }

        return frames;
    }
}
