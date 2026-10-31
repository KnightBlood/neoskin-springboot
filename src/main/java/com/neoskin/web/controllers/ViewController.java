package com.neoskin.web.controllers;

import java.util.LinkedHashMap;
import java.util.Map;

import com.neoskin.db.UserRepository;
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.Option;
import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.PhpUrlGenerator;
import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.Authenticate;
import com.neoskin.web.middleware.StartSession;
import com.neoskin.web.view.PageView;
import com.neoskin.web.view.ViewContext;
import com.neoskin.web.view.ViewEngine;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code Illuminate\Routing\ViewController}（{@code Route::view(...)}）的移植。
 *
 * <p>这类路由的 action 字符串不含模板名，必须用 uri 绑定模板（见
 * {@link HandlerResolver#resolve(String, String)}）。模板只有在 classpath 上真实存在时
 * 才返回处理器，未移植的视图继续保持 501。</p>
 *
 * <p>已移植：{@code admin/users}、{@code admin/players}、{@code admin/reports}、
 * {@code admin/i18n}、{@code admin/plugins/manage}、{@code admin/plugins/market}。
 * 其余（{@code auth/bind}、{@code user/oauth/manage}、{@code skinlib}、
 * {@code setup}、{@code setup/info}）待对应模板移植后再加入。</p>
 */
public final class ViewController {

    /** 页面布局：admin/user/skinlib 用 PageView，setup 用安装向导布局。 */
    private enum Kind {
        ADMIN, USER, SKINLIB, SETUP
    }

    /** uri → 模板 + 标题 + 布局 + 是否需要 {@code blessing-extra} 的 currentUser。 */
    private record PageSpec(String template, String titleKey, Kind kind,
            boolean currentUserExtra) {
    }

    private static final Map<String, PageSpec> PAGES = new LinkedHashMap<>();

    static {
        PAGES.put("admin/users", new PageSpec("admin/users", "general.user-manage",
                Kind.ADMIN, true));
        PAGES.put("admin/players", new PageSpec("admin/players", "general.player-manage",
                Kind.ADMIN, false));
        PAGES.put("admin/reports", new PageSpec("admin/reports", "general.report-manage",
                Kind.ADMIN, false));
        PAGES.put("admin/i18n", new PageSpec("admin/i18n", "general.i18n", Kind.ADMIN, false));
        PAGES.put("admin/plugins/manage", new PageSpec("admin/plugins",
                "general.plugin-manage", Kind.ADMIN, false));
        PAGES.put("admin/plugins/market", new PageSpec("admin/market",
                "general.plugin-market", Kind.ADMIN, false));

        PAGES.put("auth/bind", new PageSpec("auth/bind", "auth.bind.title", Kind.USER, false));
        PAGES.put("user/oauth/manage", new PageSpec("user/oauth", "general.oauth-manage",
                Kind.USER, false));
        PAGES.put("skinlib", new PageSpec("skinlib/index", "general.skinlib",
                Kind.SKINLIB, true));
        PAGES.put("setup", new PageSpec("setup/wizard/welcome",
                "setup.wizard.welcome.title", Kind.SETUP, false));
        PAGES.put("setup/info", new PageSpec("setup/wizard/info",
                "setup.wizard.info.title", Kind.SETUP, false));
    }

    private final Option option;
    private final Translator translator;
    private final JavaScript javaScript;
    private final BlessingFilter filter;
    private final UserRepository users;
    private final boolean debug;
    private final String version;

    public ViewController(Option option, Translator translator, JavaScript javaScript,
            BlessingFilter filter, UserRepository users, boolean debug, String version) {
        this.option = option;
        this.translator = translator;
        this.javaScript = javaScript;
        this.filter = filter;
        this.users = users;
        this.debug = debug;
        this.version = version;
    }

    /** 按 uri 返回处理器；未移植的视图返回 {@code null}（兜底 501）。 */
    public Handler<RoutingContext> handlerFor(String uri) {
        String normalized = uri == null ? "" : uri.replaceAll("^/+", "");
        PageSpec spec = PAGES.get(normalized);
        if (spec == null || !templateExists(spec.template())) {
            return null;
        }

        return ctx -> render(ctx, spec);
    }

    private static boolean templateExists(String template) {
        return ViewController.class.getResource("/views/" + template + ".html") != null;
    }

    private void render(RoutingContext ctx, PageSpec spec) {
        try {
            if (spec.kind() == Kind.SETUP) {
                SetupPages.render(ctx, option, translator, spec.template(), version);
                return;
            }
            String locale = translator.getLocale();
            String host = hostOf(ctx);
            String urlRoot = "http://" + host;
            SessionStore session = ctx.get(StartSession.CTX_KEY);
            UserRepository.UserRow user = Authenticate.currentUser(ctx);
            if (user == null && session != null) {
                // 这些视图路由没有 authorize 中间件（如 /skinlib），需要用会话守卫补当前用户
                try {
                    user = new com.neoskin.web.auth.SessionGuard(session, users).user();
                } catch (Exception ignored) {
                    user = null;
                }
            }
            ViewContext vc = new ViewContext(translator, option,
                    new PhpUrlGenerator(urlRoot, "http"),
                    RouteUrlGenerator.forHost(host), locale,
                    session == null ? "" : String.valueOf(session.token()), user, session);

            String scope = switch (spec.kind()) {
                case SKINLIB -> "skinlib";
                case USER -> "user";
                default -> "admin";
            };
            Map<String, Object> vars = PageView.variables(option, translator, javaScript,
                    filter, user, locale, urlRoot, ctx.request().path(), debug, scope, vc);
            vars.put("pageTitle", translator.trans(spec.titleKey(), Map.of(), locale));
            vars.put("errors", new AuthController.ErrorsView(
                    com.neoskin.web.middleware.ShareErrorsFromSession.extract(
                            session == null ? null : session.get("errors"))));

            if (spec.currentUserExtra()) {
                Map<String, Object> extra = new LinkedHashMap<>();
                if (spec.kind() == Kind.SKINLIB) {
                    extra.put("currentUid", user == null ? null : user.uid());
                } else if (user != null) {
                    extra.put("currentUser", users.findJsonById(user.uid()));
                }
                vars.put("extra_json", PhpJson.encode(extra, 0));
            }

            String html = ViewEngine.render(spec.template(), java.util.Locale.ROOT, vars);
            ctx.response().setStatusCode(200)
                    .putHeader("Content-Type", "text/html; charset=utf-8")
                    .end(Buffer.buffer(html, "UTF-8"));
        } catch (Exception e) {
            ctx.fail(e);
        }
    }

    private static String hostOf(RoutingContext ctx) {
        String host = ctx.request().getHeader("Host");

        return host == null || host.isEmpty() ? "localhost" : host;
    }

    public static HandlerResolver resolver(Option option, Translator translator,
            JavaScript javaScript, BlessingFilter filter, UserRepository users, boolean debug,
            String version) {
        ViewController c = new ViewController(option, translator, javaScript, filter, users,
                debug, version);

        return new HandlerResolver() {
            @Override
            public Handler<RoutingContext> resolve(String action) {
                return null; // action 不足以确定模板
            }

            @Override
            public Handler<RoutingContext> resolve(String action, String uri) {
                if (!"Illuminate\\Routing\\ViewController".equals(action)) {
                    return null;
                }

                return c.handlerFor(uri);
            }
        };
    }
}
