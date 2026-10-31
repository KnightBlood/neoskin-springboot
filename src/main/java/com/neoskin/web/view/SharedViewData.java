package com.neoskin.web.view;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.service.Option;
import com.neoskin.web.PhpUrlGenerator;
import com.neoskin.web.i18n.JavaScript;

/**
 * 全部视图共享的变量装配 —— 对应源里挂在多个视图上的 view composer：
 *
 * <ul>
 *   <li>{@code ViewServiceProvider} 的匿名 composer（{@code home} / {@code *.base} /
 *       {@code *.master} / {@code shared.header}）→ {@link #base}；</li>
 *   <li>{@code HeadComposer} → {@link #head}；</li>
 *   <li>{@code FootComposer} → {@link #foot}；</li>
 *   <li>{@code shared.copyright} 的匿名 composer → {@link #copyright}。</li>
 * </ul>
 *
 * <p>这里不做「按视图名注册 composer」的通用分发 —— 目前只有 home 与 auth
 * 两组视图，直接调用更直白；等模板全量移植时再抽注册表。</p>
 */
public final class SharedViewData {

    /** {@code HeadComposer::applyThemeColor} 里的浅色集合（决定 color_mode）。 */
    private static final List<String> LIGHT_COLORS =
            List.of("light", "warning", "white", "orange");

    /** {@code HeadComposer::applyThemeColor} 的颜色表。 */
    private static final Map<String, String> THEME_COLORS = Map.ofEntries(
            Map.entry("primary", "#007bff"),
            Map.entry("secondary", "#6c757d"),
            Map.entry("success", "#28a745"),
            Map.entry("warning", "#ffc107"),
            Map.entry("danger", "#dc3545"),
            Map.entry("navy", "#001f3f"),
            Map.entry("olive", "#3d9970"),
            Map.entry("lime", "#01ff70"),
            Map.entry("fuchsia", "#f012be"),
            Map.entry("maroon", "#d81b60"),
            Map.entry("indigo", "#6610f2"),
            Map.entry("purple", "#6f42c1"),
            Map.entry("pink", "#e83e8c"),
            Map.entry("orange", "#fd7e14"),
            Map.entry("teal", "#20c997"),
            Map.entry("cyan", "#17a2b8"),
            Map.entry("gray", "#6c757d"));

    private SharedViewData() {
    }

