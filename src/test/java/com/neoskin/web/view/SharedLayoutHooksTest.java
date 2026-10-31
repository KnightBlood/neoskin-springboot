package com.neoskin.web.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.neoskin.db.UserRepository;
import com.neoskin.event.EventRuntime;
import com.neoskin.event.Events;
import com.neoskin.service.Hook;

/**
 * 菜单与用户面板的插件钩子接入：
 * {@code Configure*Menu} 事件、{@code RenderingBadges}、{@code user_badges} filter。
 */
class SharedLayoutHooksTest {

    @AfterEach
    void reset() {
        Hook.reset();
        EventRuntime.set(null);
    }

    @Test
    void pluginMenuItemAppearsInSideMenu() {
        Hook.addMenuItem("user", 0, Map.of("title", "Plugin Page", "link", "plugin/page"));

        Map<String, Object> menu = SharedLayout.sideMenu("user", "/user");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) menu.get("items");

        assertTrue(items.stream().anyMatch(item -> "Plugin Page".equals(item.get("title"))),
                "插件菜单项应出现在 user 侧边栏");
    }

    @Test
    void badgeFilterAndRenderingEventBothApply() {
        Hook.addUserBadge("VIP", "danger");
        EventRuntime.dispatcher().listen(Events.RenderingBadges.class,
                event -> event.badges().add(Map.of("text", "EVENT", "color", "info")));

        UserRepository.UserRow admin = new UserRepository.UserRow(1, "a@b.c", "admin", "x",
                UserRepository.ADMIN, 0, 0, true, false, "");
        ViewContext view = mock(ViewContext.class);
        when(view.route(anyString(), any(), anyBoolean())).thenReturn("/avatar");

        Map<String, Object> panel = SharedLayout.userPanel(admin, view);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> badges = (List<Map<String, Object>>) panel.get("badges");

        assertTrue(badges.stream().anyMatch(b -> "STAFF".equals(b.get("text"))));
        assertTrue(badges.stream().anyMatch(b -> "EVENT".equals(b.get("text"))),
                "RenderingBadges 监听者应能加徽章");
        assertTrue(badges.stream().anyMatch(b -> "VIP".equals(b.get("text"))),
                "user_badges filter 应能加徽章");
    }

    @Test
    void guestHasNoBadges() {
        ViewContext view = mock(ViewContext.class);
        Map<String, Object> panel = SharedLayout.userPanel(null, view);

        assertEquals(List.of(), panel.get("badges"));
    }

    @Test
    void menuHookSupportsExploreAndAdmin() {
        Hook.addMenuItem("explore", 99, Map.of("title", "Explore Plugin"));
        Hook.addMenuItem("admin", 0, Map.of("title", "Admin Plugin"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> explore = (List<Map<String, Object>>)
                SharedLayout.sideMenu("explore", "/explore").get("items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> admin = (List<Map<String, Object>>)
                SharedLayout.sideMenu("admin", "/admin").get("items");

        assertTrue(explore.stream().anyMatch(i -> "Explore Plugin".equals(i.get("title"))));
        assertTrue(admin.stream().anyMatch(i -> "Admin Plugin".equals(i.get("title"))));
    }
}
