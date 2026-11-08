package com.neoskin.web.view;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.service.BlessingFilter;
import com.neoskin.service.LoginFailCounter;
import com.neoskin.service.Option;
import com.neoskin.web.PhpUrlGenerator;
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.Translator;

/**
 * 认证页面（{@code auth.*}）的视图变量装配。
 *
 * <p>源 {@code AuthController::login}：</p>
 * <pre>
 * $rows = [
 *     'auth.rows.login.notice',
 *     'auth.rows.login.message',
 *     'auth.rows.login.form',
 *     'auth.rows.login.registration-link',
 * ];
 * $rows = $filter-&gt;apply('auth_page_rows:login', $rows);
 *
 * return view('auth.login', [
 *     'rows' =&gt; $rows,
 *     'extra' =&gt; [
 *         'tooManyFails' =&gt; cache(sha1('login_fails_'.$ip)) &gt; 3,
 *         'recaptcha' =&gt; option('recaptcha_sitekey'),
 *         'invisible' =&gt; (bool) option('recaptcha_invisible'),
 *     ],
 * ]);
 * </pre>
 *
 * <p>注意 {@code tooManyFails} 的字面语义：{@code cache(...) > 3} ——
 * 第 <b>4</b> 次失败之后才为真（不是 ≥3）。</p>
 */
public final class AuthView {

    /** 源里 login 页的默认 rows（顺序即渲染顺序）。 */
    private static final List<String> LOGIN_ROWS = List.of(
            "auth/rows/login/notice",
            "auth/rows/login/message",
            "auth/rows/login/form",
            "auth/rows/login/registration-link");

    private AuthView() {
    }

    /**
     * {@code auth.login} 的变量。
     *
     * @param clientIp 经 {@code client_ip} filter 处理后的客户端 IP
     */
    public static Map<String, Object> loginVariables(
            Option option, Translator translator, JavaScript javaScript,
            BlessingFilter filter, LoginFailCounter failures, String clientIp,
            String locale, String urlRoot, String requestPath, boolean debug) throws Exception {

        Map<String, Object> v = new LinkedHashMap<>();
        v.putAll(SharedViewData.base(option, locale, false));
        v.putAll(SharedViewData.head(option, locale, urlRoot, requestPath, debug));
        v.putAll(SharedViewData.foot(option, javaScript, locale, requestPath));
        v.putAll(SharedViewData.copyright(option));

        // 语言菜单（auth 页也要，shared.head 之外的 shared.languages 未用，
        // 但 *.base 的 composer 会带上，保持一致）
        PhpUrlGenerator urlGen = new PhpUrlGenerator(urlRoot, "http");
        com.neoskin.web.i18n.LanguagesMenu.Result menu =
                SharedViewData.languages(requestPath, Map.of(), locale, urlGen);
        v.put("current", menu.current());
        v.put("langs", menu.langs());

        // 标题：{% block title %}{{ trans('auth.login.title') }}{% endblock %}
        v.put("pageTitle", translator == null
                ? "" : translator.trans("auth.login.title", Map.of(), locale));

        // rows：默认 4 项，再走 filter（无插件时原样返回）
        Object rows = filter == null ? LOGIN_ROWS
                : filter.apply("auth_page_rows:login", LOGIN_ROWS);
        v.put("rows", toStringList(rows));

        // extra：{tooManyFails, recaptcha, invisible}
        v.put("extra", extraJson(option, failures, clientIp));

        return v;
    }