    /**
     * {@code ViewServiceProvider} 挂在 {@code home} / {@code *.base} /
     * {@code *.master} / {@code shared.header} 上的匿名 composer。
     */
    public static Map<String, Object> base(Option option, String locale, boolean darkMode) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("site_name", option.getLocalized("site_name", "Blessing Skin"));
        String color = String.valueOf(option.get("navbar_color", "cyan"));
        v.put("navbar_color", color);
        v.put("color_mode", LIGHT_COLORS.contains(color) ? "light" : "dark");
        v.put("dark_mode", darkMode);
        // str_replace('_', '-', app()->getLocale())
        v.put("locale", locale == null ? "" : locale.replace('_', '-'));
        return v;
    }

    /** {@code HeadComposer} 的全部变量。 */
    public static Map<String, Object> head(Option option, String locale,
            String urlRoot, String requestPath) {
        return head(option, locale, urlRoot, requestPath, false);
    }

    /**
     * {@code HeadComposer} 的全部变量。
     *
     * @param debug {@code app.debug}：true 时资源指向 webpack dev-server
     *              （见 {@link AssetManifest} 与 PORT-NOTES 偏差 9.1）
     */
    public static Map<String, Object> head(Option option, String locale,
            String urlRoot, String requestPath, boolean debug) {
        Map<String, Object> v = new LinkedHashMap<>();
        String color = String.valueOf(option.get("navbar_color", "cyan"));

        v.put("favicon", favicon(option));
        v.put("theme_color", THEME_COLORS.get(color));

        Map<String, Object> seo = new LinkedHashMap<>();
        seo.put("keywords", falsyToNull(option.get("meta_keywords")));
        seo.put("description", falsyToNull(option.get("meta_description")));
        seo.put("extra", falsyToNull(option.get("meta_extras")));
        v.put("seo", seo);

        v.put("links", hookValue("head_links",
                new java.util.ArrayList<Map<String, Object>>(), requestPath));
        v.put("inline_css", falsyToNull(option.get("custom_css")));
        Object cdnRaw = falsyToNull(option.get("cdn_address"));
        String cdnHost = cdnRaw == null ? null : String.valueOf(cdnRaw);
        v.put("custom_cdn_host", cdnHost);
        // 布尔变量供模板判空（OGNL 对 NullValue 调 isEmpty() 会失败）
        v.put("has_cdn_host", cdnHost != null && !cdnHost.isBlank());
        v.put("blessing", blessing(option, locale, urlRoot, requestPath));
        // 源：事件 RenderingHeader 的监听者 addContent() 会拼进 extra_head
        java.util.List<String> headerContents = new java.util.ArrayList<>();
        com.neoskin.event.EventRuntime.fire(
                new com.neoskin.event.Events.RenderingHeader(headerContents));
        v.put("extra_head", headerContents.isEmpty() ? null
                : String.join("\n", headerContents));
        // 资源路径（替代源项目的 resources/views/assets/*.twig 构建产物）
        v.put("assets", AssetManifest.urls(debug,
                String.valueOf(option.get("cdn_address", ""))));
        return v;
    }

    /** {@code FootComposer} 的全部变量。 */
    public static Map<String, Object> foot(Option option, JavaScript javaScript, String locale)
            throws Exception {
        return foot(option, javaScript, locale, "/");
    }

    /** {@code FootComposer}（带请求路径：插件 {@code scripts} 钩子按页面匹配）。 */
    public static Map<String, Object> foot(Option option, JavaScript javaScript, String locale,
            String requestPath) throws Exception {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("i18n", javaScript == null ? "" : javaScript.generate(locale));
        v.put("scripts", hookValue("scripts",
                new java.util.ArrayList<Map<String, Object>>(), requestPath));
        v.put("inline_js", falsyToNull(option.get("custom_js")));
        // 源：事件 RenderingFooter 的监听者 addContent() 会拼进 extra_foot
        java.util.List<String> footerContents = new java.util.ArrayList<>();
        com.neoskin.event.EventRuntime.fire(
                new com.neoskin.event.Events.RenderingFooter(footerContents));
        v.put("extra_foot", footerContents.isEmpty() ? null
                : String.join("\n", footerContents));
        return v;
    }

    /** 插件钩子（{@code BlessingFilter}）取值；没有监听者时返回原列表。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> hookValue(String hook, List<Map<String, Object>> init,
            String requestPath) {
        Object result = com.neoskin.service.Hook.filter().apply(hook, init,
                List.of(requestPath == null ? "/" : requestPath));
        if (result instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }

        return init;
    }

    /** {@code shared.copyright} 的匿名 composer。 */
    public static Map<String, Object> copyright(Option option) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("copyright", option.getLocalized("copyright_prefer", 0));
        v.put("custom_copyright", copyrightText(option));
        v.put("site_url", option.get("site_url"));
        return v;
    }

    /**
     * {@code HeadComposer::addFavicon}：
     * {@code Str::startsWith($url, 'http') ? $url : url($url)}。
     */
    public static String favicon(Option option) {
        String url = String.valueOf(option.get("favicon_url", "app/favicon.ico"));
        return url.startsWith("http") ? url : "/" + url;
    }

    /**
     * {@code HeadComposer::serializeGlobals}。
     * 模板里 {@code th:inline="javascript"} + {@code [[${blessing}]]} 负责序列化。
     */
    public static Map<String, Object> blessing(
            Option option, String locale, String urlRoot, String requestPath) {
        Map<String, Object> blessing = new LinkedHashMap<>();
        blessing.put("version", "6.0.11_patch");
        blessing.put("locale", locale);
        blessing.put("base_url", urlRoot);
        blessing.put("site_name", option.getLocalized("site_name", "Blessing Skin"));
        // Laravel request()->path() 不带前导斜杠（根路径是 "/"），前端路由表
        // （route.tsx 的 'auth/login'）按无斜杠匹配；带斜杠会让 React 不挂载。
        blessing.put("route", laravelPath(requestPath));
        return blessing;
    }

    /** PHP 的真值判定：null / false / 0 / "" / "0" 为假。 */
    public static boolean isTruthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        String s = v.toString();
        return !s.isEmpty() && !s.equals("0");
    }

    /** 把 falsy 值统一成 {@code null}（模板里 {@code th:if} 更自然）。 */
    public static Object falsyToNull(Object v) {
        return isTruthy(v) ? v : null;
    }

    /** PHP 的 {@code ?:}（falsy 取右）。 */
    public static Object phpOr(Object left, Object right) {
        return isTruthy(left) ? left : right;
    }

    /**
     * {@code shared.copyright} 的 {@code custom_copyright}：
     * 源模板做 {@code |replace({'{site_name}':…, '{site_url}':…})}，
     * 这里在装配阶段替换好。
     */
    private static Object copyrightText(Option option) {
        Object raw = option.getLocalized("copyright_text", null);
        if (!isTruthy(raw)) {
            return null;
        }
        return String.valueOf(raw)
                .replace("{site_name}",
                        String.valueOf(option.getLocalized("site_name", "Blessing Skin")))
                .replace("{site_url}", String.valueOf(option.get("site_url", "")));
    }

    /**
     * 语言切换的路径形态：Laravel 的 {@code request()->path()} 根路径是
     * {@code "/"}，其余不带前导斜杠。
     */
    public static String laravelPath(String requestPath) {
        if (requestPath == null || requestPath.isEmpty()) {
            return "/";
        }
        if ("/".equals(requestPath)) {
            return "/";
        }
        return requestPath.replaceFirst("^/+", "");
    }

    /** 供 {@code LanguagesMenu} 的便捷入口。 */
    public static com.neoskin.web.i18n.LanguagesMenu.Result languages(
            String requestPath, Map<String, String> query, String locale, PhpUrlGenerator urlGen) {
        return com.neoskin.web.i18n.LanguagesMenu.compose(
                laravelPath(requestPath), query == null ? Map.of() : query, locale, urlGen);
    }
}
