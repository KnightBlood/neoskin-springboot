package com.neoskin.web.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.neoskin.conformance.Corpus;

import tools.jackson.databind.JsonNode;

/**
 * {@link Locales} 的语料驱动测试。
 *
 * <p>期望值来自 {@code conformance/data/locale.json} 的 12 条 {@code lookup.*}
 * 与 4 条 {@code const.*} 用例，都是 PHP 侧真实跑
 * {@code Arr::get(config('locales'), $key)} / {@code Arr::has()} 的结果。
 *
 * <p>三条最容易写错的：
 *
 * <ul>
 *   <li>{@code lookup.null}：{@code key = null} 时 {@code Arr::get} 返回<b>整张表</b>
 *       （不是 null、也不是默认值）；</li>
 *   <li>{@code lookup.0}：键 {@code 0}——PHP 数组里没有整数键 0，所以两者都是 false/null，
 *       不能因为「8 个键里第一个是 zh_CN」就误判成命中；</li>
 *   <li>{@code lookup.zh-TW}：连字符写法不是配置里的键（配置用下划线），必须返回 false。</li>
 * </ul>
 */
class LocalesTest {

    /** 源文件里的键顺序，必须逐字保持。 */
    private static final List<String> KEY_ORDER = List.of(
            "zh_CN", "zh_HANS_CN", "zh_TW", "en", "en_US", "es_ES", "ru", "ru_RU");

    @Test
    @DisplayName("lookup.* 语料：has / get")
    void lookupCorpus() {
        Corpus corpus = Corpus.require("locale");
        List<JsonNode> cases = corpus.cases("lookup");
        assertEquals(12, cases.size(), "lookup.* 用例数量变了，语料库需要重新生成");

        for (JsonNode c : cases) {
            String id = Corpus.text(c, "id");
            JsonNode keyNode = c.get("key");
            Object key = keyNode == null || keyNode.isNull() ? null
                    : keyNode.isString() ? keyNode.asString() : Integer.valueOf(keyNode.asInt());

            assertEquals(Corpus.bool(c, "expected_has", false), Locales.has(key), id + " 的 has");

            JsonNode expected = c.get("expected_get");
            Object actual = Locales.get(key);
            if (expected == null || expected.isNull()) {
                assertNull(actual, id + " 的 get 应为 null");
            } else {
                assertEquals(plain(expected), plain(actual), id + " 的 get");
            }
        }
    }

    @Test
    @DisplayName("lookup.null 返回的就是整张表本身")
    void nullKeyReturnsWholeTable() {
        assertSame(Locales.table(), Locales.get(null));
        assertEquals(KEY_ORDER, new ArrayList<>(Locales.table().keySet()));
    }

    @Test
    @DisplayName("const.* 语料：三个常量")
    void constants() {
        Corpus corpus = Corpus.require("locale");
        for (JsonNode c : corpus.cases("const")) {
            String id = Corpus.text(c, "id");
            JsonNode expected = c.get("expected");
            switch (id) {
                case "const.app_locale" -> assertEquals(expected.asString(), Locales.APP_LOCALE, id);
                case "const.fallback_locale" -> assertEquals(expected.asString(), Locales.FALLBACK_LOCALE, id);
                default -> {
                    // cookie 名与寿命由 DetectLanguagePrefer 负责，这里不断言
                }
            }
        }
    }

    @Test
    @DisplayName("别名的三点归一")
    void aliases() {
        assertEquals("zh_CN", Locales.resolve("zh_HANS_CN"));
        assertEquals("en", Locales.resolve("en_US"));
        assertEquals("ru_RU", Locales.resolve("ru"));
        // 本身不是别名 → 原样返回
        assertEquals("zh_CN", Locales.resolve("zh_CN"));
        assertEquals("zh_TW", Locales.resolve("zh_TW"));
        assertEquals("es_ES", Locales.resolve("es_ES"));
        // 未命中 → 原样返回（fallback 在中间件里做，不在这里）
        assertEquals("fr_FR", Locales.resolve("fr_FR"));
        assertEquals("", Locales.resolve(""));
        assertNull(Locales.resolve(null));
    }

    @Test
    @DisplayName("available() 过滤掉 3 个别名，只剩 5 个真实语言")
    void availableLanguages() {
        assertEquals(List.of("zh_CN", "zh_TW", "en", "es_ES", "ru_RU"), Locales.ids());
        assertEquals(5, Locales.available().size());
        assertEquals("中文 (简体)", Locales.displayName("zh_CN"));
        assertEquals("Русский язык", Locales.displayName("ru_RU"));
        assertEquals("es", Locales.shortName("es_ES"), "es_ES 的 short_name 与键不同，是全表唯一一个");
        // 别名没有 name / short_name
        assertNull(Locales.displayName("en_US"));
        assertNull(Locales.shortName("en_US"));
        assertNull(Locales.displayName("fr_FR"));
    }

    @Test
    @DisplayName("toPhpArray() 与 table() 内容一致且保持顺序")
    void toPhpArrayMirrorsTable() {
        PhpArrayCheck.assertSameStructure(Locales.table(), Locales.toPhpArray());
        assertTrue(Locales.toPhpArray() instanceof com.neoskin.support.PhpJson.Array);
    }

    // ------------------------------------------------------------------ 辅助

    /** 把 JSON 节点转成与 PHP 数组等价的嵌套 {@link LinkedHashMap}。 */
    static Object plain(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.properties().forEach(e -> map.put(e.getKey(), plain(e.getValue())));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(child -> list.add(plain(child)));
            return list;
        }
        if (node.isString()) {
            return node.asString();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        return node.asDouble();
    }

    /** 把实现返回的 PHP 数组递归转成 {@link String} 键的 Map，便于与语料比对。 */
    @SuppressWarnings("unchecked")
    static Object plain(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), plain(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(plain(v)));
            return out;
        }
        return value;
    }

    /** 结构比对（顺带检查顺序，因为两者都是 LinkedHashMap）。 */
    static final class PhpArrayCheck {
        private PhpArrayCheck() {
        }

        static void assertSameStructure(Map<String, Map<String, String>> expected, Map<?, ?> actual) {
            assertEquals(new ArrayList<>(expected.keySet()),
                    actual.keySet().stream().map(String::valueOf).toList());
            for (Map.Entry<String, Map<String, String>> e : expected.entrySet()) {
                assertEquals(e.getValue(), actual.get(e.getKey()), e.getKey() + " 的内容");
            }
        }
    }
}
