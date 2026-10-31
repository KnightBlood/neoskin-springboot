package com.neoskin.web.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;

/**
 * {@link JavaScript} 的回归：编译时机、产物内容、{@code resetTime} 强制重编译。
 *
 * <p>源 {@code generate()} 的判断是
 * {@code $sourceModified > $compiledModified || !file_exists($compiled)}，
 * 其中 {@code $sourceModified} 是「插件语言包 + 主语言包」的 mtime 最大值。
 * 本测试通过操纵 mtime 把三条分支都走一遍。</p>
 */
class JavaScriptTest {

    @TempDir
    Path dir;

    private AppPaths paths() {
        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getStorage().setRoot(dir.resolve("storage").toString());
        return new AppPaths(dir, settings);
    }

    private JavaScript javaScript(AppPaths paths) {
        return new JavaScript(paths, new Translator(new TranslationFileLoader(), "zh_CN", "en"),
                null);
    }

    /** 造一个最小 front-end.yml 作为「主语言包」。 */
    private void writeMainSource(JavaScript js, String locale, String body) throws Exception {
        Path file = js.langSource(locale);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    /** 首次调用：编译产物不存在 → 必然重编译，返回 ?t=sourceModified。 */
    @Test
    void generatesWhenCompiledMissing() throws Exception {
        AppPaths paths = paths();
        JavaScript js = javaScript(paths);
        writeMainSource(js, "zh_CN", "general:\n  confirm: 确定\n");

        String url = js.generate("zh_CN");
        Path compiled = js.compiled("zh_CN");
        assertTrue(Files.exists(compiled), "编译产物必须生成");
        assertTrue(url.startsWith("/lang/zh_CN.js?t="));
        assertTrue(Long.parseLong(url.substring(url.indexOf("?t=") + 3)) > 0);

        // 产物内容：blessing.i18n = {...}，中文不被 Unicode 转义。
        // 注意：trans('front-end') 走 classpath 上的真实语料（/lang/<locale>/front-end.yml），
        // 不是 writeMainSource 写的那个文件 —— 后者只作为 mtime 来源。
        String content = Files.readString(compiled);
        assertTrue(content.startsWith(JavaScript.ASSIGNMENT));
        assertTrue(content.contains("\"login\":\"登录\""),
                "必须包含 classpath 真实语料: " + content.substring(0, Math.min(120, content.length())));
        assertFalse(content.contains("\\u"), "不应有 Unicode 转义");
    }

    /** 源码 mtime 没变 → 复用缓存值，且不再重写文件。 */
    @Test
    void reusesWhenSourceUnchanged() throws Exception {
        AppPaths paths = paths();
        JavaScript js = javaScript(paths);
        writeMainSource(js, "zh_CN", "general:\n  confirm: 确定\n");

        String first = js.generate("zh_CN");
        Path compiled = js.compiled("zh_CN");
        FileTime afterFirst = Files.getLastModifiedTime(compiled);

        // 第二次：sourceModified == compiledModified → 走缓存分支
        String second = js.generate("zh_CN");
        assertEquals(first, second, "mtime 未变 → ?t= 必须一致");
        assertEquals(afterFirst, Files.getLastModifiedTime(compiled), "不应重写文件");
    }

    /** 源码变新 → sourceModified > compiledModified → 重编译。 */
    @Test
    void regeneratesWhenSourceNewer() throws Exception {
        AppPaths paths = paths();
        JavaScript js = javaScript(paths);
        writeMainSource(js, "zh_CN", "general:\n  confirm: 确定\n");

        String first = js.generate("zh_CN");
        long firstT = Long.parseLong(first.substring(first.indexOf("?t=") + 3));

        Path source = js.langSource("zh_CN");
        Files.writeString(source, "general:\n  confirm: 好的\n");
        Files.setLastModifiedTime(source, FileTime.fromMillis((firstT + 5) * 1000));

        Path compiled = js.compiled("zh_CN");
        FileTime beforeRegen = Files.getLastModifiedTime(compiled);
        String second = js.generate("zh_CN");
        long secondT = Long.parseLong(second.substring(second.indexOf("?t=") + 3));

        assertEquals(firstT + 5, secondT, "?t= 更新为新的 sourceModified");
        assertEquals(firstT + 5, js.cachedTime("zh_CN"), "缓存记回新的 sourceModified");
        assertTrue(Files.getLastModifiedTime(compiled).compareTo(beforeRegen) >= 0,
                "产物被重写");
    }

    /** {@code resetTime} → 缓存归零 → 下次必然重编译（即便源码没动）。 */
    @Test
    void resetTimeForcesRegeneration() throws Exception {
        AppPaths paths = paths();
        JavaScript js = javaScript(paths);
        writeMainSource(js, "zh_CN", "general:\n  confirm: 确定\n");

        String first = js.generate("zh_CN");
        long firstT = Long.parseLong(first.substring(first.indexOf("?t=") + 3));
        assertEquals(firstT, js.cachedTime("zh_CN"));

        js.resetTime("zh_CN");
        assertEquals(0L, js.cachedTime("zh_CN"));

        // 源码没动：sourceModified 仍是 firstT > 0 → 触发重编译
        String second = js.generate("zh_CN");
        assertEquals(first, second, "源码没动 → ?t= 仍是 sourceModified");
        assertEquals(firstT, js.cachedTime("zh_CN"), "重编译后缓存记回 sourceModified");
    }

    /** 插件语言包：挂在<b>插件名</b>键下，且只算存在的文件的 mtime。 */
    @Test
    void pluginTranslationsAreNamespaced() throws Exception {
        AppPaths paths = paths();
        JavaScript js = javaScript(paths);
        writeMainSource(js, "zh_CN", "general:\n  confirm: 确定\n");

        Path pluginLang = dir.resolve("myplugin").resolve("lang").resolve("zh_CN")
                .resolve("front-end.yml");
        Files.createDirectories(pluginLang.getParent());
        Files.writeString(pluginLang, "hello: 你好\n");

        JavaScript withPlugin = new JavaScript(paths,
                new Translator(new TranslationFileLoader(), "zh_CN", "en"),
                locale -> List.of(new JavaScript.PluginLang(
                        "myplugin", "myplugin", pluginLang.toString())));

        String url = withPlugin.generate("zh_CN");
        assertTrue(url.startsWith("/lang/zh_CN.js?t="));

        String content = Files.readString(withPlugin.compiled("zh_CN"));
        // 主语言包在顶层
        assertTrue(content.contains("\"auth\""), "主语言包在顶层: " + content.substring(0, 80));
        // 插件整体挂在插件名下。此处插件命名空间尚未注册进 loader
        // （loader 目前只读 classpath，插件的 lang 在磁盘上 —— 见 PORT-NOTES），
        // 于是 trans() 回退为 key 本身，与 PHP 的未命中行为一致。
        assertTrue(content.contains("\"myplugin\"") && content.contains("myplugin::front-end"),
                "未注册的插件命名空间 → 值为 key 本身（与 PHP 一致）: "
                        + content.substring(Math.max(0, content.length() - 80)));
    }

    /** 不存在的插件路径被过滤（不参与 mtime，也不产出键）。 */
    @Test
    void missingPluginSourceIsIgnored() throws Exception {
        AppPaths paths = paths();
        JavaScript js = javaScript(paths);
        writeMainSource(js, "zh_CN", "general:\n  confirm: 确定\n");

        JavaScript withGhost = new JavaScript(paths,
                new Translator(new TranslationFileLoader(), "zh_CN", "en"),
                locale -> List.of(new JavaScript.PluginLang(
                        "ghost", "ghost", dir.resolve("nope").resolve("front-end.yml").toString())));

        withGhost.generate("zh_CN");
        String content = Files.readString(withGhost.compiled("zh_CN"));
        assertFalse(content.contains("ghost"), "不存在的插件不应产出键: " + content);
    }

    /** 多 locale 各自独立缓存。 */
    @Test
    void perLocaleCacheIsIndependent() throws Exception {
        AppPaths paths = paths();
        JavaScript js = javaScript(paths);
        writeMainSource(js, "zh_CN", "general:\n  confirm: 确定\n");
        writeMainSource(js, "en", "general:\n  confirm: Confirm\n");

        js.generate("zh_CN");
        js.generate("en");
        js.resetTime("en");

        assertEquals(0L, js.cachedTime("en"), "en 被 reset");
        assertTrue(js.cachedTime("zh_CN") > 0, "zh_CN 不受影响");
        // en 的编译产物（classpath 真实语料）
        assertTrue(Files.exists(js.compiled("en")));
        assertTrue(Files.readString(js.compiled("en")).contains("Log In"));
    }

    @Test
    void mainSourceAndCompiledPaths() {
        AppPaths paths = paths();
        JavaScript js = javaScript(paths);
        // resource_path("lang/<locale>/front-end.yml") / public_path("lang/<locale>.js")
        assertEquals(dir.resolve("resources").resolve("lang").resolve("zh_CN")
                .resolve("front-end.yml"), js.langSource("zh_CN"));
        assertEquals(dir.resolve("public").resolve("lang").resolve("zh_CN.js"),
                js.compiled("zh_CN"));
        // 未生成过 → 缓存值为 0（源的 cache->get(..., 0) 默认）
        assertEquals(0L, js.cachedTime("zh_CN"));
    }
}
