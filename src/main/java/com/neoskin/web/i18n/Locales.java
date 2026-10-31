package com.neoskin.web.i18n;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.support.PhpArr;
import com.neoskin.support.PhpJson;

/**
 * {@code config/locales.php} 的移植。
 *
 * <p>表里 8 个键，其中 3 个是纯别名（只有 {@code alias}、没有 {@code name}）：
 *
 * <table border="1">
 *   <caption>语言表</caption>
 *   <tr><th>键</th><th>内容</th></tr>
 *   <tr><td>{@code zh_CN}</td><td>中文 (简体) / zh-CN</td></tr>
 *   <tr><td>{@code zh_HANS_CN}</td><td>别名 → {@code zh_CN}</td></tr>
 *   <tr><td>{@code zh_TW}</td><td>中文 (正體) / zh-TW</td></tr>
 *   <tr><td>{@code en}</td><td>English / en</td></tr>
 *   <tr><td>{@code en_US}</td><td>别名 → {@code en}</td></tr>
 *   <tr><td>{@code es_ES}</td><td>Español / <b>es</b>（唯一的 short_name 与键不同）</td></tr>
 *   <tr><td>{@code ru}</td><td>别名 → {@code ru_RU}</td></tr>
 *   <tr><td>{@code ru_RU}</td><td>Русский язык / ru</td></tr>
 * </table>
 *
 * <p>键顺序就是 {@code menu.*} 用例里语言下拉菜单的顺序，不能改成字母序：
 * {@code zh_CN, zh_HANS_CN, zh_TW, en, en_US, es_ES, ru, ru_RU}。
 *
 * <p>{@link #resolve(String)} 复刻 {@code DetectLanguagePrefer} 里的别名归一：
 * 命中且带 {@code alias} 就换成别名指向的键，其余（含未命中）原样返回。
 * 注意它<b>不</b>做 fallback —— 那一步在中间件里，并且只认 {@code Arr::has}。
 */
public final class Locales {

    /** 源项目 {@code config/app.php} 的 {@code locale}。 */
    public static final String APP_LOCALE = "zh_CN";

    /** 源项目 {@code config/app.php} 的 {@code fallback_locale}（{@code APP_FALLBACK_LOCALE} 默认值）。 */
    public static final String FALLBACK_LOCALE = "en";

    /** 语言表本体，保持 PHP 数组的书写顺序。 */
    private static final Map<String, Map<String, String>> TABLE = build();

    private Locales() {
    }

    private static Map<String, Map<String, String>> build() {
        Map<String, Map<String, String>> table = new LinkedHashMap<>();
        table.put("zh_CN", entry("name", "中文 (简体)", "short_name", "zh-CN"));
        table.put("zh_HANS_CN", entry("alias", "zh_CN"));
        table.put("zh_TW", entry("name", "中文 (正體)", "short_name", "zh-TW"));
        table.put("en", entry("name", "English", "short_name", "en"));
        table.put("en_US", entry("alias", "en"));
        table.put("es_ES", entry("name", "Español", "short_name", "es"));
        table.put("ru", entry("alias", "ru_RU"));
        table.put("ru_RU", entry("name", "Русский язык", "short_name", "ru"));
        return java.util.Collections.unmodifiableMap(table);
    }

    private static Map<String, String> entry(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** 完整的 8 键表（只读）。 */
    public static Map<String, Map<String, String>> table() {
        return TABLE;
    }

    /** {@code Arr::has(config('locales'), $locale)} 的等价实现。 */
    public static boolean has(Object key) {
        return PhpArr.has(TABLE, key);
    }

    /**
     * {@code Arr::get(config('locales'), $locale)} 的等价实现。
     *
     * <p>{@code key == null} 时返回整张表（{@link PhpArr#get} 的既有语义），
     * 语料用例 {@code lookup.null} 正是这么断言的。
     */
    public static Object get(Object key) {
        return PhpArr.get(TABLE, key);
    }

    /**
     * 别名归一：{@code en_US → en}、{@code ru → ru_RU}、{@code zh_HANS_CN → zh_CN}。
     *
     * <p>对应 {@code DetectLanguagePrefer} 里那段：
     * <pre>
     * if (($info = Arr::get(config('locales'), $locale)) &amp;&amp; ($alias = Arr::get($info, 'alias'))) {
     *     $locale = $alias;
     * }
     * </pre>
     * 未命中时 {@code Arr::get} 返回 null，条件短路，原值返回。
     */
    public static String resolve(String locale) {
        Object info = get(locale);
        if (PhpArr.truthy(info)) {
            Object alias = PhpArr.get(info, "alias");
            if (PhpArr.truthy(alias)) {
                return String.valueOf(alias);
            }
        }
        return locale;
    }

    /** 只有 {@code name}/{@code short_name} 的真实语言（别名被过滤掉），顺序同源文件。 */
    public static Map<String, Map<String, String>> available() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        TABLE.forEach((id, info) -> {
            if (!PhpArr.has(info, "alias")) {
                out.put(id, info);
            }
        });
        return out;
    }

    /** 真实语言的键列表，例如 {@code [zh_CN, zh_TW, en, es_ES, ru_RU]}。 */
    public static List<String> ids() {
        return new ArrayList<>(available().keySet());
    }

    /** 语言下拉菜单里显示的 {@code name}，缺失时按 PHP 语义返回 null。 */
    public static String displayName(String id) {
        Object info = get(id);
        if (!PhpArr.truthy(info)) {
            return null;
        }
        Object name = PhpArr.get(info, "name");
        return name == null ? null : String.valueOf(name);
    }

    /** 语言代码的短名，例如 {@code es_ES → es}。 */
    public static String shortName(String id) {
        Object info = get(id);
        if (!PhpArr.truthy(info)) {
            return null;
        }
        Object shortName = PhpArr.get(info, "short_name");
        return shortName == null ? null : String.valueOf(shortName);
    }

    /** 把表导出成 {@link PhpJson.Array}，供需要「像 PHP 数组一样」遍历的调用方使用。 */
    public static PhpJson.Array toPhpArray() {
        PhpJson.Array array = new PhpJson.Array();
        TABLE.forEach((id, info) -> {
            PhpJson.Array inner = new PhpJson.Array();
            info.forEach(inner::put);
            array.put(id, inner);
        });
        return array;
    }
}
