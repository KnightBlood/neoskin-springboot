package com.neoskin.web.i18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.neoskin.config.AppPaths;
import com.neoskin.support.PhpJson;

/**
 * {@code App\Services\Translations\JavaScript} 的移植：把前端语言包编译成
 * {@code public/lang/<locale>.js}，供页面 {@code <script src>} 直接引用。
 *
 * <p>源 {@code generate($locale)} 的算法（逐条对齐）：</p>
 * <ol>
 *   <li>取所有<b>已启用插件</b>的 {@code <plugin>/lang/<locale>/front-end.yml}，
 *       过滤出真实存在的文件；</li>
 *   <li>再 {@code push(resource_path("lang/<locale>/front-end.yml"))} —— 主语言包
 *       排在最后；</li>
 *   <li>{@code sourceModified = max(lastModified(...))}（源文件的最新 mtime，
 *       PHP 的 {@code max()} 对空集合返回 0 也要兼容）；</li>
 *   <li>{@code compiled = public_path("lang/<locale>.js")}；
 *       {@code compiledModified = (int) cache->get("front-end-trans-{$locale}", 0)}；</li>
 *   <li>若 {@code sourceModified > compiledModified} 或编译产物不存在 →
 *       重新生成：{@code trans('front-end')} 得到整表，再逐个插件挂
 *       {@code $translations[$plugin->name] = trans($ns.'::front-end')}；
 *       内容 = {@code 'blessing.i18n = ' . json_encode($translations, JSON_UNESCAPED_UNICODE)}；
 *       写入文件、cache 记 {@code sourceModified}；返回
 *       {@code asset("lang/<locale>.js?t={$sourceModified}")}；</li>
 *   <li>否则直接返回 {@code asset("lang/<locale>.js?t={$compiledModified}")}。</li>
 * </ol>
 *
 * <p>{@code resetTime($locale)}：把缓存值写回 0 —— 下一次 {@code generate}
 * 必然 {@code sourceModified > 0}，从而强制重编译。admin 后台改了
 * {@code front-end} 组的翻译行之后会调它。</p>
 *
 * <h2>与源的两个实现差异（行为等价）</h2>
 * <ul>
 *   <li>缓存：源用 Laravel 的 {@code Cache} 仓库（可跨进程）；这里用
 *       {@link ConcurrentHashMap}（进程内）。重启即失效、触发一次重编译 ——
 *       编译产物本身仍在磁盘上，只是多一次 mtime 比较，无正确性影响。</li>
 *   <li>插件：源依赖 {@code PluginManager}；这里用回调
 *       {@link PluginLangProvider} 注入，PF4J 接好后传入实现，未接时为空列表
 *       （即「没有插件」这一子集，与主语言包路径完全一致）。</li>
 *   <li>主语言包源定位：源始终读 {@code resource_path()}（文件系统）；这里
 *       {@code <base>/resources/lang} 缺失时退回 classpath 资源 mtime，仍拿不到
 *       则进程内首请求强制重编译一次 —— 保证 yml 更新 / 重启后 {@code public/lang}
 *       不会停留在陈旧产物上。</li>
 * </ul>
 */
public final class JavaScript {

    /** 源 {@code $prefix = 'front-end-trans-'}。 */
    public static final String PREFIX = "front-end-trans-";

    /** 编译产物的文件名前缀 {@code blessing.i18n = }。 */
    public static final String ASSIGNMENT = "blessing.i18n = ";

    private final AppPaths paths;

    private final Translator translator;

    private final PluginLangProvider plugins;

    /** {@code front-end-trans-<locale> → 上次编译时的 sourceModified}。 */
    private final Map<String, Long> cache = new ConcurrentHashMap<>();

    /** mtime 不可用时保证进程内每个 locale 至少重编译一次。 */
    private final java.util.Set<String> generatedOnce = ConcurrentHashMap.newKeySet();

    public JavaScript(AppPaths paths, Translator translator, PluginLangProvider plugins) {
        this.paths = paths;
        this.translator = translator;
        this.plugins = plugins == null ? locale -> List.of() : plugins;
    }

    /**
     * 插件语言包来源：返回 {@code (插件标识 → 该 locale 的 front-end.yml 路径)}
     * 的<b>有序</b>映射，键即源里的 {@code $plugin->name}，值是
     * {@code <plugin>/lang/<locale>/front-end.yml}（可能不存在，由调用方过滤）。
     */
    @FunctionalInterface
    public interface PluginLangProvider {

        List<PluginLang> forLocale(String locale);
    }

    /** 一个插件的语言包来源（pair of {@code plugin->name} / {@code plugin->namespace}）。 */
    public record PluginLang(String name, String namespace, String path) {
    }

