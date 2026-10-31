package com.neoskin.web.i18n;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.support.PhpArr;

/**
 * {@code Illuminate\Translation\Translator} 的移植。
 *
 * <p>只覆盖 Web 层用到的 {@code get / has / hasForLocale / setLocale / getLocale /
 * setFallback}（源里 {@code choice} 的 {@code |} 复数语义没有调用方 —— 本应用
 * {@code app/}、{@code resources/views/} 内没有任何 {@code trans_choice / ->choice}
 * 调用，保留 {@code fallback} 行为即可）。</p>
 *
 * <h2>必须照抄的 get() 流程</h2>
 * <pre>
 * get($key, $replace, $locale = null, $fallback = true):
 *   $locale = $locale ?: $this-&gt;locale;
 *   $this-&gt;load('*', '*', $locale);
 *   $line = $this-&gt;loaded['*']['*'][$locale][$key] ?? null;
 *   if (! isset($line)) {
 *       [$namespace, $group, $item] = $this-&gt;parseKey($key);
 *       foreach ($this-&gt;localeArray($locale) as $l) {   // [$locale, $fallback] 去重
 *           if (! is_null($line = $this-&gt;getLine($ns, $group, $l, $item, $replace))) return $line;
 *       }
 *   }
 *   return $this-&gt;makeReplacements($line ?: $key, $replace);
 * </pre>
 *
 * <p>{@code getLine}：{@code Arr::get($this-&gt;loaded[ns][group][locale], $item)}；
 * 是字符串 → 换占位符；是非空数组 → 对每个叶子递归换占位符；否则 {@code null}。</p>
 *
 * <h2>parseKey（{@code NamespacedItemResolver} + Translator 的 {@code [0]='*'}）</h2>
 * <ol>
 *   <li>含 {@code ::} → {@code [ns, group(第一段), item(后续点拼)]}；</li>
 *   <li>否则按 {@code .} 切：第一段是 group，其余重新点拼是 item；
 *       只有一段 → {@code [*, $segment, null]}</li>
 * </ol>
 *
 * <h2>makeReplacements（{:name}/{:Name}/{:NAME} 三种占位符）</h2>
 * <pre>
 * foreach ($replace as $key => $value) {
 *     $shouldReplace[':'.Str::ucfirst($key)] = Str::ucfirst($value ?? '');
 *     $shouldReplace[':'.Str::upper($key)]   = Str::upper($value ?? '');
 *     $shouldReplace[':'.$key]               = $value;
 * }
 * return strtr($line, $shouldReplace);
 * </pre>
 */
public final class Translator {

    private final TranslationFileLoader loader;

    /**
     * DB 覆盖层（Spatie {@code TranslationLoaderManager} 的等价物）。
     *
     * <p>源把 {@code FileLoader} 换成了 {@code TranslationLoaderManager extends FileLoader}，
     * 每次 {@code load()} 都做：</p>
     * <pre>
     * $fileTranslations = parent::load($locale, $group, $namespace);
     * if ($namespace !== null &amp;&amp; $namespace !== '*') return $fileTranslations;  // 插件命名空间不查库
     * return array_replace_recursive($fileTranslations, $dbTranslations);       // 库覆盖文件
     * </pre>
     *
     * <p>之前本类只读文件，导致 admin 后台改的 {@code language_lines} 在页面上
     * 完全不生效 —— 这是个真实缺口，不是「未来优化」。{@code null} 表示未接库
     * （单测/无库场景），行为与「库为空」一致。</p>
     */
    private final DbTranslationLoader overlay;

    /** {@code app.locale}（构造时传入，可中途 setLocale）。 */
    private String locale;

    /**
     * 语言来源（可空）。为 {@code null} 时用 {@link #locale} 这个固定值。
     *
     * <p>Web 层要传 {@code Option::currentLocale} —— 语言是<b>按请求</b>由
     * {@code DetectLanguagePrefer} 写进 ThreadLocal 的，而 {@code Translator}
     * 是单例 Bean；用固定字段会在并发请求间串语言。</p>
     */
    private final java.util.function.Supplier<String> localeSource;

