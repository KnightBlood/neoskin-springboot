package com.neoskin.web.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.neoskin.conformance.Corpus;
import com.neoskin.service.Option;

import tools.jackson.databind.JsonNode;

/**
 * {@link DetectLanguagePrefer} 的语料驱动测试。
 *
 * <p>23 条 {@code resolve.*} 是 PHP 侧真实跑中间件得到的「最终 locale + 排队 cookie」，
 * 5 条 {@code quirk.*} 把源码里几个反直觉的行为单独钉住。
 *
 * <p>这些用例里有一半看起来「像 bug」，但它们都是源站的真实输出：改掉任何一条，
 * 中文站点的语言切换、API 的语言、匿名首访的默认语言就会与 PHP 版分叉。
 */
class DetectLanguagePreferTest {

    @AfterEach
    void resetLocale() {
        Option.clearCurrentLocale();
    }

    @Test
    @DisplayName("resolve.* 语料：locale 与 cookie")
    void resolveCorpus() {
        Corpus corpus = Corpus.require("locale");
        List<JsonNode> cases = corpus.cases("resolve");
        assertEquals(23, cases.size(), "resolve.* 用例数量变了，语料库需要重新生成");

        for (JsonNode c : cases) {
            String id = Corpus.text(c, "id");
            JsonNode in = c.get("input");

            DetectLanguagePrefer.Result result = DetectLanguagePrefer.resolve(input(in));

            assertEquals(Corpus.text(c, "expected_locale"), result.locale(), id + " 的 locale");
            assertEquals(Option.currentLocale(), result.locale(), id + " 未应用到 Option");

            JsonNode expectedCookie = c.get("expected_cookie");
            if (expectedCookie == null || expectedCookie.isNull()) {
                assertNull(result.cookie(), id + " 不应写 cookie");
            } else {
                DetectLanguagePrefer.Cookie cookie = result.cookie();
                assertNotNull(cookie, id + " 应写 cookie");
                assertEquals(Corpus.text(expectedCookie, "name"), cookie.name(), id + " cookie.name");
                assertEquals(Corpus.text(expectedCookie, "value"), cookie.value(), id + " cookie.value");
                assertEquals(Corpus.integer(expectedCookie, "max_age", -1), cookie.maxAgeSeconds(),
                        id + " cookie.max_age");
                assertEquals(Corpus.text(expectedCookie, "path"), cookie.path(), id + " cookie.path");
                assertEquals(Corpus.bool(expectedCookie, "http_only", false), cookie.httpOnly(),
                        id + " cookie.http_only");
                assertEquals(Corpus.bool(expectedCookie, "secure", true), cookie.secure(), id + " cookie.secure");
                assertEquals(Corpus.text(expectedCookie, "same_site"), cookie.sameSite(), id + " cookie.same_site");
                assertEquals(Corpus.text(expectedCookie, "domain"), cookie.domain(), id + " cookie.domain");
            }
        }
    }

    @Test
    @DisplayName("quirk.* 语料：五个反直觉行为")
    void quirkCorpus() {
        Corpus corpus = Corpus.require("locale");
        List<JsonNode> cases = corpus.cases("quirk");
        assertEquals(5, cases.size(), "quirk.* 用例数量变了，语料库需要重新生成");

        for (JsonNode c : cases) {
            String id = Corpus.text(c, "id");
            String explanation = Corpus.text(c, "explanation");
            JsonNode expected = c.get("expected");

            switch (id) {
                // 源码 "$locale ?? app()->getLocale();" 缺赋值 → 走 fallback 而不是 app.locale
                case "quirk.fallback_not_app_locale" -> assertEquals(expected.asString(),
                        DetectLanguagePrefer.resolve(new DetectLanguagePrefer.Input(
                                null, null, null, List.of("web"), "zh_CN")).locale(),
                        explanation);
                // ?lang= 是空串而非 null，?? 不会回退
                case "quirk.empty_lang_is_set" -> assertEquals(expected.asString(),
                        DetectLanguagePrefer.resolve(new DetectLanguagePrefer.Input(
                                "", "es_ES", "ru", List.of("web"), "zh_CN")).locale(),
                        explanation);
                // api 路由不写 cookie
                case "quirk.api_route_skips_cookie" -> assertNull(
                        DetectLanguagePrefer.resolve(new DetectLanguagePrefer.Input(
                                "zh_TW", null, null, List.of("api"), "zh_CN")).cookie(),
                        explanation);
                // cookie 里存归一后的值，不是原始 en_US
                case "quirk.alias_still_sets_cookie" -> {
                    DetectLanguagePrefer.Result result = DetectLanguagePrefer.resolve(
                            new DetectLanguagePrefer.Input("en_US", null, null, List.of("web"), "zh_CN"));
                    assertEquals("en", result.locale(), explanation);
                    assertNotNull(result.cookie());
                    assertEquals("en", result.cookie().value(), explanation);
                }
                // zh_HANS_CN 是别名 → zh_CN
                case "quirk.zh_hans_alias" -> assertEquals(expected.asString(),
                        DetectLanguagePrefer.resolve(new DetectLanguagePrefer.Input(
                                "zh_HANS_CN", null, null, List.of("web"), "zh_CN")).locale(),
                        explanation);
                default -> throw new IllegalStateException("未处理的新 quirk 用例: " + id);
            }
        }
    }

    @Test
    @DisplayName("大小写敏感：Accept-Language 的 zh-Hans-CN ≠ 配置键 zh_HANS_CN")
    void acceptAliasIsCaseSensitive() {
        // formatLocale 产出 zh_Hans_CN，配置里的别名是 zh_HANS_CN，两者不等
        DetectLanguagePrefer.Result result = DetectLanguagePrefer.resolve(new DetectLanguagePrefer.Input(
                null, null, "zh-Hans-CN", List.of("web"), "zh_CN"));
        assertEquals("en", result.locale());
        // 带下划线的写法才会命中别名
        assertEquals("zh_CN", DetectLanguagePrefer.resolve(new DetectLanguagePrefer.Input(
                "zh_HANS_CN", null, null, List.of("web"), "zh_CN")).locale());
    }

    @Test
    @DisplayName("初始 locale 不影响兜底（缺陷的第二重证据）")
    void initialLocaleIsIrrelevant() {
        for (String initial : List.of("zh_CN", "zh_TW", "en", "ru_RU")) {
            assertEquals("en", DetectLanguagePrefer.resolve(new DetectLanguagePrefer.Input(
                    null, null, null, List.of("web"), initial)).locale(), initial);
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static DetectLanguagePrefer.Input input(JsonNode in) {
        return new DetectLanguagePrefer.Input(
                queryLang(in.get("query")),
                cookieLocale(in.get("cookies")),
                Corpus.text(in, "accept_language"),
                middleware(in.get("route_middleware")),
                Corpus.textOrEmpty(in, "initial_locale"));
    }

    /** {@code $request->input('lang')}：键不存在才是 null，显式空串要保留。 */
    private static String queryLang(JsonNode query) {
        if (query == null || !query.isObject()) {
            return null;
        }
        JsonNode lang = query.get("lang");
        return lang == null || lang.isNull() ? null : lang.asString();
    }

    /** {@code $request->cookie('locale')}：同上。 */
    private static String cookieLocale(JsonNode cookies) {
        if (cookies == null || !cookies.isObject()) {
            return null;
        }
        JsonNode locale = cookies.get("locale");
        return locale == null || locale.isNull() ? null : locale.asString();
    }

    /** 路由中间件数组；{@code null} 表示没有路由。 */
    private static List<String> middleware(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asString()));
        return out;
    }
}