    private static List<String> toStringList(Object rows) {
        List<String> out = new ArrayList<>();
        if (rows instanceof List<?> list) {
            for (Object o : list) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    /**
     * {@code extra|json_encode|raw}。模板里放在
     * {@code <script id="blessing-extra" type="application/json">} 中。
     */
    /** {@code auth.register} 的变量。 */
    public static Map<String, Object> registerVariables(
            Option option, Translator translator, JavaScript javaScript,
            BlessingFilter filter, LoginFailCounter failures, String clientIp,
            String locale, String urlRoot, String requestPath, boolean debug) throws Exception {

        Map<String, Object> v = new LinkedHashMap<>();
        v.putAll(SharedViewData.base(option, locale, false));
        v.putAll(SharedViewData.head(option, locale, urlRoot, requestPath, debug));
        v.putAll(SharedViewData.foot(option, javaScript, locale, requestPath));
        v.putAll(SharedViewData.copyright(option));

        PhpUrlGenerator urlGen = new PhpUrlGenerator(urlRoot, "http");
        com.neoskin.web.i18n.LanguagesMenu.Result menu =
                SharedViewData.languages(requestPath, Map.of(), locale, urlGen);
        v.put("current", menu.current());
        v.put("langs", menu.langs());

        v.put("pageTitle", translator == null ? "" : translator.trans("auth.register.title", Map.of(), locale));

        List<String> rows = List.of("auth/rows/register/notice", "auth/rows/register/form");
        Object filtered = filter == null ? rows : filter.apply("auth_page_rows:register", rows);
        v.put("rows", toStringList(filtered));

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("player", option.getBoolean("register_with_player_name", false));
        extra.put("recaptcha", SharedViewData.falsyToNull(option.get("recaptcha_sitekey")));
        extra.put("invisible", option.getBoolean("recaptcha_invisible", false));
        v.put("extra", com.neoskin.support.PhpJson.encode(extra, 0));
        v.put("site_name", option.getLocalized("site_name", "NeoSkin"));

        return v;
    }

    /** {@code auth.forgot} 的变量。 */
    public static Map<String, Object> forgotVariables(
            Option option, Translator translator, JavaScript javaScript,
            String locale, String urlRoot, String requestPath, boolean debug) throws Exception {

        Map<String, Object> v = new LinkedHashMap<>();
        v.putAll(SharedViewData.base(option, locale, false));
        v.putAll(SharedViewData.head(option, locale, urlRoot, requestPath, debug));
        v.putAll(SharedViewData.foot(option, javaScript, locale, requestPath));
        v.putAll(SharedViewData.copyright(option));

        PhpUrlGenerator urlGen = new PhpUrlGenerator(urlRoot, "http");
        com.neoskin.web.i18n.LanguagesMenu.Result menu =
                SharedViewData.languages(requestPath, Map.of(), locale, urlGen);
        v.put("current", menu.current());
        v.put("langs", menu.langs());

        v.put("pageTitle", translator == null ? "" : translator.trans("auth.forgot.title", Map.of(), locale));

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("recaptcha", SharedViewData.falsyToNull(option.get("recaptcha_sitekey")));
        extra.put("invisible", option.getBoolean("recaptcha_invisible", false));
        v.put("extra", com.neoskin.support.PhpJson.encode(extra, 0));

        return v;
    }

    /** {@code auth.reset} / {@code auth.verify} / {@code auth.bind} 的基础变量。 */
    public static Map<String, Object> simpleVariables(
            Option option, Translator translator, JavaScript javaScript,
            String locale, String urlRoot, String requestPath, boolean debug,
            String titleKey) throws Exception {

        Map<String, Object> v = new LinkedHashMap<>();
        v.putAll(SharedViewData.base(option, locale, false));
        v.putAll(SharedViewData.head(option, locale, urlRoot, requestPath, debug));
        v.putAll(SharedViewData.foot(option, javaScript, locale, requestPath));
        v.putAll(SharedViewData.copyright(option));

        PhpUrlGenerator urlGen = new PhpUrlGenerator(urlRoot, "http");
        com.neoskin.web.i18n.LanguagesMenu.Result menu =
                SharedViewData.languages(requestPath, Map.of(), locale, urlGen);
        v.put("current", menu.current());
        v.put("langs", menu.langs());

        v.put("pageTitle", translator == null ? "" : translator.trans(titleKey, Map.of(), locale));
        return v;
    }

    private static String extraJson(Option option, LoginFailCounter failures, String clientIp) {
        int fails = failures == null ? 0
                : failures.get(LoginFailCounter.keyForIp(clientIp));

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("tooManyFails", fails > 3);
        extra.put("recaptcha", SharedViewData.falsyToNull(option.get("recaptcha_sitekey")));
        extra.put("invisible", option.getBoolean("recaptcha_invisible", false));
        return com.neoskin.support.PhpJson.encode(extra, 0);
    }
}
