package com.neoskin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neoskin.support.PhpJson;

/**
 * {@link CommunicationRenderer} 与 PHP league/commonmark 2.10.4 的逐字比对。
 *
 * <p>期望值全部来自 PHP 真实输出（{@code CommunicationRenderer} 探针 +
 * league/commonmark），见 {@link RendererTruth}。这批断言是「内容渲染
 * 不能随机变脸」的护栏 —— 它们失败的唯一可能是 Java 侧实现分叉了。</p>
 */
class CommunicationRendererTest {

    private final CommunicationRenderer renderer = new CommunicationRenderer();

    // ---------------------------------------------------------- markdown

    @Test
    void heading() {
        assertEquals(RendererTruth.EXPECTED_HEADING,
                renderer.render(Map.of("editor", "source",
                        "source", "## 标题\n正文 **粗体** *斜体*。\n\n- 列表一\n- 列表二\n"),
                        "pfx"));
    }

    @Test
    void link() {
        assertEquals(RendererTruth.EXPECTED_LINK, renderer.render(Map.of("editor", "source",
                "source", "[E negli angluars](https://e.example.com/a?b=1&c=2)"
                        + " 及 [bad](javascript:alert(1)) 与 [data](data:text/html,a)"
                        + " 端 [file](file://c/x)。"), "pfx"));
    }

    @Test
    void autolink() {
        assertEquals(RendererTruth.EXPECTED_AUTOLINK, renderer.render(Map.of("editor", "source",
                "source", "访问 https://a.example.com 或测试邮箱 a@b.com。"), "pfx"));
    }

    @Test
    void table() {
        assertEquals(RendererTruth.EXPECTED_TABLE, renderer.render(Map.of("editor", "source",
                "source", "| A | B |\n| --- | --- |\n| 1 | 2 |\n"), "pfx"));
    }

    @Test
    void strikethrough() {
        assertEquals(RendererTruth.EXPECTED_STRIKETHROUGH, renderer.render(Map.of("editor",
                "source", "source", "~~没了~~ 与 `code` 及``多行``。"), "pfx"));
    }

    @Test
    void tasklist() {
        assertEquals(RendererTruth.EXPECTED_TASKLIST, renderer.render(Map.of("editor", "source",
                "source", "- [ ] 待办\n- [x] 完成\n"), "pfx"));
    }

    @Test
    void rawhtml() {
        assertEquals(RendererTruth.EXPECTED_RAWHTML, renderer.render(Map.of("editor", "source",
                "source", "<script>alert(1)</script>\n\n"
                        + "<script type=\"text/javascript\">var a=1;</script>\n\n"
                        + "<div>ok</div>\n<b>bold</b>\n<TEXTAREA>x</TEXTAREA>"), "pfx"));
    }

    @Test
    void blockquote() {
        assertEquals(RendererTruth.EXPECTED_BLOCKQUOTE, renderer.render(Map.of("editor",
                "source", "source", "> 引\n\n```\ncode\n```\n\n---\n"), "pfx"));
    }

    @Test
    void image() {
        assertEquals(RendererTruth.EXPECTED_IMAGE, renderer.render(Map.of("editor", "source",
                "source", "![图](https://img.example.com/a.png)"
                        + " ![data图](data:image/png;base64,AA)"), "pfx"));
    }

    // ---------------------------------------------------------- blocks

    @Test
    void blocks() {
        Map<String, Object> alert0 = Map.of("type", "alert", "content", "提示内容",
                "tone", "danger");
        Map<String, Object> collapse = Map.of("type", "collapse", "title", "折叠",
                "content", "折叠内容", "tone", "warning", "expanded", true);
        Map<String, Object> divider = Map.of("type", "divider");
        Map<String, Object> alert1 = Map.of("type", "alert", "content", "无tone");

        Map<String, Object> data = Map.of(
                "editor", "blocks",
                "blocks", List.of(
                        Map.of("type", "text", "content", "纯 **文本**\n第二行"),
                        Map.of("type", "heading", "title", "含&与\"引号\"与<'撇>"),
                        alert0, collapse, divider, alert1));

        assertEquals(RendererTruth.EXPECTED_BLOCKS, renderer.render(data, "pfx"));
    }

    // ---------------------------------------------------------- importBlocks

    @Test
    void importSample0() {
        assertEquals(decode(RendererTruth.IMPORT_0), renderer.importBlocks(
                "<h2>标题</h2><p>段落 <b>粗</b></p>"
                        + "<div class='alert alert-danger t'>红色提示</div>"));
    }

    @Test
    void importSample1() {
        assertEquals(decode(RendererTruth.IMPORT_1), renderer.importBlocks(
                "<div class='card card-outline card-primary'>"
                        + "<div class='card-header'>折叠标题</div>"
                        + "<div class='card-body collapse show'><p>内文</p></div></div>"));
    }

    /**
     * PHP {@code json_decode(..., true)} 的返回值（{@link PhpJson.Array}，键是
     * {@code Long 0..n-1}）与 {@code importBlocks} 的 {@code List}（内部的
     * {@code LinkedHashMap}）结构型不同，先归一化再比。
     */
    private static Object decode(String json) {
        return normalize(PhpJson.decode(json.replace("\\/", "/")));
    }

    /** {@code Array} → list 形态转 {@code List}、对象形态转 {@code LinkedHashMap}（递归）。 */
    private static Object normalize(Object value) {
        if (value instanceof PhpJson.Array array) {
            if (PhpJson.isList(array)) {
                List<Object> list = new ArrayList<>();
                for (Map.Entry<Object, Object> entry : array.entrySet()) {
                    list.add(normalize(entry.getValue()));
                }
                return list;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            array.forEach((key, item) -> map.put(String.valueOf(key), normalize(item)));
            return map;
        }
        return value;
    }

    // ---------------------------------------------------------- 空输入

    @Test
    void emptySourceYieldsEmptyString() {
        assertEquals("", renderer.render(Map.of("editor", "source", "source", ""), "pfx"));
    }
}
