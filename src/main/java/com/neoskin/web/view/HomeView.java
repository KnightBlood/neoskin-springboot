package com.neoskin.web.view;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.service.Option;
import com.neoskin.web.PhpUrlGenerator;
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.Translator;

/**
 * 首页（{@code home}）的视图变量装配。
 *
 * <p>合并了源里散落三处的逻辑：{@code HomeController::index()} 的
 * {@code with()}、{@code ViewServiceProvider} 挂在 {@code 'home'} 上的
 * 匿名 composer、以及 {@code shared.head} / {@code shared.foot} /
 * {@code shared.copyright} 三个 composer。后三者走 {@link SharedViewData}。</p>
 */
public final class HomeView {

    /** {@code config('options.home_pic_url')} 的默认值。 */
    private static final String DEFAULT_HOME_PIC_URL = "./app/bg.webp";

    /** 首页三个特性条目（{@code home.twig} 里 {% for item in [...] %}）。 */
    private static final List<String> FEATURE_ITEMS = List.of("first", "second", "third");

    private HomeView() {
    }

    /**
     * 装配 {@code home} 模板需要的全部变量。
     *
     * @param option      选项服务
     * @param translator  翻译器（{@code index.introduction} 的占位符替换）
     * @param javaScript  前端语言包服务；{@code null} 时 {@code i18n} 为空串
     * @param locale      当前应用语言，例如 {@code zh_CN}
     * @param urlRoot     {@code url('/')} 的结果
     * @param requestPath {@code request()->path()}
     * @return 视图变量表（值可能为 {@code null}，由 {@link ViewEngine} 转哨兵）
     */
    public static Map<String, Object> variables(
            Option option, Translator translator, JavaScript javaScript,
            com.neoskin.service.BlessingFilter filter,
            com.neoskin.db.UserRepository.UserRow user,
            String locale, String urlRoot, String requestPath, boolean debug) throws Exception {

        Map<String, Object> v = new LinkedHashMap<>();

        // ---------- HomeController::index() 的 with() ----------
        v.put("site_description", option.getLocalized("site_description", ""));
        v.put("transparent_navbar", option.getBoolean("transparent_navbar", false));
        v.put("fixed_bg", SharedViewData.falsyToNull(option.get("fixed_bg")));
        v.put("hide_intro", SharedViewData.falsyToNull(option.get("hide_intro")));
        v.put("home_pic_url",
                SharedViewData.phpOr(option.get("home_pic_url"), DEFAULT_HOME_PIC_URL));

        // ---------- 共享 composer ----------
        v.putAll(SharedViewData.base(option, locale, false));
        v.putAll(SharedViewData.head(option, locale, urlRoot, requestPath, debug));
        v.putAll(SharedViewData.foot(option, javaScript, locale, requestPath));
        v.putAll(SharedViewData.copyright(option));

        // ---------- home 模板自身的循环变量 ----------
        v.put("featureItems", FEATURE_ITEMS);
        // index.introduction 的 :sitename 占位符在装配阶段替换好
        // （Twig 的 {sitename: site_name} map 字面量在 Thymeleaf 里没有等价语法）
        v.put("intro", introText(translator, locale, v.get("site_name")));
        // window.blessing-extra 的内容（{transparent_navbar: ...}|json_encode）
        v.put("blessingExtra", blessingExtra(option));

        // ---------- 语言菜单（shared.languages） ----------
        PhpUrlGenerator urlGen = new PhpUrlGenerator(urlRoot, "http");
        com.neoskin.web.i18n.LanguagesMenu.Result menu =
                SharedViewData.languages(requestPath, Map.of(), locale, urlGen);
        v.put("current", menu.current());
        v.put("langs", menu.langs());

        // ---------- 用户菜单（UserMenuComposer） ----------
        // 未登录时 UserMenu 会给空值，模板用 view.authCheck() 守卫
        v.putAll(com.neoskin.web.view.UserMenu.variables(user, option, translator, filter,
                new com.neoskin.web.RouteUrlGenerator(urlRoot, "http"), locale));
        v.put("dark_mode", user != null && user.darkMode());

        return v;
    }

    /**
     * {@code home.twig} 的
     * {@code trans('index.introduction', {sitename: site_name})}。
     */
    private static String introText(Translator translator, String locale, Object siteName) {
        return translator == null ? ""
                : translator.trans("index.introduction",
                        Map.of("sitename", siteName == null ? "" : String.valueOf(siteName)), locale);
    }

    /**
     * {@code home.twig} 末尾的
     * {@code {{ {transparent_navbar: transparent_navbar}|json_encode|raw }}}。
     */
    private static String blessingExtra(Option option) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("transparent_navbar", option.getBoolean("transparent_navbar", false));
        return com.neoskin.support.PhpJson.encode(extra, 0);
    }
}
