package com.neoskin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.neoskin.event.EventRuntime;
import com.neoskin.event.Events;

/**
 * {@link Hook} 的 7 个方法（源 {@code App\Services\Hook}）。
 */
class HookTest {

    @AfterEach
    void reset() {
        Hook.reset();
        EventRuntime.set(null);
    }

    @Test
    void addMenuItemInsertsAtPosition() {
        Hook.addMenuItem("user", 1, Map.of("title", "Plugin"));

        Map<String, List<Map<String, Object>>> menu = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("title", "A"));
        items.add(Map.of("title", "B"));
        menu.put("user", items);
        EventRuntime.fire(new Events.ConfigureUserMenu(menu));

        assertEquals(3, menu.get("user").size());
        assertEquals(Map.of("title", "A"), menu.get("user").get(0));
        assertEquals(Map.of("title", "Plugin"), menu.get("user").get(1));
        assertEquals(Map.of("title", "B"), menu.get("user").get(2));
    }

    @Test
    void addMenuItemAppendsWhenPositionBeyondEnd() {
        Hook.addMenuItem("admin", 99, Map.of("title", "Tail"));

        Map<String, List<Map<String, Object>>> menu = new LinkedHashMap<>();
        menu.put("admin", new ArrayList<>(List.of(Map.of("title", "A"))));
        EventRuntime.fire(new Events.ConfigureAdminMenu(menu));

        assertEquals(2, menu.get("admin").size());
        assertEquals(Map.of("title", "Tail"), menu.get("admin").get(1));
    }

    @Test
    void styleAndScriptHooksMatchPagePattern() {
        Hook.addStyleFileToPage(List.of("/plugins/x/style.css"), List.of("admin/*"));
        Hook.addScriptFileToPage(List.of("/plugins/x/script.js"), List.of("*"));

        Object links = Hook.filter().apply("head_links", new ArrayList<>(), List.of("/admin/plugins"));
        Object unmatched = Hook.filter().apply("head_links", new ArrayList<>(), List.of("/user"));
        Object scripts = Hook.filter().apply("scripts", new ArrayList<>(), List.of("/anything"));

        assertEquals(1, ((List<?>) links).size());
        assertEquals(0, ((List<?>) unmatched).size(), "不匹配的页面不注入");
        assertEquals(1, ((List<?>) scripts).size());
    }

    @Test
    void addUserBadgeAppends() {
        Hook.addUserBadge("VIP", null);

        Object badges = Hook.filter().apply("user_badges", new ArrayList<>());
        Map<?, ?> badge = (Map<?, ?>) ((List<?>) badges).get(0);
        assertEquals("VIP", badge.get("text"));
        assertEquals("primary", badge.get("color"));
    }

    @Test
    void sendNotificationUsesInjectedSink() {
        List<String> sent = new ArrayList<>();
        Hook.setNotificationSink((users, title, content) ->
                sent.add(title + "|" + content + "|" + users));

        Hook.sendNotification(List.of(1, 2), "标题", "内容");

        assertEquals(1, sent.size());
        assertTrue(sent.get(0).startsWith("标题|内容|"));
    }

    @Test
    void pushMiddlewareIsRecorded() {
        Object middleware = new Object();
        Hook.pushMiddleware(middleware);

        assertEquals(List.of(middleware), Hook.middlewares());
    }
}
