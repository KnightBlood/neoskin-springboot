package com.neoskin.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.neoskin.conformance.Corpus;

import tools.jackson.databind.JsonNode;

/**
 * {@link AcceptLanguage} 的语料驱动测试。
 *
 * <p>期望值来自 {@code conformance/data/locale.json} 的 18 条 {@code accept.*} 用例，
 * 是 PHP 侧真实跑 {@code Symfony\Request::getLanguages()/getPreferredLanguage()} 的结果。
 *
 * <p>{@code accept.absent} 的 {@code header} 是 {@code null}，但期望值是
 * {@code [en_US, en]}——那是 {@code Request::create()} 自动注入默认头
 * {@code en-us,en;q=0.5} 造成的，所以这条用例喂给实现的是
 * {@link AcceptLanguage#DEFAULT_ACCEPT_LANGUAGE}。
 */
class AcceptLanguageTest {

    private static List<String> languages(JsonNode c) {
        String header = Corpus.text(c, "header");
        return AcceptLanguage.getLanguages(header != null ? header : AcceptLanguage.DEFAULT_ACCEPT_LANGUAGE);
    }

    private static String preferred(JsonNode c) {
        String header = Corpus.text(c, "header");
        return AcceptLanguage.getPreferredLanguage(
                header != null ? header : AcceptLanguage.DEFAULT_ACCEPT_LANGUAGE);
    }

    @Test
    @DisplayName("accept.* 语料：getLanguages / getPreferredLanguage")
    void acceptCorpus() {
        Corpus corpus = Corpus.require("locale");
        List<JsonNode> cases = corpus.cases("accept");
        assertEquals(18, cases.size(), "accept.* 用例数量变了，语料库需要重新生成");

        for (JsonNode c : cases) {
            String id = Corpus.text(c, "id");
            assertEquals(Corpus.texts(c, "expected_languages"), languages(c), id + " 的语言列表");
            assertEquals(Corpus.text(c, "expected_preferred"), preferred(c), id + " 的首选语言");
        }
    }

    @Test
    @DisplayName("真实的空头 → 空列表（区别于 oracle 的默认头）")
    void reallyEmptyHeader() {
        assertEquals(List.of(), AcceptLanguage.getLanguages(""));
        assertNull(AcceptLanguage.getPreferredLanguage(""));
        assertEquals(List.of(), AcceptLanguage.getLanguages(null));
        assertNull(AcceptLanguage.getPreferredLanguage(null));
    }

    @Test
    @DisplayName("formatLocale 的六个边界")
    void formatLocaleBoundaries() {
        assertEquals("zh_CN", AcceptLanguage.formatLocale("zh-CN"));
        assertEquals("zh_Hans_CN", AcceptLanguage.formatLocale("zh-Hans-CN"));
        assertEquals("zh", AcceptLanguage.formatLocale("zh-CN-"));
        assertEquals("cherokee", AcceptLanguage.formatLocale("i-cherokee"));
        assertEquals("en_GB", AcceptLanguage.formatLocale("en-GB-oed"));
        assertEquals("a", AcceptLanguage.formatLocale("a"));
        assertEquals("", AcceptLanguage.formatLocale(""));
        assertEquals("", AcceptLanguage.formatLocale(null));
        assertEquals("*", AcceptLanguage.formatLocale("*"));
        assertEquals("zh_Hant_TW", AcceptLanguage.formatLocale("zh-Hant-TW"));
    }

    @Test
    @DisplayName("getPreferredLanguage(locales) 的组合展开")
    void preferredWithCandidates() {
        assertEquals("zh_CN", AcceptLanguage.getPreferredLanguage("zh-Hans-CN", List.of("zh_CN", "en")));
        assertEquals("fr_FR", AcceptLanguage.getPreferredLanguage("fr-Latn-FR", List.of("fr_FR")));
        // 没有任何候选命中 → 返回第一个候选（PHP 的 $locales[0]）
        assertEquals("zh_CN", AcceptLanguage.getPreferredLanguage("ja-JP", List.of("zh_CN", "en")));
        // 头为空 → 也返回第一个候选
        assertEquals("en", AcceptLanguage.getPreferredLanguage("", List.of("en", "zh_CN")));
    }
}
