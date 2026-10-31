package com.neoskin.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link EventDispatcher}：类事件、字符串事件、顺序、隔离。
 */
class EventDispatcherTest {

    private final EventDispatcher dispatcher = new EventDispatcher();

    @AfterEach
    void resetRuntime() {
        EventRuntime.set(null);
    }

    @Test
    void classEventReachesListenersInOrder() {
        List<String> order = new ArrayList<>();
        dispatcher.listen(Events.UserLoggedIn.class, event -> order.add("first:" + event.user()));
        dispatcher.listen(Events.UserLoggedIn.class, event -> order.add("second"));

        Events.UserLoggedIn event = new Events.UserLoggedIn(user());
        assertTrue(dispatcher.dispatch(event) == event, "dispatch 返回原事件");
        assertEquals(List.of("first:" + user(), "second"), order);
    }

    @Test
    void stringEventPassesPayload() {
        List<Object[]> seen = new ArrayList<>();
        dispatcher.listen("closet.removed", seen::add);

        Object texture = "texture";
        dispatcher.dispatch("closet.removed", "ds", texture, 7);

        assertEquals(1, seen.size());
        assertEquals(7, seen.get(0)[2]);
    }

    @Test
    void listenerCanMutateEventCollections() {
        dispatcher.listen(Events.RenderingHeader.class,
                event -> event.addContent("<script></script>"));

        Events.RenderingHeader event = new Events.RenderingHeader(new ArrayList<>());
        dispatcher.dispatch(event);

        assertEquals(List.of("<script></script>"), event.contents());
    }

    @Test
    void countsAndClearsListeners() {
        dispatcher.listen("x", payload -> { });
        dispatcher.listen(Events.UserRegistered.class, event -> { });

        assertEquals(1, dispatcher.count("x"));
        assertEquals(1, dispatcher.count(Events.UserRegistered.class));

        dispatcher.clear();
        assertEquals(0, dispatcher.count("x"));
        assertEquals(0, dispatcher.count(Events.UserRegistered.class));
    }

    @Test
    void runtimeDefaultsIncludeClosetListener() {
        assertEquals(1, EventRuntime.dispatcher().count("closet.removed"));
        assertEquals(0, EventRuntime.dispatcher().count(Events.UserRegistered.class));
    }

    private static com.neoskin.db.UserRepository.UserRow user() {
        return new com.neoskin.db.UserRepository.UserRow(1, "a@b.c", "nick", "x", 0, 0, 0,
                false, false, "");
    }

    @Test
    void hookStyleMenuInsertionUsesEvent() {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, List<Map<String, Object>>> menu = new java.util.LinkedHashMap<>();
        menu.put("user", items);

        dispatcher.listen(Events.ConfigureUserMenu.class,
                event -> event.menu().put("user", items));
        dispatcher.dispatch(new Events.ConfigureUserMenu(menu));

        assertEquals(items, menu.get("user"));
    }
}
