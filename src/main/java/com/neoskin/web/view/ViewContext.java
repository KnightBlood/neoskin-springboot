package com.neoskin.web.view;

import java.util.LinkedHashMap;
import java.util.Map;

import com.neoskin.service.Option;
import com.neoskin.web.PhpUrlGenerator;
import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.i18n.Translator;

/**
 * 视图函数的上下文：模板里能调用的东西都挂在这里。
 *
 * <p>源项目里这些是 TwigBridge 注册的全局函数（{@code trans()} /
 * {@code url()} / {@code route()} / {@code csrf_token()} /
 * {@code auth_check()} / {@code option()} 等），全量 109 个模板共用到
 * 10 个函数。本工程把它们收敛到一个对象里，由 {@link NeoSkinDialect}
 * 暴露给 Thymeleaf。</p>
 *
 * <p>不可变：每请求新建一个实例。</p>
 */
public final class ViewContext {

    private final Translator translator;

    private final Option option;

    private final PhpUrlGenerator url;

    private final RouteUrlGenerator routeUrl;

    private final String locale;

    private final String csrfToken;

    private final Object currentUser;

    private final com.neoskin.service.session.SessionStore session;

    public ViewContext(Translator translator, Option option, PhpUrlGenerator url,
            RouteUrlGenerator routeUrl, String locale, String csrfToken, Object currentUser,
            com.neoskin.service.session.SessionStore session) {
        this.translator = translator;
        this.option = option;
        this.url = url;
        this.routeUrl = routeUrl;
        this.locale = locale;
        this.csrfToken = csrfToken;
        this.currentUser = currentUser;
        this.session = session;
    }

    public String trans(String key) {
        return trans(key, Map.of());
    }

    public String trans(String key, Map<String, Object> replace) {
        if (translator == null) {
            return key;
        }
        return translator.trans(key, replace == null ? Map.of() : replace, locale);
    }

    /**
     * 单占位符的便捷重载：{@code trans(key, name, value)}。
     *
     * <p>模板里不能写 {@code T(java.util.Map).of(...)} —— Thymeleaf 的 OGNL
     * 方言默认禁止静态类型访问（报 {@code source is null for getProperty(null, "util")}）。
     * 所以把「一个键值对」提成方法，模板侧只传两个字符串。</p>
     */
    public String trans(String key, String name, Object value) {
        return trans(key, java.util.Collections.singletonMap(name, value));
    }

    public String url(String path) {
        return url == null ? path : url.to(path);
    }

    public String route(String name) {
        return route(name, Map.of(), true);
    }

    public String route(String name, Map<String, Object> params) {
        return route(name, params, true);
    }

    public String route(String name, Map<String, Object> params, boolean absolute) {
        if (routeUrl != null) {
            return routeUrl.route(name, params == null ? Map.of() : params, absolute);
        }
        return url(name);
    }

    public Object option(String key) {
        return option(key, null);
    }

    public Object option(String key, Object defaultValue) {
        return option == null ? defaultValue : option.get(key, defaultValue);
    }

    public Object optionLocalized(String key, Object defaultValue) {
        return option == null ? defaultValue : option.getLocalized(key, defaultValue);
    }

    public String csrfToken() {
        return csrfToken == null ? "" : csrfToken;
    }

    public String csrfField() {
        return "<input type=\"hidden\" name=\"_token\" value=\"" + csrfToken() + "\">";
    }

    public boolean authCheck() {
        return currentUser != null;
    }

    public Object authUser() {
        return currentUser;
    }

    /**
     * {@code old($key, $default)}：读闪存的 {@code _old_input}（{@code data_get}）。
     */
    public Object old(String key) {
        return old(key, null);
    }

    public Object old(String key, Object defaultValue) {
        Object raw = session == null ? null : session.get("_old_input");
        if (raw == null) {
            return defaultValue;
        }
        Object value = com.neoskin.support.PhpArr.get(raw, key, null);
        return value == null ? defaultValue : value;
    }

    /** {@code session_has($key)}。 */
    public boolean sessionHas(String key) {
        return session != null && session.has(key);
    }

    /** {@code session($key)}（读，不清除）。 */
    public Object session(String key) {
        return session == null ? null : session.get(key);
    }

    /**
     * {@code session_pull($key)}：读取并移除（{@code Session::pull()}）。
     * 视图渲染阶段调用会消费掉 flash，与源一致。
     */
    public Object sessionPull(String key) {
        if (session == null) {
            return null;
        }
        Object value = session.get(key);
        session.forget(key);
        return value;
    }

    public String locale() {
        return locale;
    }

    public String version() {
        return "6.0.11_patch";
    }

    public Map<String, Object> blessing() {
        Map<String, Object> blessing = new LinkedHashMap<>();
        blessing.put("version", version());
        blessing.put("locale", locale);
        blessing.put("base_url", url("/"));
        blessing.put("site_name", optionLocalized("site_name", "Blessing Skin"));
        return blessing;
    }
}
