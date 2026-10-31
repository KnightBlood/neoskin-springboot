package com.neoskin.web.controllers;

import java.util.List;
import java.util.Map;

import com.neoskin.service.Option;
import com.neoskin.support.PhpArr;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.PhpUrlGenerator;
import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.view.HomeView;
import com.neoskin.web.view.ViewContext;
import com.neoskin.web.view.ViewEngine;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\HomeController} 的移植。
 */
public final class HomeController {

    private static final List<String> COPYRIGHTS = List.of(
            "Powered with ❤ by Blessing Skin Server.",
            "Powered by Blessing Skin Server.",
            "Proudly powered by Blessing Skin Server.",
            "由 Blessing Skin Server 强力驱动。",
            "采用 Blessing Skin Server 搭建。",
            "使用 Blessing Skin Server 稳定运行。",
            "自豪地采用 Blessing Skin Server。");

    private final Option option;

    private final Translator translator;

    private final JavaScript javaScript;

    /** {@code app.debug}：决定资源指向 dev-server 还是生产哈希。 */
    private final boolean debug;

    /** 登录态（首页要渲染用户菜单）。 */
    private final com.neoskin.web.auth.SessionGuard guard;

    private final com.neoskin.service.BlessingFilter filter;

    public HomeController(Option option, Translator translator, JavaScript javaScript) {
        this(option, translator, javaScript, null, null, false);
    }

    public HomeController(Option option, Translator translator, JavaScript javaScript,
            com.neoskin.web.auth.SessionGuard guard, com.neoskin.service.BlessingFilter filter,
            boolean debug) {
        this.option = option;
        this.translator = translator;
        this.javaScript = javaScript;
        this.guard = guard;
        this.filter = filter;
        this.debug = debug;
    }

    public Handler<RoutingContext> apiRoot() {
        return ctx -> {
            Object prefer = option.getLocalized("copyright_prefer", 0);
            Object copyright = PhpArr.get(COPYRIGHTS, prefer, null);

            PhpJson.Array body = new PhpJson.Array();
            body.put("blessing_skin", "6.0.11_patch");
            body.put("spec", 0);
            body.put("copyright", copyright);
            body.put("site_name", option.get("site_name"));

            String json = PhpJson.encode(body, 0);
            ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .putHeader("Cache-Control", "no-cache, private")
                    .end(Buffer.buffer(json));
        };
    }

    public Handler<RoutingContext> index() {
        return ctx -> {
            String locale = translator.getLocale();
            String urlRoot = "http://" + hostOf(ctx);
            String requestPath = ctx.request().path();
            String csrfToken = sessionToken(ctx);
            com.neoskin.service.session.SessionStore session =
                    ctx.get(com.neoskin.web.middleware.StartSession.CTX_KEY);

            // 先解析登录态：ViewContext 与模板变量都要用
            com.neoskin.db.UserRepository.UserRow user = null;
            if (guard != null) {
                try {
                    user = guard.user();
                } catch (java.sql.SQLException e) {
                    // 读用户失败不阻塞首页：按未登录渲染
                    user = null;
                }
            }

            ViewContext viewContext = new ViewContext(
                    translator, option,
                    new PhpUrlGenerator(urlRoot, "http"),
                    RouteUrlGenerator.forHost(hostOf(ctx)),
                    locale, csrfToken, user, session);

            Map<String, Object> vars;
            try {
                vars = HomeView.variables(option, translator, javaScript, filter, user,
                        locale, urlRoot, requestPath, debug);
            } catch (Exception e) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            vars.put("view", viewContext);

            String html = ViewEngine.render("home", java.util.Locale.ROOT, vars);
            ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "text/html; charset=utf-8")
                    .end(Buffer.buffer(html, "UTF-8"));
        };
    }

    private static String hostOf(RoutingContext ctx) {
        String h = ctx.request().getHeader("Host");
        return h == null || h.isEmpty() ? "localhost" : h;
    }

    private static String sessionToken(RoutingContext ctx) {
        Object session = ctx.get(com.neoskin.web.middleware.StartSession.CTX_KEY);
        if (session instanceof com.neoskin.service.session.SessionStore store) {
            return String.valueOf(store.token());
        }
        return "";
    }

    /** 供 HandlerResolver 使用的工厂。 */
    public static HandlerResolver resolver(Option option, Translator translator, JavaScript javaScript) {
        return resolver(option, translator, javaScript, null, null, false);
    }

    /** 带 {@code app.debug} 的工厂。 */
    public static HandlerResolver resolver(Option option, Translator translator,
            JavaScript javaScript, boolean debug) {
        return resolver(option, translator, javaScript, null, null, debug);
    }

    /** 完整工厂：带登录态与插件钩子。 */
    public static HandlerResolver resolver(Option option, Translator translator,
            JavaScript javaScript, com.neoskin.web.auth.SessionGuard guard,
            com.neoskin.service.BlessingFilter filter, boolean debug) {
        HomeController c = new HomeController(option, translator, javaScript, guard, filter, debug);
        return action -> switch (action) {
            case "App\\Http\\Controllers\\HomeController@apiRoot" -> c.apiRoot();
            case "App\\Http\\Controllers\\HomeController@index" -> c.index();
            default -> null;
        };
    }
}
