package com.neoskin.web.error;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.service.Option;
import com.neoskin.support.PhpJson;
import com.neoskin.web.PhpUrlGenerator;
import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.i18n.LanguagesMenu;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.view.AssetManifest;
import com.neoskin.web.view.SharedViewData;
import com.neoskin.web.view.ViewContext;
import com.neoskin.web.view.ViewEngine;

/**
 * 错误页的渲染中枢 —— Laravel {@code Handler::render()} 里那套「挑模板 + 装变量」
 * 的等价实现。
 *
 * <h2>源的选择顺序（{@code getHttpExceptionView}）</h2>
 * <pre>
 * $view = 'errors::'.$status;                  // 先试精确码
 * if (view()->exists($view)) return $view;
 * $view = substr($view, 0, -2).'xx';           // 再试同族的 xx（如 4xx）
 * if (view()->exists($view)) return $view;
 * return null;                                 // 都没有 → Symfony 通用页
 * </pre>
 *
 * <p>注意 {@code errors::} 这个<b>命名空间</b>依次指向
 * {@code resources/views/errors}、{@code resources/views/overrides/errors}、
 * 以及 Laravel 自带的 {@code vendor/.../Exceptions/views}。所以
 * 401/402/419/429 会命中 Laravel 自带的 blade（{@code errors::minimal}），
 * 而 403/404/500/503 命中项目自己的 twig。本类把这条链固化下来。</p>
 *
 * <h2>为什么 403/404/500/503 之外还要留 Symfony 页</h2>
 * <p>405 在项目里没有模板，Laravel 自带的 {@code errors::405} 也不存在（只有
 * 401/402/403/404/419/429/500/503），于是落到
 * {@code renderExceptionWithSymfony()} —— 输出 Symfony 的
 * {@code error.html.php}。实测：{@code <title>An Error Occurred: Method Not Allowed</title>}，
 * 正文 {@code The server returned a "405 Method Not Allowed".}。这三个兜底页
 * （{@code errors/minimal.html}、{@code errors/symfony.html}）的字节是从 PHP
 * 真实响应里抠出来的，见测试 {@code ErrorPagesTest}。</p>
 */
public final class ErrorPages {

    /** 项目自带、走 {@code errors.base} 的状态码（源码 {@code resources/views/errors}）。 */
    private static final List<Integer> PROJECT_PAGES = List.of(403, 404, 500, 503);

    /** Laravel 自带 {@code errors::minimal} 家族的状态码。 */
    private static final Map<Integer, String> MINIMAL = Map.of(
            401, "Unauthorized",
            402, "Payment Required",
            419, "Page Expired",
            429, "Too Many Requests");

    private final Option option;

    private final Translator translator;

    private final boolean debug;

    public ErrorPages(Option option, Translator translator, boolean debug) {
        this.option = option;
        this.translator = translator;
        this.debug = debug;
    }

    /**
     * 渲染一个异常。
     *
     * @param error   异常（只能是 {@link WebException}）
     * @param urlRoot 请求根，如 {@code http://localhost}（绝对链接用）
     * @param path    Laravel 语义的 {@code Request::path()}（无前导斜杠，根为 {@code /}）
     * @param query   当前 query 参数（语言切换链接要保留它）
     * @param session 会话（CSRF token / 登录态）
     * @param user    当前登录用户；{@code null} 表示匿名
     * @return 渲染结果（状态码 + 头 + 正文）
     */
    public Rendered render(WebException error, String urlRoot, String path,
            Map<String, String> query,
            com.neoskin.service.session.SessionStore session,
            com.neoskin.db.UserRepository.UserRow user) {

        String locale = translator == null ? Option.currentLocale() : translator.getLocale();

        ViewContext viewContext = new ViewContext(translator, option,
                new PhpUrlGenerator(urlRoot, "http"),
                RouteUrlGenerator.forHost(hostOf(urlRoot)),
                locale, session == null ? "" : String.valueOf(session.token()),
                user, session);

        if (error.isPretty()) {
            return pretty(error, viewContext, locale, urlRoot, path, query);
        }

        int status = error.status();

        if (PROJECT_PAGES.contains(status)) {
            return projectPage(error, status, viewContext, locale, urlRoot, path, query);
        }

        if (MINIMAL.containsKey(status)) {
            return minimal(status, MINIMAL.get(status), error, viewContext);
        }

        return symfony(status, error);
    }

    /** {@code errors.{403,404,500,503}}：走 {@code errors.base} 的页。 */
    private Rendered projectPage(WebException error, int status, ViewContext viewContext,
            String locale, String urlRoot, String path, Map<String, String> query) {
        // 模板里的 {% block title %}404 Not Found{% endblock %} 是硬编码的英文，不翻译
        String title = status + " " + HttpStatus.text(status);

        // {{ trans('errors.exception.detail', {msg: exception.message ?: trans('errors.http.msg-404')}) }}
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            message = translator.trans("errors.http.msg-" + status, Map.of(), locale);
        }
        String detail = translator.trans("errors.exception.detail",
                Map.of("msg", message), locale);

        // 模板块里的换行与缩进逐字保留（见 PHP 实测的 404 输出）
        String body = "\n  <p>\n    " + PhpHtml.escape(detail) + "\n  </p>\n";

        Map<String, Object> vars = baseVariables(viewContext, locale, urlRoot, path, query);
        vars.put("title", title);
        vars.put("subtitle", title);
        vars.put("message", body);

