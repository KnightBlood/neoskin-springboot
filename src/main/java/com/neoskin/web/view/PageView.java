package com.neoskin.web.view;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.db.UserRepository.UserRow;
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.Option;
import com.neoskin.support.PhpJson;
import com.neoskin.web.PhpUrlGenerator;
import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.Translator;

/**
 * 后台 / 用户中心页面的公共变量装配（{@code *.base} + 全部共享 composer）。
 */
public final class PageView {

    /** 未读通知来源（{@code ViewServiceProvider} 的 {@code shared.notifications} composer）。 */
    @FunctionalInterface
    public interface InboxProvider {
        List<Map<String, Object>> unread(UserRow user) throws Exception;
    }

    private static volatile InboxProvider inboxProvider;

    /** 组合根（{@code WebConfig}）在启动时注入；未注入时通知下拉为空。 */
    public static void setInboxProvider(InboxProvider provider) {
        inboxProvider = provider;
    }

    private PageView() {
    }

    /**
     * 装配页面公共变量。
     *
     * @param locale      当前语言（{@code zh_CN}）
     * @param urlRoot     {@code url('/')} 的结果
     * @param requestPath {@code request()->path()}
     * @param scope       {@code user} / {@code admin}
     */
    public static Map<String, Object> variables(
            Option option, Translator translator, JavaScript javaScript, BlessingFilter filter,
            UserRow user, String locale, String urlRoot, String requestPath,
            boolean debug, String scope, ViewContext view) throws Exception {

        Map<String, Object> v = new LinkedHashMap<>();
        boolean darkMode = user != null && user.darkMode();

        v.putAll(SharedViewData.base(option, locale, darkMode));
        v.putAll(SharedViewData.head(option, locale, urlRoot, requestPath, debug));
        v.putAll(SharedViewData.foot(option, javaScript, locale, requestPath));
        v.putAll(SharedViewData.copyright(option));
        v.putAll(SharedLayout.sidebar(option, darkMode));
        v.putAll(SharedLayout.darkMode(darkMode));
        v.putAll(SharedLayout.userPanel(user, view));
        v.putAll(UserMenu.variables(user, option, translator, filter,
                new RouteUrlGenerator(urlRoot, "http"), locale));

        // 侧边栏三段菜单
        v.put("userItems", SharedLayout.sideMenu("user", requestPath).get("items"));
        v.put("exploreItems", SharedLayout.sideMenu("explore", requestPath).get("items"));
        v.put("adminItems", SharedLayout.sideMenu("admin", requestPath).get("items"));

        // 语言菜单
        PhpUrlGenerator urlGen = new PhpUrlGenerator(urlRoot, "http");
        com.neoskin.web.i18n.LanguagesMenu.Result menu =
                SharedViewData.languages(requestPath, Map.of(), locale, urlGen);
        v.put("current", menu.current());
        v.put("langs", menu.langs());

        // 未读通知（shared.notifications）：composer 的等价实现
        List<Map<String, Object>> notifications = List.of();
        if (user != null && inboxProvider != null) {
            List<Map<String, Object>> unread = inboxProvider.unread(user);
            if (unread != null) {
                notifications = unread;
            }
        }
        v.put("notifications", notifications);
        v.put("notifications_json", PhpJson.encode(notifications, 0));

        v.put("scope", scope);
        v.put("view", view);
        return v;
    }
}