package com.neoskin.web.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.support.PhpJson;
import com.neoskin.support.SymfonyYaml;

/**
 * 翻译文件加载链：源项目是
 * {@code Spatie\TranslationLoader\TranslationLoaderManager extends
 * Illuminate\Translation\FileLoader}，应用侧
 * {@code App\Services\Translations\Loader} 只把解析从 {@code .php} 换成 {@code .yml}
 * （{@code Symfony\Yaml::parseFile} 语义，见 {@link SymfonyYaml}）。
 *
 * <p>装载 {@code (locale, group, namespace)} 的规则（顺序是行为契约）：</p>
 *
 * <ol>
 *   <li>安全检查：locale / group 含 {@code ..}、{@code \}、{@code \0}、
 *       {@code /}（group 允许一级目录）→ 返回空表，不碰文件系统</li>
 *   <li>{@code group === '*' && namespace === '*'} → {@code lang/<locale>.json}
 *       优先，再叠数据库 {@code group='*'} 的平铺行（{@code lines[key]}）</li>
 *   <li>namespace 为 null 或 {@code '*'} → {@code lang/<locale>/<group>.yml}</li>
 *   <li>常规 namespace → {@code lang/vendor/<ns>/<locale>/<group>.yml}，
 *       用 {@code array_replace_recursive} 叠加本地 override</li>
 * </ol>
 *
 * <p><b>递归覆盖</b>在两层各有语义（都已锁定）：</p>
 * <ul>
 *   <li>{@code paths} 列表内：后一个存在的文件 {@code array_replace_recursive($output, $file)}
 *       —— 也就是说列表后面的路径覆盖前面的；</li>
 *   <li>Spatie 层：{@code array_replace_recursive($fileTranslations, $dbTranslations)}
 *       —— 数据库行覆盖文件（{@code redirectToSetup} 未安装时把 loader 列表清空，
 *       {@code DbLoader} 由调用方置 {@code null} 表达）。</li>
 * </ul>
 *
 * <p>本应用 {@code paths} 只有一个（{@code path.lang}），所以第一层覆盖在 Java 侧
 * 退化为「找到即读」；未来插件（PF4J）注册路径时可复用 {@link #addPath(String)}。
 * 多路径支持已按同一 reduce 语义实现，见 {@link #loadFromPaths(List, String, String)}。</p>
 */
public final class TranslationFileLoader implements TranslationLoader {

    /** classpath 上 lang 目录的公共前缀（对应源 path.lang = resources/lang）。 */
    public static final String LANG_PREFIX = "/lang/";

    /** 常规路径（相对 lang 前缀的内层子目录，默认空 = 根目录）。 */
    private final List<String> paths = new ArrayList<>();

    /** JSON 翻译路径（相对前缀；缺省也是空，应用没有 JSON 翻译）。 */
    private final List<String> jsonPaths = new ArrayList<>();

    /** 数据库覆盖层；{@code null} → 不加 DB 层（Spatie 的 loader 列表为空）。 */
    private DbTranslationLoader dbLoader;

    // ================================================================= load()

    @Override
    public Map<String, Object> load(String locale, String group, String namespace) {
        if (isUnsafePathSegment(locale, false)
                || (!"*".equals(group) && isUnsafePathSegment(group, true))) {
            return Map.of();
        }

        if ("*".equals(group) && "*".equals(namespace)) {
            return loadJsonPaths(locale);
        }

        if (namespace == null || "*".equals(namespace)) {
            return loadPaths(locale, group);
        }

        return loadNamespaced(locale, group, namespace);
    }

    public void setDbLoader(DbTranslationLoader dbLoader) {
        this.dbLoader = dbLoader;
    }

    /** 源 {@code addPath()}：children of {@code lang/}（供插件复用）。 */
    public void addPath(String child) {
        paths.add(child);
    }

