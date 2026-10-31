package com.neoskin.web.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.neoskin.event.EventRuntime;
import com.neoskin.event.Events;
import com.neoskin.service.Hook;
import com.neoskin.service.Option;

/**
 * 渲染层接入插件钩子：{@code head_links} / {@code scripts} /
 * {@code RenderingHeader} / {@code RenderingFooter}。
 */
class SharedViewDataHookTest {

    @AfterEach
    void reset() {
        Hook.reset();
        EventRuntime.set(null);
    }

    @Test
    void headIncludesPluginStylesAndHeaderContent() {
        Hook.addStyleFileToPage(List.of("/plugins/demo/style.css"), List.of("admin/*"));
        EventRuntime.dispatcher().listen(Events.RenderingHeader.class,
                event -> event.addContent("<script>plugin()</script>"));

        Option option = mockOption();
        Map<String, Object> head = SharedViewData.head(option, "zh_CN",
                "http://localhost", "/admin/plugins", false);

        assertEquals(1, ((List<?>) head.get("links")).size());
        assertEquals("<script>plugin()</script>", head.get("extra_head"));

        Map<String, Object> other = SharedViewData.head(option, "zh_CN",
                "http://localhost", "/user", false);
        assertEquals(0, ((List<?>) other.get("links")).size(), "页面不匹配时不注入");
    }

    @Test
    void footIncludesPluginScriptsAndFooterContent() throws Exception {
        Hook.addScriptFileToPage(List.of("/plugins/demo/script.js"), List.of("*"));
        EventRuntime.dispatcher().listen(Events.RenderingFooter.class,
                event -> event.addContent("<div>footer</div>"));

        Map<String, Object> foot = SharedViewData.foot(mockOption(), null, "zh_CN", "/");
        assertEquals(1, ((List<?>) foot.get("scripts")).size());
        assertEquals("<div>footer</div>", foot.get("extra_foot"));
    }

    @Test
    void noHooksMeansEmptyLists() throws Exception {
        Map<String, Object> head = SharedViewData.head(mockOption(), "zh_CN",
                "http://localhost", "/", false);
        Map<String, Object> foot = SharedViewData.foot(mockOption(), null, "zh_CN", "/");

        assertEquals(0, ((List<?>) head.get("links")).size());
        assertEquals(0, ((List<?>) foot.get("scripts")).size());
        assertNull(head.get("extra_head"));
        assertNull(foot.get("extra_foot"));
    }

    private Option mockOption() {
        Option option = mock(Option.class);
        when(option.get(anyString())).thenReturn(null);

        return option;
    }
}