    /** {@code app.fallback_locale}。 */
    private final String fallback;

    public Translator(TranslationFileLoader loader, String locale, String fallback) {
        this(loader, null, locale, fallback);
    }

    public Translator(TranslationFileLoader loader, DbTranslationLoader overlay,
            String locale, String fallback) {
        this(loader, overlay, locale, fallback, false);
    }

    /**
     * 动态语言来源版本（Web 层用）。
     *
     * <p>{@code Translator} 是单例 Bean，而语言是<b>按请求</b>由
     * {@code DetectLanguagePrefer} 写进 ThreadLocal 的
     * （{@link com.neoskin.service.Option#currentLocale()}）。用固定字段会在并发
     * 请求间串语言，所以 Web 层传 {@code Option::currentLocale} 当来源。</p>
     *
     * @param localeSource 每次取当前语言；{@code null} 表示用固定 {@code locale}
     */
    public Translator(TranslationFileLoader loader, DbTranslationLoader overlay,
            String localeSource, String fallback, boolean dynamic) {
        this.loader = loader;
        this.overlay = overlay;
        this.locale = localeSource;
        this.fallback = fallback;
        this.localeSource = dynamic
                ? com.neoskin.service.Option::currentLocale : null;
    }

    public String getLocale() {
        return localeSource != null ? localeSource.get() : locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    /**
     * {@code trans($key, $replace = [], $locale = null)} 的主方法。
     *
     * <p>返回类型是 {@code Object}：源 {@code Translator::get} 可能返回字符串
     * <b>或整个数组</b>（{@code trans('front-end')} 这种不带点号的键走
     * {@code Arr::get($lines, null)} → 整表）。调用方若确定是单值，用
     * {@link #trans(String, Map, String)} 取字符串视图。</p>
     *
     * <p>未命中时返回<b>原 key</b>（源 {@code makeReplacements($line ?: $key)}）。</p>
     */
    public Object get(String key, Map<String, Object> replace, String locale) {
        if (key == null) {
            return "";
        }
        String l = locale != null && !locale.isEmpty() ? locale : getLocale();

        // JSON 翻译路径：load('*', '*', $locale) 的平铺表
        Map<String, Object> json = loadMerged(l, "*", "*");
        Object line = json.get(key);

        // 常规翻译文件路径
        if (line == null) {
            String[] keyParts = parseKey(key);
            String namespace = keyParts[0];
            String group = keyParts[1];
            String item = keyParts[2];

            for (String candidate : localeArray(l)) {
                Object found = getLine(namespace, group, candidate, item, replace);
                if (found != null) {
                    return found;
                }
            }
            return key;
        }

        // JSON 组的值是平铺字符串；非字符串（理论上不应出现）原样返回
        return line instanceof String s ? makeReplacements(s, replace) : line;
    }

    /** 单值视图：把 {@link #get} 的结果按 PHP 字符串语义收敛。 */
    public String trans(String key, Map<String, Object> replace, String locale) {
        Object value = get(key, replace, locale);
        return value == null ? "" : String.valueOf(value);
    }

    /** 源 {@code hasForLocale($key, $locale)}。 */
    public boolean hasForLocale(String key, String locale) {
        return has(key, locale, false);
    }

    /** 源 {@code has}：存在则 true；JSON 组直读；否则看 get() 结果 ≠ key。 */
    public boolean has(String key, String locale, boolean useFallback) {
        String l = locale != null && !locale.isEmpty() ? locale : getLocale();

        Map<String, Object> json = loadMerged(l, "*", "*");
        Object direct = json.get(key);
        if (direct != null) {
            return true;
        }

        String[] keyParts = parseKey(key);
        String group = keyParts[1];
        String item = keyParts[2];
        if (item == null) {
            return !loadMerged(l, group, keyParts[0]).isEmpty();
        }
        Object value = PhpArr.get(loadMerged(l, group, keyParts[0]), item, null);
        return value != null;
    }

    // ================================================================= 内部

    /**
     * 源 {@code getLine}：加载后用 {@code Arr::get} 取值，再按类型处理。
     *
     * <pre>
     * if (is_string($line))     return makeReplacements($line, $replace);
     * elseif (is_array($line) && $line !== []) {
     *     array_walk_recursive($line, fn (&$v) => $v = makeReplacements($v, $replace));
     *     return $line;
     * }
     * // 空数组 / 其它类型 → 返回 null（未命中）
     * </pre>
     *
     * <p>{@code item == null}（即 {@code trans('front-end')} 这种不带点号的键）
     * 时 {@code Arr::get($lines, null)} 返回整表 —— 这正是
     * {@code trans('front-end')} 得到整个嵌套数组的途径。</p>
     */
    /**
     * Spatie {@code TranslationLoaderManager::load()} 的等价实现：
     * 文件优先读出，DB 覆盖层用 {@code array_replace_recursive} 盖上去。
     *
     * <pre>
     * $fileTranslations = parent::load($locale, $group, $namespace);
     * if ($namespace !== null &amp;&amp; $namespace !== '*') return $fileTranslations;
     * return array_replace_recursive($fileTranslations, $dbTranslations);
     * </pre>
     *
     * <p>三个必须照抄的行为：</p>
     * <ol>
     *   <li>插件命名空间（{@code namespace != "*"}）<b>不查库</b> —— 库只覆盖主语言包；</li>
     *   <li>DB 有值时<b>以 DB 为准</b>（覆盖文件），DB 没有的键保留文件值；</li>
     *   <li>两侧都是数组时<b>递归</b>合并，不是整块替换。</li>
     * </ol>
     */
    private Map<String, Object> loadMerged(String locale, String group, String namespace) {
        Map<String, Object> files = this.loader.load(locale, group, namespace);

        // 插件命名空间不查库（源: $namespace !== null && $namespace !== '*'）
        if (overlay == null || (namespace != null && !"*".equals(namespace))) {
            return files;
        }

        Map<String, Object> db = overlay.loadTranslations(locale, group);
        if (db == null || db.isEmpty()) {
            return files;
        }
        return arrayReplaceRecursive(files, db);
    }

    /**
     * {@code array_replace_recursive($a, $b)}：键按 {@code $a} 的顺序保留，
     * {@code $b} 新增的键追加在后；两侧同为数组则递归，否则 {@code $b} 覆盖。
     */
    private static Map<String, Object> arrayReplaceRecursive(
            Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : override.entrySet()) {
            Object existing = out.get(e.getKey());
            Object incoming = e.getValue();
            if (existing instanceof Map<?, ?> a && incoming instanceof Map<?, ?> b) {
                out.put(e.getKey(), arrayReplaceRecursive(castMap(a), castMap(b)));
            } else {
                out.put(e.getKey(), incoming);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private Object getLine(String namespace, String group, String locale, String item,
            Map<String, Object> replace) {
        Map<String, Object> lines = loadMerged(locale, group, namespace);
        Object line = PhpArr_get(lines, item);
        if (line instanceof String s) {
            return makeReplacements(s, replace);
        }
        if (line instanceof Map<?, ?> map && !map.isEmpty()) {
            return walkRecursive(map, replace);
        }
        if (line instanceof List<?> list && !list.isEmpty()) {
            return walkRecursive(list, replace);
        }
        return null;
    }

    /** {@code array_walk_recursive}：对每个叶子做占位符替换。 */
    @SuppressWarnings("unchecked")
    private static Object walkRecursive(Object node, Map<String, Object> replace) {
        if (replace == null || replace.isEmpty()) {
            return node;
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), walkRecursive(e.getValue(), replace));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object v : list) {
                out.add(walkRecursive(v, replace));
            }
            return out;
        }
        return node instanceof String s ? makeReplacements(s, replace) : node;
    }