    /** 源 {@code addJsonPath()}。 */
    public void addJsonPath(String child) {
        jsonPaths.add(child);
    }

    // ================================================================ 常规/JSON

    /**
     * {@code FileLoader::loadPaths}：对 paths 列表做 reduce，后一个存在的文件
     * 用 {@code array_replace_recursive($output, $file)} 覆盖。
     */
    private Map<String, Object> loadPaths(String locale, String group) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String path : pathCandidates(locale, group)) {
            Map<String, Object> file = parseYaml(LANG_PREFIX + path);
            if (file != null) {
                out = mergeLeft(out, file);
            }
        }
        if (paths.isEmpty()) {
            // paths 还没注册任何目录时，用「builtin classpath」one path 语义：
            // 源只有一个 path.lang，不需要 fallback
            Map<String, Object> file = parseYaml(resourcePathFor(locale, group, null));
            if (file != null) {
                out = mergeLeft(out, file);
            }
        }

        Map<String, Object> db = loadDb(locale, group);
        if (!db.isEmpty()) {
            out = mergeLeft(out, db);
        }
        return out;
    }

    /** 枚举 paths 数组里的实际文件（相对 lang 的 children）。 */
    private List<String> pathCandidates(String locale, String group) {
        List<String> out = new ArrayList<>();
        for (String child : paths) {
            String resource = LANG_PREFIX + child + locale + "/" + group + ".yml";
            if (resourceExists(resource)) {
                out.add(child + locale + "/" + group + ".yml");
            }
        }
        return out;
    }

    static String resourcePathFor(String locale, String group, String subNamespace) {
        if (subNamespace == null) {
            return LANG_PREFIX + locale + "/" + group + ".yml";
        }
        return LANG_PREFIX + "vendor/" + subNamespace + "/" + locale + "/" + group + ".yml";
    }

    /** {@code FileLoader::loadJsonPaths}（组 {@code * + * }）。 */
    private Map<String, Object> loadJsonPaths(String locale) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 源 jsonPaths 的 reduce 列表 = jsonPaths + paths（文件存在才覆盖），
        // 本应用没有注册任何 json path → classpath 默认 lang/<locale>.json 一个。
        List<String> candidates = new ArrayList<>(jsonPaths);
        candidates.add(locale + ".json");
        for (String candidate : candidates) {
            String resource = LANG_PREFIX + candidate;
            Map<String, Object> file = parseJson(resource);
            if (file != null) {
                out = mergeLeft(out, file);
            }
        }

        Map<String, Object> db = loadDb(locale, "*");
        if (!db.isEmpty()) {
            out.putAll(db);
        }
        return out;
    }

    // ============================================================== namespace

    /** {@code FileLoader::loadNamespaced + loadNamespaceOverrides}。 */
    private Map<String, Object> loadNamespaced(String locale, String group, String namespace) {
        if (!namespaces.containsKey(namespace)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        String hint = namespaces.get(namespace);
        Map<String, Object> file = parseYaml(hint + locale + "/" + group + ".yml");
        if (file != null) {
            out.putAll(file);
        }

        for (String path : overrideCandidates(namespace, locale, group)) {
            Map<String, Object> override = parseYaml(path);
            if (override != null) {
                out = mergeLeft(out, override);
            }
        }
        return out;
    }

    /** 源 {@code hints}：namespace → hint 目录。 */
    private final Map<String, String> namespaces = new LinkedHashMap<>();

    /** 源 {@code addNamespace($namespace, $hint)}。 */
    public void addNamespace(String namespace, String hint) {
        namespaces.put(namespace, hint);
    }

    /** 命名空间的本地 override（paths 下 {@code vendor/<ns>/<locale>/<group>.yml}）。 */
    private List<String> overrideCandidates(String namespace, String locale, String group) {
        List<String> out = new ArrayList<>();
        for (String child : paths) {
            String resource = LANG_PREFIX + child + "vendor/" + namespace + "/" + locale + "/" + group + ".yml";
            if (resourceExists(resource)) {
                out.add(resource);
            }
        }
        return out;
    }

    // ================================================================== DB 层

    private Map<String, Object> loadDb(String locale, String group) {
        if (dbLoader == null) {
            return Map.of();
        }
        Map<String, Object> loaded = dbLoader.loadTranslations(locale, group);
        return loaded == null ? Map.of() : loaded;
    }

    // ================================================================ 文件读取

    /** 解析一个 yml 资源；资源不存在返回 {@code null}。 */
    static Map<String, Object> parseYaml(String resource) {
        if (!resourceExists(resource)) {
            return null;
        }
        try (InputStream in = SymfonyYaml.class.getResourceAsStream(resource);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return SymfonyYaml.parse(reader);
        } catch (IOException e) {
            throw new IllegalStateException("翻译文件读取失败: " + resource, e);
        }
    }

    /** 解析一个 JSON 资源（单层 key → value）；资源不存在返回 {@code null}。 */
    static Map<String, Object> parseJson(String resource) {
        if (!resourceExists(resource)) {
            return null;
        }
        try (InputStream in = SymfonyYaml.class.getResourceAsStream(resource);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return readJson(reader);
        } catch (IOException e) {
            throw new IllegalStateException("JSON 翻译文件读取失败: " + resource, e);
        }
    }

    /**
     * 源 {@code isUnsafePathSegment}：翻译键不能“逃出”翻译目录。
     * group 允许一级目录斜杠（{@code allowSlashes=true}）。
     */
    static boolean isUnsafePathSegment(String value, boolean allowSlashes) {
        return value == null
                || value.isEmpty()
                || value.contains("..")
                || value.contains("\\")
                || value.contains("\0")
                || (!allowSlashes && value.contains("/"));
    }

    private static boolean resourceExists(String resource) {
        return SymfonyYaml.class.getResource(resource) != null;
    }

    /** {@code array_replace_recursive($output, $file)}：$file 覆盖 $output。 */
    static Map<String, Object> mergeLeft(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> e : source.entrySet()) {
            Object current = target.get(e.getKey());
            if (current instanceof Map<?, ?> cur && e.getValue() instanceof Map<?, ?> other) {
                // 两边都是 Map → 递归归并
                target.put(e.getKey(), mergeMaps(cur, other));
            } else {
                target.put(e.getKey(), e.getValue());
            }
        }
        return target;
    }

    /** {@code mergeMaps}：递归替换标量、递归合并 Map，键序 = target 顺序 + source 新增键。 */
    private static Map<String, Object> mergeMaps(Map<?, ?> target, Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : target.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (source.containsKey(entry.getKey())) {
                Object other = source.get(entry.getKey());
                if (value instanceof Map<?, ?> targetMap && other instanceof Map<?, ?> otherMap) {
                    out.put(key, mergeMaps(targetMap, otherMap));
                } else {
                    out.put(key, other);
                }
            } else if (value instanceof Map<?, ?> map) {
                out.put(key, deepCopy(map));
            } else {
                out.put(key, value);
            }
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!target.containsKey(entry.getKey())) {
                Object value = entry.getValue();
                out.put(key, value instanceof Map<?, ?> map ? deepCopy(map) : value);
            }
        }
        return out;
    }

    private static Object deepCopy(Map<?, ?> map) {
        return mergeMaps(map, Map.of());
    }

    /** 简单的 JSON 对象解码（返回 Map，值可能是 String / Number / Boolean / 嵌套 Map）。 */
    private static Map<String, Object> readJson(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = reader.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        Object decoded = PhpJson.decode(sb.toString());
        if (decoded instanceof Map<?, ?> map) {
            return (Map<String, Object>) (Map<?, ?>) map;
        }
        throw new IllegalStateException("JSON 翻译文件顶层必须是对象");
    }
}