    /**
     * {@code generate($locale)}：必要时重编译，返回带 {@code ?t=} 的资产路径。
     *
     * @return {@code asset("lang/<locale>.js?t=<mtime>")} 的相对路径形态
     *         （{@code /lang/zh_CN.js?t=...}）
     */
    public String generate(String locale) throws IOException {
        List<PluginLang> available = new java.util.ArrayList<>();
        for (PluginLang plugin : plugins.forLocale(locale)) {
            if (plugin.path() != null && Files.exists(Path.of(plugin.path()))) {
                available.add(plugin);
            }
        }

        Path mainSource = langSource(locale);
        long sourceModified = 0L;
        for (PluginLang plugin : available) {
            sourceModified = Math.max(sourceModified, lastModified(plugin.path()));
        }
        long mainModified = lastModified(mainSource.toString());
        if (mainModified == 0L) {
            // 主语言包不在文件系统（源 yml 打进 classpath/jar，见 TranslationFileLoader）
            // → 退回读 classpath 资源的 mtime，否则 yml 更新永远触发不了重编译
            mainModified = classpathLastModified(locale);
        }
        sourceModified = Math.max(sourceModified, mainModified);

        Path compiled = compiled(locale);
        long compiledModified = cache.getOrDefault(PREFIX + locale, 0L);

        // mtime 完全不可用时（源在 jar 内且连接层拿不到时间）退化为
        // 「进程内首次 generate 必重编译」：进程重启即刷新，语义与进程内缓存一致
        boolean forceOnce = sourceModified == 0L && !Files.exists(mainSource)
                && generatedOnce.add(PREFIX + locale);
        if (forceOnce || sourceModified > compiledModified || !Files.exists(compiled)) {
            Map<String, Object> translations = new LinkedHashMap<>();
            // trans('front-end')：不带点号的键 → 整表
            Object frontEnd = translator.get("front-end", Map.of(), locale);
            if (frontEnd instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    translations.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            for (PluginLang plugin : available) {
                Object ns = translator.get(plugin.namespace() + "::front-end", Map.of(), locale);
                if (ns != null) {
                    translations.put(plugin.name(), ns);
                }
            }

            write(compiled, ASSIGNMENT
                    + PhpJson.encode(translations, PhpJson.JSON_UNESCAPED_UNICODE));
            cache.put(PREFIX + locale, sourceModified);
            return asset(locale, sourceModified);
        }

        return asset(locale, compiledModified);
    }

    /** {@code resetTime($locale)}：cache 写回 0，强制下次重编译。 */
    public void resetTime(String locale) {
        cache.put(PREFIX + locale, 0L);
    }

    /** 编译产物里记录的 mtime（测试可观测）。 */
    public long cachedTime(String locale) {
        return cache.getOrDefault(PREFIX + locale, 0L);
    }

    // ================================================================= 内部

    /** {@code resource_path("lang/<locale>/front-end.yml")}。 */
    public Path langSource(String locale) {
        return paths.base().resolve("resources").resolve("lang")
                .resolve(locale).resolve("front-end.yml");
    }

    /** {@code public_path("lang/<locale>.js")}。 */
    public Path compiled(String locale) {
        return paths.base().resolve("public").resolve("lang").resolve(locale + ".js");
    }

    /** {@code url()->asset("lang/<locale>.js?t=<t>")} 的路径部分。 */
    private static String asset(String locale, long t) {
        return "/lang/" + locale + ".js?t=" + t;
    }

    /** {@code Filesystem::lastModified($path)}：不存在 / IO 失败 → 0。 */
    private static long lastModified(String path) {
        try {
            Path p = Path.of(path);
            return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() / 1000 : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * classpath 上 {@code /lang/<locale>/front-end.yml} 的 mtime（秒）。
     * 开发态落在 {@code target/classes}（真实文件，mtime 准确）；打进 jar 时
     * {@link java.net.URLConnection#getLastModified()} 通常返回 jar 本身的时间
     * （重新打包即变化），拿不到则返回 0、由 {@code forceOnce} 兜底。
     */
    private static long classpathLastModified(String locale) {
        try {
            java.net.URL url = JavaScript.class.getResource(
                    TranslationFileLoader.LANG_PREFIX + locale + "/front-end.yml");
            if (url == null) {
                return 0L;
            }
            if ("file".equals(url.getProtocol())) {
                Path p = Path.of(url.toURI());
                return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() / 1000 : 0L;
            }
            java.net.URLConnection conn = url.openConnection();
            return Math.max(conn.getLastModified() / 1000, 0L);
        } catch (IOException | java.net.URISyntaxException | IllegalArgumentException e) {
            return 0L;
        }
    }

    /** {@code Filesystem::put($path, $content)}：UTF-8、自动建父目录。 */
    private static void write(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