    /**
     * 源 {@code localeArray}：[$locale, $fallback]，去重、去空。
     * 源 {@code array_filter} 把空串也剔除，{@code array_values(array_unique(...))} 保序去重。
     */
    private List<String> localeArray(String locale) {
        List<String> locales = new ArrayList<>();
        String primary = locale != null && !locale.isEmpty() ? locale : getLocale();
        if (!primary.isEmpty()) {
            locales.add(primary);
        }
        if (fallback != null && !fallback.isEmpty() && !locales.contains(fallback)) {
            locales.add(fallback);
        }
        return locales;
    }

    /**
     * {@code NamespacedItemResolver::parseKey + Translator::parseKey 的 [0]='*'}。
     */
    public static String[] parseKey(String key) {
        if (key.contains("::")) {
            int pos = key.indexOf("::");
            String namespace = key.substring(0, pos);
            String item = key.substring(pos + 2);
            // parseBasicSegments(itemSegments)：[ns, group, item]
            String group = String.valueOf(firstSegment(item));
            String rest = rest(item);
            return new String[] { namespace, group, rest };
        }
        // 不含 :: → 按 . 切：group = 第一段，item = 其余点拼
        int first = key.indexOf('.');
        if (first < 0) {
            return new String[] { "*", key, null };
        }
        return new String[] { "*", key.substring(0, first), key.substring(first + 1) };
    }