        String html = ViewEngine.render("errors/base", java.util.Locale.ROOT, vars);
        return new Rendered(status, Map.of(), html, false);
    }

    /** {@code errors.pretty}：PrettyPageException 专用，状态码 200。 */
    private Rendered pretty(WebException error, ViewContext viewContext, String locale,
            String urlRoot, String path, Map<String, String> query) {
        String title = translator.trans("errors.general.title", Map.of(), locale);

        String codeLine = translator.trans("errors.exception.code",
                Map.of("code", error.code()), locale);
        String detail = translator.trans("errors.exception.detail",
                Map.of("msg", error.getMessage() == null ? "" : error.getMessage()), locale);

        // 源：{{ ...|nl2br }} —— pre_escape=html，先转义再插 <br />
        String body = "\n  <p>" + PhpHtml.escape(codeLine) + "</p>\n"
                + "  <p>" + PhpHtml.escapeNl2br(detail) + "</p>\n";

        Map<String, Object> vars = baseVariables(viewContext, locale, urlRoot, path, query);
        vars.put("title", title);
        vars.put("subtitle", title);
        vars.put("message", body);

        String html = ViewEngine.render("errors/base", java.util.Locale.ROOT, vars);
        return new Rendered(200, Map.of(), html, false);
    }

    /** Laravel {@code errors::minimal}（401/402/419/429）。 */
    private Rendered minimal(int status, String message, WebException error,
            ViewContext viewContext) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("title", message);
        vars.put("code", String.valueOf(status));
        vars.put("message", message);
        vars.put("view", viewContext);

        String html = ViewEngine.render("errors/minimal", java.util.Locale.ROOT, vars);
        return new Rendered(status, error.headers(), html, false);
    }

    /** Symfony {@code error.html.php}（405 等无模板可用的状态码）。 */
    private Rendered symfony(int status, WebException error) {
        String statusText = HttpStatus.text(status);

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("statusCode", String.valueOf(status));
        vars.put("statusText", statusText);
        // 源模板是 <?= ... ?> 直接拼，引号原样输出（没有 HTML 转义），
        // 所以这里预拼好再用 th:utext —— 用 th:text 会把 " 转成 &quot;。
        vars.put("heading", "The server returned a \"" + status + " " + statusText + "\".");

        String html = ViewEngine.render("errors/symfony", java.util.Locale.ROOT, vars);
        return new Rendered(status, error.headers(), html, false);
    }

    /**
     * {@code errors.*} 视图的公共变量。
     *
     * <p>源里 {@code errors.base} 用到 {@code styles} 与 {@code scripts} 两个数组，
     * 但它们<b>从来没有被赋值</b>（Twig 的 undefined → 空数组语义），所以这里固定给
     * 空表；{@code langs}/{@code current} 来自 {@code errors.*} 上挂的
     * {@code LanguagesMenuComposer}。</p>
     */
    private Map<String, Object> baseVariables(ViewContext viewContext, String locale,
            String urlRoot, String path, Map<String, String> query) {
        Map<String, Object> vars = new LinkedHashMap<>(SharedViewData.base(option, locale, false));

        vars.put("styles", List.of());
        vars.put("scripts", List.of());
        vars.put("assets", AssetManifest.urls(debug,
                String.valueOf(option.get("cdn_address", ""))));
        vars.put("view", viewContext);

        // errors.languages：与 shared.languages 同一个 composer，但用的是
        // errors.* 自己的模板（class 那行不一样）
        LanguagesMenu.Result menu = LanguagesMenu.compose(
                path == null || path.isEmpty() ? "/" : path,
                query == null ? Map.of() : query,
                locale, new PhpUrlGenerator(urlRoot, "http"));

        Map<String, Map<String, String>> langs = new LinkedHashMap<>();
        menu.langs().forEach((id, info) -> {
            Map<String, String> withCss = new LinkedHashMap<>(info);
            // lang.name == lang_name ? 'label-primary' : 'label-secondary'
            // lang_name 在源里从未定义 → 恒 null → 永远走 secondary
            withCss.put("css", "mx-2 p-1 label label-secondary");
            langs.put(id, withCss);
        });
        vars.put("langs", langs);

        return vars;
    }

    /** 从 {@code http://host} 里取回 host（RouteUrlGenerator 要）。 */
    private static String hostOf(String urlRoot) {
        if (urlRoot == null) {
            return "localhost";
        }
        int scheme = urlRoot.indexOf("://");
        return scheme < 0 ? urlRoot : urlRoot.substring(scheme + 3);
    }

    /** 渲染结果：状态码、额外响应头、正文。 */
    public record Rendered(int status, Map<String, String> headers, String body,
            boolean json) {
    }

    /** 供测试/工具构造 JSON 错误体（{@code prepareJsonResponse}）。 */
    public static String jsonBody(String message) {
        return jsonBody(message, List.of());
    }

    /**
     * 源 {@code Handler::convertExceptionToArray()} 的 JSON 形态：
     * {@code {message, exception:true, trace:[{file,line}]}}。
     */
    public static String jsonBody(String message, List<Map<String, Object>> trace) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        payload.put("exception", true);
        payload.put("trace", trace == null ? new PhpJson.Array() : trace);
        return PrettyJson.encode(payload);
    }
}
