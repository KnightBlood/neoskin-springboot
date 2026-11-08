package com.neoskin.web.controllers;

import java.util.LinkedHashMap;
import java.util.Map;

import com.neoskin.service.Option;
import com.neoskin.service.session.SessionStore;
import com.neoskin.web.PhpUrlGenerator;
import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.ShareErrorsFromSession;
import com.neoskin.web.middleware.StartSession;
import com.neoskin.web.view.ViewContext;
import com.neoskin.web.view.ViewEngine;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * 安装向导页面的公共变量与渲染（{@code setup.base} 不依赖 user/admin 布局）。
 */
public final class SetupPages {

    private SetupPages() {
    }

    /** setup 页面的变量：locale / view / cdn_base / errors / 语言菜单。 */
    public static Map<String, Object> variables(RoutingContext ctx, Option option,
            Translator translator, String version) {
        String locale = translator.getLocale();
        String host = ctx.request().getHeader("Host");
        if (host == null || host.isEmpty()) {
            host = "localhost";
        }
        String urlRoot = "http://" + host;
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        ViewContext view = new ViewContext(translator, option,
                new PhpUrlGenerator(urlRoot, "http"), RouteUrlGenerator.forHost(host), locale,
                session == null ? "" : String.valueOf(session.token()), null, session);

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("locale", locale);
        vars.put("view", view);
        vars.put("cdn_base", option.get("cdn_address", ""));
        vars.put("site_name", option.get("site_name", "NeoSkin"));
        vars.put("version", version == null || version.isEmpty()
                ? option.get("version", "6.0.11_patch") : version);
        vars.put("errors", new AuthController.ErrorsView(
                ShareErrorsFromSession.extract(session == null ? null : session.get("errors"))));

        com.neoskin.web.i18n.LanguagesMenu.Result menu = com.neoskin.web.view.SharedViewData
                .languages(ctx.request().path(), Map.of(), locale,
                        new PhpUrlGenerator(urlRoot, "http"));
        Map<String, Map<String, String>> langs = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : menu.langs().entrySet()) {
            Map<String, String> item = new LinkedHashMap<>(entry.getValue());
            item.put("css", "mx-2 p-1 label label-secondary");
            langs.put(entry.getKey(), item);
        }
        vars.put("langs", langs);
        vars.put("current", menu.current());

        return vars;
    }

    /** 渲染 setup 模板（{@code setup/wizard/*}、{@code setup/locked}）。 */
    public static void render(RoutingContext ctx, String template, Map<String, Object> vars) {
        String html = ViewEngine.render(template, java.util.Locale.ROOT, vars);
        ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(Buffer.buffer(html, "UTF-8"));
    }

    /** 供 ViewController 复用的 setup 页面渲染。 */
    public static void render(RoutingContext ctx, Option option, Translator translator,
            String template, String version) {
        render(ctx, template, variables(ctx, option, translator, version));
    }

    /** {@code Whip::getValidIpAddress()} 的近似：X-Forwarded-For 首项，否则远端地址。 */
    public static String clientIp(RoutingContext ctx) {
        String forwarded = ctx.request().getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String real = ctx.request().getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }

        return ctx.request().remoteAddress() == null ? "127.0.0.1"
                : ctx.request().remoteAddress().hostAddress();
    }


}
