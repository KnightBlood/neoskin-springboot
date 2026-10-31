package com.neoskin.web.view;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.db.UserRepository;
import com.neoskin.service.Option;

/**
 * 后台 / 用户中心共享布局的变量装配（对应源里的若干 view composer）：
 *
 * <ul>
 *   <li>{@code shared.sidebar} → {@link #sidebar}</li>
 *   <li>{@code shared.side-menu} → {@link #sideMenu}</li>
 *   <li>{@code shared.user-panel} → {@link #userPanel}</li>
 *   <li>{@code shared.notifications} → {@link #notifications}</li>
 *   <li>{@code shared.dark-mode} → {@link #darkMode}</li>
 * </ul>
 *
 * <p>菜单数据来自源 {@code config/menu.php}；本阶段硬编码，插件钩子
 * （{@code side_menu} filter / {@code Configure*Menu} 事件）留待插件阶段接入。</p>
 */
public final class SharedLayout {

    private static final List<String> LIGHT_COLORS =
            List.of("light", "warning", "white", "orange");

    private SharedLayout() {
    }

    /** {@code shared.sidebar} 的 {@code sidebar_color}。 */
    public static Map<String, Object> sidebar(Option option, boolean darkMode) {
        Map<String, Object> v = new LinkedHashMap<>();
        String color = String.valueOf(option.get("sidebar_color", "dark"));
        if (darkMode) {
            color = color.replace("light", "dark");
        }
        v.put("sidebar_color", color);
        return v;
    }

