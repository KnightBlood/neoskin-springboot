package com.neoskin.web.i18n;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.support.PhpArr;
import com.neoskin.support.PhpUrl;
import com.neoskin.web.PhpUrlGenerator;

/**
 * {@code LanguagesMenuComposer} 的移植。
 *
 * <p>源项目里这是一个视图 composer，往视图塞两个变量：
 *
 * <pre>
 * $query = $this->request-&gt;query();
 * $path  = $this->request-&gt;path();
 *
 * $langs = collect(config('locales'))
 *     -&gt;reject(fn ($locale) =&gt; Arr::has($locale, 'alias'))
 *     -&gt;map(function ($locale, $id) use ($query, $path) {
 *         $query = array_merge($query, ['lang' =&gt; $id]);
 *         $locale['url'] = url($path.'?'.http_build_query($query));
 *         return $locale;
 *     });
 *
 * $current = config('locales.'.app()-&gt;getLocale().'.name');
 * </pre>
 *
 * <p>三个容易漏掉的细节：
 *
 * <ol>
 *   <li>{@code request-&gt;path()} 对根路径返回 {@code "/"}（不是空串），
 *       于是拼出 {@code "/?lang=zh_CN"}，而 {@code UrlGenerator::format()} 末尾的
 *       {@code trim($x, '/')} 会把 {@code http://localhost/} 收成
 *       {@code http://localhost}——这就是语料 {@code menu.root} 里没有斜杠的原因；</li>
 *   <li>{@code $query} 在闭包里是<b>按值捕获</b>，每轮都从原始 query 重新 merge，
 *       所以上一轮加进去的 {@code lang} 不会污染下一轮（结果一样，但语义不同）；</li>
 *   <li>别名项（{@code zh_HANS_CN}、{@code en_US}、{@code ru}）被 {@code reject} 掉，
 *       所以菜单只有 5 项，顺序是源文件的书写顺序。</li>
 * </ol>
 */
public final class LanguagesMenu {

    /** 视图里的 {@code current} 与 {@code langs}。 */
    public record Result(String current, Map<String, Map<String, String>> langs) {
    }

    private LanguagesMenu() {
    }

    /**
     * 生成语言下拉菜单的数据。
     *
     * @param path       {@code $request-&gt;path()}，根路径是 {@code "/"}，其余不带前导斜杠
     * @param query      当前请求的查询参数（保持顺序）
     * @param appLocale  当前应用语言，例如 {@code en}
     * @param generator  用于 {@code url()} 的生成器
     */
    public static Result compose(String path, Map<String, String> query, String appLocale,
            PhpUrlGenerator generator) {
        Map<String, Map<String, String>> langs = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, String>> entry : Locales.table().entrySet()) {
            String id = entry.getKey();
            Map<String, String> info = entry.getValue();
            if (PhpArr.has(info, "alias")) {
                continue;
            }

            Map<String, String> merged = new LinkedHashMap<>(query == null ? Map.of() : query);
            merged.put("lang", id);

            String target = path + "?" + PhpUrl.buildQuery(merged);

            Map<String, String> withUrl = new LinkedHashMap<>(info);
            withUrl.put("url", generator.to(target));
            langs.put(id, withUrl);
        }

        return new Result(Locales.displayName(appLocale), langs);
    }

    /** 语言项里固定会有的三个键，方便断言时比对顺序。 */
    public static List<String> expectedKeys() {
        return List.of("name", "short_name", "url");
    }
}
