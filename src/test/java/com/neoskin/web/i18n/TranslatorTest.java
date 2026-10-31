package com.neoskin.web.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.neoskin.support.SymfonyYaml;

/**
 * {@link Translator} 的回归测试：
 *
 * <ol>
 *   <li><b>语料对账</b>：143 个 yml 文件 / 10744 个叶子 / 24 个非字符串（全是
 *       12 个 locale 的 {@code general.yml} 里 {@code yes}/{@code no} 的 boolean），
 *       基线来自 PHP 8.5 探针 {@code probe_yaml.php}；</li>
 *   <li><b>查找行为</b>：组查找、fallback 去重、缺键回 key、占位符替换。</li>
 * </ol>
 */
class TranslatorTest {

    @BeforeAll
    static void checkLangResourceOnClasspath() {
        // mvn 会把 src/main/resources 变成 classpath；这里防止跑错构建环境
        assertTrue(SymfonyYaml.class.getResource("/lang/zh_CN/auth.yml") != null,
                "classpath 上必须有 /lang/zh_CN/auth.yml");
    }

    // ------------------------------------------------------------------ 语料对账

    /**
     * 全量重放：对每个 yml 文件做（Symfony 语义的）解析，逐叶子归类，
     * 对齐探针输出（files=143 leaves=10744 nonString=24）。
     */
    @Test
    void corpusParityProbeYaml() throws Exception {
        int files = 0;
        int leaves = 0;
        int nonString = 0;
        for (String locale : localeDirs()) {
            for (Path yml : listYml(locale)) {
                Map<String, Object> doc = SymfonyYaml.parseResource(
                        "/lang/" + locale + "/" + yml.getFileName().toString());
                if (doc == null) {
                    continue;
                }
                files++;
                leaves += countLeaves(doc);
                nonString += countNonString(doc);
            }
        }
        assertEquals(143, files);
        assertEquals(10744, leaves);
        assertEquals(24, nonString);
    }

    // ------------------------------------------------------------------ 查找

    private Translator newTranslator(String locale) {
        return new Translator(new TranslationFileLoader(), locale, "en");
    }

    /** zh_CN 组查找。 */
    @Test
    void groupLookup() {
        Translator t = newTranslator("zh_CN");
        assertEquals("你已被本站封禁，详情请联系站点管理员", t.get("auth.check.banned", Map.of(), null));
        // 该组的其它键
        assertEquals("未授权的访问，请先登录", t.get("auth.check.anonymous", Map.of(), null));
    }

    /** fallback 去重：显式 locale 命中时不会去 en；不传 locale 用 this.locale。 */
    @Test
    void fallbackToEn() {
        Translator t = newTranslator("zh_CN");
        // en 组存在 general.login → 取 "Log In"（不是 zh_CN 的 "登录"）
        assertEquals("Log In", t.get("general.login", Map.of(), "en"));
        // 不传 locale → zh_CN → "登录"
        assertEquals("登录", t.get("general.login", Map.of(), null));
    }

    /**
     * fallback：找一个「该 locale 组文件里没有、en 有的键」→ 回退 en。
     * 实测基线：{@code en/skinlib.yml} 有 22 个叶子、de_DE 是 21 ——
     * 差的那个键就是 {@code upload.too-wide}。{@code general.yml} 等组每个
     * locale 键位一致，不触发回退。
     */
    @Test
    void fallbackWhenLocaleMissing() {
        Translator t = newTranslator("de_DE");
        // de_DE/skinlib.yml 没有 upload.too-wide → 回退 en 的行
        assertEquals("The texture is too wide (:widthpx). Maximum width allowed is :maxWidthpx",
                t.get("skinlib.upload.too-wide", Map.of(), null));
        // 占位符叠加回退行
        assertEquals("The texture is too wide (2048px). Maximum width allowed is :maxWidthpx",
                t.get("skinlib.upload.too-wide", Map.of("width", "2048"), null));
        // locale 自身有键时不回退
        assertEquals("Benutzer nicht gefunden.", t.get("general.unexistent-user", Map.of(), null));
    }

    /** 缺键 → 回原 key。 */
    @Test
    void missingKeyReturnsKey() {
        Translator t = newTranslator("zh_CN");
        assertEquals("auth.not.a.real.key", t.get("auth.not.a.real.key", Map.of(), null));
        // has 也一样
        assertFalse(t.has("auth.not.a.real.key", null, true));
        assertTrue(t.has("auth.check.banned", null, true));
    }

    /**
     * 占位符替换：{:name}、{:Name}、{:NAME} 三种形态（源 makeReplacements 的
     * 三列表）。用 zh_CN auth.register.message（"欢迎使用 :sitename！"）实测。
     */
    @Test
    void makeReplacements() {
        Translator t = newTranslator("zh_CN");
        assertEquals("欢迎使用 NeoSkin！",
                t.get("auth.register.message", Map.of("sitename", "NeoSkin"), null));
        // 三种大小写形态（静态方法）：value='x' → :x→'x'、:X→ucfirst('x')='X'、:XX 不在替换表
        assertEquals("a x b X c XX d",
                Translator.makeReplacements("a :x b :X c :XX d", Map.of("x", "x")));
    }

    // ================================================================= 内部工具

    /** 12 个 locale（不含 overrides/）。 */
    private static final java.util.Set<String> LANG_STATICS = java.util.Set.of(
            "de_DE", "el_GR", "en", "es_ES", "fr_FR", "it_IT", "ja_JP", "ko_KR", "nl_NL",
            "pt_PT", "ru_RU", "zh_CN", "zh_TW");

    private static List<String> localeDirs() {
        return new ArrayList<>(LANG_STATICS);
    }

    private static List<Path> listYml(String locale) throws IOException {
        try (Stream<Path> stream = Files.list(
                Paths.get("src/main/resources/lang").resolve(locale))) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * PHP 探针的 walk 是 {@code is_array($x) → 递归}：PHP 数组不区分 map / list，
     * 所以 YAML 序列的<b>每个元素</b>都算一个叶子（如 {@code report.status.0/.1/.2}）。
     * 这个计数器必须同样递归 List，否则对账差 26（13 个 locale × front-end 的
     * 3 元素列表 3-1=2）。
     */
    private static int countLeaves(Object value) {
        if (value instanceof Map<?, ?> map) {
            int out = 0;
            for (Object v : map.values()) {
                out += countLeaves(v);
            }
            return out;
        }
        if (value instanceof List<?> list) {
            int out = 0;
            for (Object v : list) {
                out += countLeaves(v);
            }
            return out;
        }
        return 1;
    }

    private static int countNonString(Object value) {
        if (value instanceof Map<?, ?> map) {
            int out = 0;
            for (Object v : map.values()) {
                out += countNonString(v);
            }
            return out;
        }
        if (value instanceof List<?> list) {
            int out = 0;
            for (Object v : list) {
                out += countNonString(v);
            }
            return out;
        }
        return value instanceof String ? 0 : 1;
    }
}
