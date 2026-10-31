package com.neoskin.web.pagination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@code LengthAwarePaginator.links()} 的 Laravel 形态与 {@code UrlWindow} 滑窗。
 */
class LengthAwarePaginatorTest {

    @Test
    void smallRangeListsEveryPage() {
        LengthAwarePaginator<String> page = LengthAwarePaginator.of(
                List.of("a"), 20, 10, 1, Map.of(), "http://localhost/skinlib/list");

        List<Map<String, Object>> links = page.links();

        // previous + 2 页码 + next
        assertEquals(4, links.size());
        assertEquals("&laquo; Previous", links.get(0).get("label"));
        assertNull(links.get(0).get("url"));
        assertEquals("http://localhost/skinlib/list?page=1", links.get(1).get("url"));
        assertEquals(true, links.get(1).get("active"));
        assertEquals("http://localhost/skinlib/list?page=2", links.get(2).get("url"));
        assertEquals("Next &raquo;", links.get(3).get("label"));
    }

    @Test
    void largeRangeUsesSliderAndEllipsis() {
        // lastPage = 50, onEachSide = 3 → 窗口 + 省略号
        LengthAwarePaginator<String> page = LengthAwarePaginator.of(
                List.of("a"), 500, 10, 25, Map.of(), "http://localhost/list");

        List<Map<String, Object>> links = page.links();
        List<String> labels = links.stream()
                .map(link -> String.valueOf(link.get("label"))).toList();

        assertTrue(labels.contains("..."), "中间应有省略号：" + labels);
        assertTrue(labels.contains("25"));
        assertEquals("http://localhost/list?page=25",
                links.get(labels.indexOf("25")).get("url"));
        assertTrue(labels.contains("1") && labels.contains("50"), "窗口应含首尾页：" + labels);
    }

    @Test
    void firstAndLastPagesHaveNoPrevNextUrl() {
        LengthAwarePaginator<String> first = LengthAwarePaginator.of(
                List.of("a"), 100, 10, 1, Map.of(), "/list");
        LengthAwarePaginator<String> last = LengthAwarePaginator.of(
                List.of("a"), 100, 10, 10, Map.of(), "/list");

        assertNull(first.links().get(0).get("url"));
        assertNull(last.links().get(last.links().size() - 1).get("url"));
        assertFalse((Boolean) last.links().get(0).get("active"));
    }
}
