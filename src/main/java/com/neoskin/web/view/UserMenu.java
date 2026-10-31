package com.neoskin.web.view;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.db.UserRepository.UserRow;
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.Option;
import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.i18n.Translator;

/**
 * {@code App\Http\View\Composers\UserMenuComposer} 的移植。
 *
 * <p>源实现往视图塞四个变量（{@code shared.user-menu} 用）：</p>
 * <pre>
 * $avatarUrl    = route('avatar.texture', ['tid' =&gt; $user-&gt;avatar, 'size' =&gt; 36], false);
 * $avatar       = $filter-&gt;apply('user_avatar', $avatarUrl, [$user]);
 * $avatarPNG    = route('avatar.texture', ['tid' =&gt; ..., 'png' =&gt; true], false);
 * $avatarPNG    = $filter-&gt;apply('user_avatar', $avatarPNG, [$user]);
 *
 * $menuItems = [
 *     ['label' =&gt; trans('general.user-center'), 'link' =&gt; route('user.home')],
 *     ['label' =&gt; trans('general.profile'),     'link' =&gt; route('user.profile.view')],
 * ];
 * if ($user-&gt;isAdmin()) { 追加 分隔线 + 管理面板 + 用户管理 + 举报管理 + 'Web CLI' }
 * $menuItems = $filter-&gt;apply('user_menu', $menuItems, [$user]);
 * </pre>
 *
 * <p>两个容易做错的点：</p>
 * <ol>
 *   <li>avatar 的两个 URL 用 {@code $absolute = false}（**相对路径**），
 *       所以是 {@code /avatar/0?size=36} 而不是带域名；</li>
 *   <li>分隔线的表示是 {@code link === '#divider'}（label 为空串），模板据此渲染
 *       {@code <div class="dropdown-divider">}。</li>
 * </ol>
 */
public final class UserMenu {

    /** 分隔线占位（源 {@code ['label' =&gt; '', 'link' =&gt; '#divider']}）。 */
    public static final String DIVIDER = "#divider";

    private UserMenu() {
    }

    /**
     * 生成用户菜单变量。
     *
     * @return {@code user / avatar / avatar_png / menu / user_nickname} 五个键
     */
    public static Map<String, Object> variables(UserRow user, Option option,
            Translator translator, BlessingFilter filter, RouteUrlGenerator routeUrl,
            String locale) {

        Map<String, Object> v = new LinkedHashMap<>();
        if (user == null) {
            v.put("user_nickname", null);
            v.put("avatar", "");
            v.put("avatar_png", "");
            v.put("menu", List.of());
            return v;
        }

        // route('avatar.texture', [...], false) —— 注意 relative
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tid", user.avatar());
        params.put("size", 36);
        String avatar = routeUrl.route("avatar.texture", params, false);

        Object filteredAvatar = filter == null ? avatar
                : filter.apply("user_avatar", avatar, List.of(user));
        String avatarUrl = filteredAvatar == null ? avatar : String.valueOf(filteredAvatar);

        Map<String, Object> pngParams = new LinkedHashMap<>(params);
        pngParams.put("png", true);
        String avatarPng = routeUrl.route("avatar.texture", pngParams, false);
        Object filteredPng = filter == null ? avatarPng
                : filter.apply("user_avatar", avatarPng, List.of(user));
        String avatarPngUrl = filteredPng == null ? avatarPng : String.valueOf(filteredPng);

        List<Map<String, Object>> menu = new ArrayList<>();
        menu.add(item(trans(translator, "general.user-center", locale),
                routeUrl.route("user.home", Map.of(), false)));
        menu.add(item(trans(translator, "general.profile", locale),
                routeUrl.route("user.profile.view", Map.of(), false)));

        if (user.isAdmin()) {
            menu.add(item("", DIVIDER));
            menu.add(item(trans(translator, "general.admin-panel", locale),
                    routeUrl.route("admin.view", Map.of(), false)));
            menu.add(item(trans(translator, "general.user-manage", locale),
                    routeUrl.route("admin.users.view", Map.of(), false)));
            menu.add(item(trans(translator, "general.report-manage", locale),
                    routeUrl.route("admin.reports.view", Map.of(), false)));
            menu.add(item("Web CLI", "#launch-cli"));
        }

        if (filter != null) {
            Object filtered = filter.apply("user_menu", menu, List.of(user));
            if (filtered instanceof List<?> list) {
                menu = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> map) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        map.forEach((k, value) -> copy.put(String.valueOf(k), value));
                        menu.add(copy);
                    }
                }
            }
        }

        v.put("user_nickname", nicknameOf(user));
        v.put("avatar", avatarUrl);
        v.put("avatar_png", avatarPngUrl);
        v.put("menu", menu);
        v.put("user_dark_mode", user.darkMode());
        return v;
    }

    /** {@code {{ user.nickname ?? user.email }}} 的 PHP {@code ??} 语义（空串也算有值）。 */
    static String nicknameOf(UserRow user) {
        String nickname = user.nickname();
        return nickname != null ? nickname : user.email();
    }

    private static Map<String, Object> item(String label, String link) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("link", link);
        return item;
    }

    private static String trans(Translator translator, String key, String locale) {
        return translator == null ? key : translator.trans(key, Map.of(), locale);
    }
}
