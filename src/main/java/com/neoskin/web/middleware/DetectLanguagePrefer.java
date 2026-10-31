package com.neoskin.web.middleware;

import java.util.List;

import com.neoskin.service.Option;
import com.neoskin.support.PhpArr;
import com.neoskin.web.http.AcceptLanguage;
import com.neoskin.web.i18n.Locales;

/**
 * {@code App\Http\Middleware\DetectLanguagePrefer} 的移植。
 *
 * <p>源文件只有 30 行，但含一个<b>必须原样保留</b>的缺陷：
 *
 * <pre>
 * $locale = $request-&gt;input('lang')
 *     ?? $request-&gt;cookie('locale')
 *     ?? $request-&gt;getPreferredLanguage();
 * if (($info = Arr::get(config('locales'), $locale)) &amp;&amp; ($alias = Arr::get($info, 'alias'))) {
 *     $locale = $alias;
 * }
 * $locale ?? app()-&gt;getLocale();          // &lt;-- 缺了 "$locale ="，是个空语句
 * if (!Arr::has(config('locales'), $locale)) {
 *     $locale = config('app.fallback_locale');
 * }
 * </pre>
 *
 * <p>第 22 行本该是「全都没有就退回当前 locale」，实际不生效，于是三个来源都为空时
 * 走的是 {@code fallback_locale}（{@code en}），而不是 {@code app.locale}（{@code zh_CN}）。
 * 语料用例 {@code resolve.nothing} / {@code quirk.fallback_not_app_locale} 与
 * {@code resolve.nothing_initial_en} / {@code resolve.nothing_initial_ru}（初始 locale 不同
 * 但结果都是 {@code en}）锁定的就是这个行为。<b>不要「修」它</b>：否则中文站点
 * 的匿名首次访问会从英文变成中文，与源站不一致。
 *
 * <p>第二个坑是 {@code ??} 只跳过 {@code null}，所以 {@code ?lang=}（空串）
 * 会挡住 cookie 与 {@code Accept-Language}，最后落到 fallback（{@code quirk.empty_lang_is_set}）。
 *
 * <p>第三个坑是别名的<b>大小写敏感</b>：{@code config/locales.php} 的别名键是
 * {@code zh_HANS_CN}，而 {@code Accept-Language: zh-Hans-CN} 经 {@code formatLocale()}
 * 得到 {@code zh_Hans_CN}，两者不相等，于是落回 fallback（{@code resolve.alias_from_accept}
 * 期望 {@code en}）。这不是 bug，是源站的真实行为。
 *
 * <p>第四个坑是 {@code api} 检查：`in_array('api', route()->middleware())`
 * 判断的是路由自己的中间件数组（组名也算），不是「uri 以 api 开头」。
 * 命中时不回写 cookie，但<b>解析照做</b>（{@code resolve.api_lang} 仍然切到 {@code ru_RU}）。
 */
public final class DetectLanguagePrefer {

    /** {@code locale} cookie 名。 */
    public static final String COOKIE_NAME = "locale";

    /** {@code $response-&gt;cookie('locale', $locale, 120)} 里的 120 分钟。 */
    public static final int COOKIE_LIFETIME_MINUTES = 120;

    private DetectLanguagePrefer() {
    }

    /** 一次解析的输入。{@code null} 与空串在 {@code input()} / {@code cookie()} 里含义不同，故都用 {@link String}。 */
    public record Input(
            String lang,
            String cookieLocale,
            String acceptLanguage,
            List<String> routeMiddleware,
            String initialLocale) {
    }

    /** 需要写回浏览器的 cookie（{@code null} 表示不写）。 */
    public record Cookie(
            String name,
            String value,
            int maxAgeSeconds,
            String path,
            boolean httpOnly,
            boolean secure,
            String sameSite,
            String domain) {
    }

    /** 解析结果：最终 locale 与可选 cookie。 */
    public record Result(String locale, Cookie cookie) {
    }

    /** 便捷入口：不带路由（{@code route() === null}）时的解析。 */
    public static Result resolve(Input input) {
        String locale = input.lang() != null
                ? input.lang()
                : input.cookieLocale() != null
                        ? input.cookieLocale()
                        : input.acceptLanguage() == null ? null
                                : AcceptLanguage.getPreferredLanguage(input.acceptLanguage());

        // 别名归一：$info = Arr::get(config('locales'), $locale) && $alias = Arr::get($info, 'alias')
        Object info = Locales.get(locale);
        if (PhpArr.truthy(info)) {
            Object alias = PhpArr.get(info, "alias");
            if (PhpArr.truthy(alias)) {
                locale = String.valueOf(alias);
            }
        }

        // 空语句 "$locale ?? app()->getLocale();" —— 故意不做任何事，见类注释。
        if (!Locales.has(locale)) {
            locale = Locales.FALLBACK_LOCALE;
        }

        Option.setCurrentLocale(locale);

        boolean apiRoute = input.routeMiddleware() != null
                && input.routeMiddleware().contains("api");
        return new Result(locale, apiRoute ? null : cookie(locale));
    }

    /**
     * 构造要排队的 cookie。
     *
     * <p>对应 {@code CookieJar::make('locale', $locale, 120)}：{@code $minutes == 0} 才写
     * {@code 0}，否则 {@code availableAt(120 * 60)} 秒；path 默认 {@code "/"}、secure 默认
     * {@code false}、sameSite 默认 {@code "lax"}、domain 默认 {@code null}。
     */
    public static Cookie cookie(String locale) {
        return new Cookie(COOKIE_NAME, locale, COOKIE_LIFETIME_MINUTES * 60, "/", true, false, "lax", null);
    }

    /** Vert.x 适配层用：解析并把结果应用到 {@link Option} 的线程局部 locale。 */
    public static Result handle(Input input) {
        return resolve(input);
    }
}