    /** {@code shared.dark-mode}。 */
    public static Map<String, Object> darkMode(boolean darkMode) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("dark_mode", darkMode);
        return v;
    }

    /** {@code shared.user-panel}。 */
    public static Map<String, Object> userPanel(UserRepository.UserRow user, ViewContext view) {
        Map<String, Object> v = new LinkedHashMap<>();
        if (user == null) {
            v.put("user", null);
            v.put("avatar", null);
            v.put("avatar_png", null);
            v.put("panel_avatar", null);
            v.put("panel_avatar_png", null);
            v.put("panel_nickname", null);
            v.put("panel_email", null);
            v.put("badges", List.of());
            return v;
        }
        v.put("user", user);
        v.put("avatar", view.route("avatar.texture",
                Map.of("tid", user.avatar(), "size", 45), false));
        v.put("avatar_png", view.route("avatar.texture",
                Map.of("tid", user.avatar(), "size", 45, "png", true), false));
        v.put("panel_avatar", view.route("avatar.texture",
                Map.of("tid", user.avatar(), "size", 45), false));
        v.put("panel_avatar_png", view.route("avatar.texture",
                Map.of("tid", user.avatar(), "size", 45, "png", true), false));
        v.put("panel_nickname", user.nickname());
        v.put("panel_email", user.email());
        List<Map<String, Object>> badges = new ArrayList<>();
        if (user.isAdmin()) {
            Map<String, Object> badge = new LinkedHashMap<>();
            badge.put("text", "STAFF");
            badge.put("color", "primary");
            badges.add(badge);
        }
        // 源 UserPanelComposer：先派发 RenderingBadges，再 apply('user_badges', ..., [$user])
        com.neoskin.event.EventRuntime.fire(
                new com.neoskin.event.Events.RenderingBadges(badges));
        Object filtered = com.neoskin.service.Hook.filter().apply("user_badges", badges,
                List.of(user));
        if (filtered instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> result = (List<Map<String, Object>>) list;
            badges = result;
        }
        v.put("badges", badges);
        return v;
    }

    /** {@code shared.side-menu}：按 {@code type}（user / explore / admin）取菜单。 */
    public static Map<String, Object> sideMenu(String type, String requestPath) {
        List<Map<String, Object>> raw = menu(type);

        // 源 SideMenuComposer：先派发 Configure*Menu 事件（插件 Hook.addMenuItem 在此生效）
        Map<String, List<Map<String, Object>>> configured = new LinkedHashMap<>();
        configured.put(type, new ArrayList<>(raw));
        switch (type) {
            case "user" -> com.neoskin.event.EventRuntime.fire(
                    new com.neoskin.event.Events.ConfigureUserMenu(configured));
            case "admin" -> com.neoskin.event.EventRuntime.fire(
                    new com.neoskin.event.Events.ConfigureAdminMenu(configured));
            case "explore" -> com.neoskin.event.EventRuntime.fire(
                    new com.neoskin.event.Events.ConfigureExploreMenu(configured));
            default -> {
                // 未知分类：保持原样
            }
        }
        raw = configured.getOrDefault(type, raw);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> item : raw) {
            items.add(transform(item, requestPath));
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("items", items);
        return v;
    }

    private static Map<String, Object> transform(Map<String, Object> item, String requestPath) {
        Map<String, Object> out = new LinkedHashMap<>(item);
        String link = (String) item.get("link");
        boolean active = link != null && matches(requestPath, link);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) item.get("children");
        List<Map<String, Object>> transformedChildren = new ArrayList<>();
        if (children != null) {
            for (Map<String, Object> child : children) {
                Map<String, Object> t = transform(child, requestPath);
                if (Boolean.TRUE.equals(t.get("active"))) {
                    active = true;
                }
                transformedChildren.add(t);
            }
            out.put("children", transformedChildren);
        }
        List<String> classes = new ArrayList<>();
        if (active) {
            out.put("active", true);
            classes.add("active menu-open");
        }
        out.put("classes", classes);
        String icon = (String) item.get("icon");
        if (icon != null) {
            out.put("iconClass", (icon.equals("fa-circle") ? "far " : "fas ") + icon);
        }
        return out;
    }

    /** {@code $request->is($link)}：精确或前缀匹配。 */
    private static boolean matches(String path, String link) {
        if (path == null) {
            return false;
        }
        String p = path.replaceFirst("^/+", "");
        String l = link.replaceFirst("^/+", "");
        return p.equals(l) || p.startsWith(l + "/");
    }

    private static List<Map<String, Object>> menu(String type) {
        return switch (type) {
            case "user" -> userMenu();
            case "admin" -> adminMenu();
            case "explore" -> exploreMenu();
            default -> List.of();
        };
    }

    private static Map<String, Object> item(String title, String link, String icon) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("link", link);
        m.put("icon", icon);
        return m;
    }

    private static Map<String, Object> group(String title, String icon,
            List<Map<String, Object>> children) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("icon", icon);
        m.put("children", children);
        return m;
    }

    private static List<Map<String, Object>> userMenu() {
        return List.of(
                item("general.dashboard", "user", "fa-tachometer-alt"),
                item("站内信", "user/inbox", "fa-envelope"),
                item("general.my-closet", "user/closet", "fa-star"),
                item("general.player-manage", "user/player", "fa-users"),
                item("general.my-reports", "user/reports", "fa-flag"),
                item("general.profile", "user/profile", "fa-user"),
                group("general.developer", "fa-code-branch", List.of(
                        item("general.oauth-manage", "user/oauth/manage", "fa-feather-alt"))));
    }

    private static List<Map<String, Object>> adminMenu() {
        return List.of(
                item("general.dashboard", "admin", "fa-tachometer-alt"),
                item("通知与公告", "admin/communications", "fa-bullhorn"),
                item("general.user-manage", "admin/users", "fa-users"),
                item("general.player-manage", "admin/players", "fa-gamepad"),
                item("general.report-manage", "admin/reports", "fa-flag"),
                item("general.customize", "admin/customize", "fa-paint-brush"),
                item("general.i18n", "admin/i18n", "fa-globe"),
                item("general.score-options", "admin/score", "fa-credit-card"),
                item("general.options", "admin/options", "fa-cog"),
                item("general.res-options", "admin/resource", "fa-atom"),
                item("general.status", "admin/status", "fa-battery-three-quarters"),
                item("general.plugin-manage", "admin/plugins/manage", "fa-plug"),
                item("general.plugin-market", "admin/plugins/market", "fa-shopping-bag"),
                group("general.plugin-configs", "fa-cogs", List.of()),
                item("general.check-update", "admin/update", "fa-arrow-up"));
    }

    private static List<Map<String, Object>> exploreMenu() {
        return List.of(item("general.skinlib", "skinlib", "fa-archive"));
    }
}