    private static String firstSegment(String item) {
        int pos = item.indexOf('.');
        return pos < 0 ? item : item.substring(0, pos);
    }

    /** parseBasicSegments 的 item 部分（第二段之后的点拼）；只剩一段→null。 */
    private static String rest(String item) {
        int pos = item.indexOf('.');
        return pos < 0 ? null : item.substring(pos + 1);
    }

    /** 源 {@code makeReplacements}，{:name}/{:Name}/{:NAME}。 */
    public static String makeReplacements(String line, Map<String, Object> replace) {
        if (replace == null || replace.isEmpty()) {
            return line;
        }
        // 源 strtr：先建 shouldReplace 表，一次替换。这里同样按源顺序填表
        Map<String, String> replaceMap = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : replace.entrySet()) {
            String key = e.getKey();
            String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
            replaceMap.put(":" + phpUcfirst(key), phpUcfirst(value));
            replaceMap.put(":" + phpStrUpper(key), phpStrUpper(value));
            replaceMap.put(":" + key, value);
        }
        return phpStrtr(line, replaceMap);
    }

    /**
     * {@code strtr} 的补齐：最长匹配优先。
     */
    private static String phpStrtr(String line, Map<String, String> replace) {
        StringBuilder sb = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            boolean matched = false;
            String longest = null;
            for (Map.Entry<String, String> e : replace.entrySet()) {
                String from = e.getKey();
                if (line.startsWith(from, i)
                        && (longest == null || from.length() > longest.length())) {
                    longest = from;
                }
            }
            if (longest != null) {
                sb.append(replace.get(longest));
                i += longest.length() - 1;
                matched = true;
            }
            if (!matched) {
                sb.append(line.charAt(i));
            }
        }
        return sb.toString();
    }

    /** {@code Str::ucfirst}：首个字符大写。 */
    static String phpUcfirst(String s) {
        if (s.isEmpty()) {
            return "";
        }
        int first = s.codePointAt(0);
        String upper = new String(Character.toChars(first)).toUpperCase(java.util.Locale.ROOT);
        // PHP Str::ucfirst 用 mb_strtoupper 单字符；单个码点的（非代理对）→上
        int take = Character.charCount(first);
        return upper + s.substring(take);
    }

    /** {@code Str::upper}: mb_strtoupper。 */
    static String phpStrUpper(String s) {
        return s.toUpperCase(java.util.Locale.ROOT);
    }

    /** {@code Arr::get} 的移植：直接挪用 {@link PhpArr}。 */
    private static Object PhpArr_get(Map<String, Object> array, String key) {
        return PhpArr.get(array, key, null);
    }
}
