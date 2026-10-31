package com.neoskin.web.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neoskin.db.UserRepository.UserRow;
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.Option;
import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.i18n.TranslationFileLoader;
import com.neoskin.web.i18n.Translator;

/**
 * {@link UserMenu}（{@code UserMenuComposer} 的移植）。
 *
 * <p>锁四条：</p>
 * <ol>
 *   <li>avatar 两个 URL 是<b>相对路径</b>（源 {@code route(..., false)}）；</li>
 *   <li>普通用户两项菜单；管理员多分隔线 + 三项 + Web CLI；</li>
 *   <li>分隔线用 {@code link == "#divider"} 表示；</li>
 *   <li>昵称空串时回落 email（PHP 的 {@code ??} 不是「空串取右」）。</li>
 * </ol>
 */
class UserMenuTest {

    private static final RouteUrlGenerator ROUTES =
            RouteUrlGenerator.forHost("localhost:8080");

    private static final Translator TRANSLATOR =
            new Translator(new TranslationFileLoader(), "zh_CN", "en");

    private static UserRow user(int avatar, int permission, String nickname, String email) {
        return new UserRow(1, email, nickname, "hash", permission, avatar, 0, false, false, null);
    }

    private Map<String, Object> menu(UserRow u) {
        return UserMenu.variables(u, null, TRANSLATOR, new BlessingFilter(), ROUTES, "zh_CN");
    }

    @Test
    void avatarUrlsAreRelative() {
        Map<String, Object> vars = menu(user(7, 0, "小明", "a@b.com"));

        assertEquals("/avatar/7?size=36", vars.get("avatar"));
        assertEquals("/avatar/7?size=36&png=true", vars.get("avatar_png"));
        assertTrue(!String.valueOf(vars.get("avatar")).startsWith("http"),
                "源用的是 route(..., false)，不能是绝对 URL");
    }

    @Test
    void normalUserHasTwoItems() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) menu(user(0, 0, "小明", "a@b.com")).get("menu");

        assertEquals(2, items.size());
        assertEquals("用户中心", items.get(0).get("label"));
        assertEquals("/user", items.get(0).get("link"));
        assertEquals("个人资料", items.get(1).get("label"));
        assertEquals("/user/profile", items.get(1).get("link"));
    }

    @Test
    void adminSeesExtraItems() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) menu(user(0, 1, "管理员", "a@b.com")).get("menu");

        // 2 普通 + 分隔线 + 管理面板 + 用户管理 + 举报管理 + Web CLI
        assertEquals(7, items.size(), items.toString());
        assertEquals(UserMenu.DIVIDER, items.get(2).get("link"));
        assertEquals("/admin", items.get(3).get("link"));
        assertEquals("/admin/users", items.get(4).get("link"));
        assertEquals("/admin/reports", items.get(5).get("link"));
        assertEquals("#launch-cli", items.get(6).get("link"));
        assertEquals("Web CLI", items.get(6).get("label"));
    }

    /** 封禁用户（permission = -1）不是管理员。 */
    @Test
    void bannedUserIsNotAdmin() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) menu(user(0, -1, "x", "x@y.com")).get("menu");
        assertEquals(2, items.size());
    }

    @Test
    void nicknameFallsBackToEmailWhenNull() {
        assertEquals("a@b.com", menu(user(0, 0, null, "a@b.com")).get("user_nickname"));
        // 空串是「有值」，PHP 的 ?? 不会回落
        assertEquals("", menu(user(0, 0, "", "a@b.com")).get("user_nickname"));
        assertEquals("小明", menu(user(0, 0, "小明", "a@b.com")).get("user_nickname"));
    }

    @Test
    void unauthenticatedGivesEmptyMenu() {
        Map<String, Object> vars = UserMenu.variables(
                null, null, TRANSLATOR, null, ROUTES, "zh_CN");
        assertEquals(List.of(), vars.get("menu"));
        assertEquals(null, vars.get("user_nickname"));
    }

    /** 插件钩子能改写菜单（{@code user_menu} filter）。 */
    @Test
    void filterCanAppendItems() {
        BlessingFilter filter = new BlessingFilter();
        filter.add("user_menu", (carry, args) -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) carry;
            java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
            extra.put("label", "插件项");
            extra.put("link", "/plugin");
            list.add(extra);
            return list;
        });

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) UserMenu.variables(
                user(0, 0, "x", "x@y.com"), null, TRANSLATOR, filter, ROUTES, "zh_CN")
                .get("menu");

        assertEquals(3, items.size());
        assertEquals("插件项", items.get(2).get("label"));
    }

    /** {@code option} 参数保留（当前实现不使用，但签名与源 composer 对齐）。 */
    @Test
    void optionParameterMayBeNull() {
        assertEquals("/avatar/0?size=36",
                menu(user(0, 0, "x", "x@y.com")).get("avatar"));
        Option unused = null;
        assertEquals(null, unused);
    }
}
