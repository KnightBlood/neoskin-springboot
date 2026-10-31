package com.neoskin.web.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.neoskin.conformance.Corpus;
import com.neoskin.web.PhpUrlGenerator;

import tools.jackson.databind.JsonNode;

/**
 * {@link LanguagesMenu} 的语料驱动测试。
 *
 * <p>期望值来自 {@code conformance/data/locale.json} 的 4 条 {@code menu.*} 用例，
 * 是 PHP 侧真实跑 {@code LanguagesMenuComposer::compose()} 的结果。
 *
 * <p>最关键的一条是 {@code menu.root}：根路径的 URL 是
 * {@code http://localhost?lang=zh_CN}，<b>没有</b> {@code /}。
 * 原因是 {@code UrlGenerator::format()} 最后一步是
 * {@code trim($root.$path, '/')}（两侧都 trim），
 * 而 {@code request->path()} 对根路径返回 {@code "/"}，拼出来的
 * {@code http://localhost/} 就被收掉了。改造这条时任何「补一个斜杠」的冲动都会
 * 让菜单链接与源站不一致。
 */
class LanguagesMenuTest {

    @Test
    @DisplayName("menu.* 语料：current 与 5 条语言链接")
    void menuCorpus() {
        Corpus corpus = Corpus.require("locale");
        List<JsonNode> cases = corpus.cases("menu");
        assertEquals(4, cases.size(), "menu.* 用例数量变了，语料库需要重新生成");

        for (JsonNode c : cases) {
            String id = Corpus.text(c, "id");
            String uri = Corpus.text(c, "uri");

            LanguagesMenu.Result result = LanguagesMenu.compose(
                    requestPath(uri),
                    query(c.get("query")),
                    // 语料里的 current 都是 English，说明 app()->getLocale() 是 en
                    "en",
                    PhpUrlGenerator.forHost("localhost"));

            JsonNode expected = c.get("expected");
            assertEquals(Corpus.text(expected, "current"), result.current(), id + " 的 current");

            Map<String, Object> actual = new LinkedHashMap<>();
            result.langs().forEach((key, value) -> actual.put(key, new LinkedHashMap<>(value)));
            assertEquals(LocalesTest.plain(expected.get("langs")), actual, id + " 的 langs");
        }
    }

    @Test
    @DisplayName("根路径不带斜杠（trim 两侧的结果）")
    void rootHasNoTrailingSlash() {
        LanguagesMenu.Result result = LanguagesMenu.compose(
                "/", Map.of(), "en", PhpUrlGenerator.forHost("localhost"));
        assertEquals("http://localhost?lang=zh_CN", result.langs().get("zh_CN").get("url"));
    }

    @Test
    @DisplayName("中文站点下 current 是中文名")
    void currentFollowsAppLocale() {
        assertEquals("中文 (简体)", LanguagesMenu.compose("/", Map.of(), "zh_CN",
                PhpUrlGenerator.forHost("localhost")).current());
        assertEquals("Русский язык", LanguagesMenu.compose("/", Map.of(), "ru_RU",
                PhpUrlGenerator.forHost("localhost")).current());
    }

    @Test
    @DisplayName("5 项且顺序与 config/locales.php 一致")
    void orderMatchesSourceFile() {
        List<String> keys = new ArrayList<>(LanguagesMenu.compose("/", Map.of(), "en",
                PhpUrlGenerator.forHost("localhost")).langs().keySet());
        assertEquals(List.of("zh_CN", "zh_TW", "en", "es_ES", "ru_RU"), keys);
    }

    // ------------------------------------------------------------------ 辅助

    /** {@code Request::path()}：根路径是 {@code "/"}，其余不带前导斜杠。 */
    static String requestPath(String uri) {
        String trimmed = uri == null ? "" : uri.replaceAll("^/+", "").replaceAll("/+$", "");
        return trimmed.isEmpty() ? "/" : trimmed;
    }

    /** 语料里的 {@code query} 有时是 {@code []}（空数组），有时是对象。 */
    static Map<String, String> query(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        node.properties().forEach(e -> out.put(e.getKey(), e.getValue().asString()));
        return out;
    }
}
