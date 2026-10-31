package com.neoskin.web.middleware;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.neoskin.web.error.WebException;
import com.neoskin.web.i18n.Translator;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Middleware\EnforceEverGreen} 的移植（web 组第 6 个，
 * 在 {@link RedirectToSetup} <b>之前</b>）。
 *
 * <pre>
 * $userAgent = $request-&gt;userAgent();
 *
 * preg_match('/Chrome\/(\d+)/', $userAgent, $matches);
 * $isOldChrome = Arr::has($matches, 1) &amp;&amp; $matches[1] &lt; 55;
 *
 * if ($isOldChrome || Str::contains($userAgent, ['Trident', 'MSIE'])) {
 *     throw new PrettyPageException(trans('errors.http.ie'));
 * }
 * </pre>
 *
 * <h2>为什么状态码是 200 而不是 4xx</h2>
 * <p>{@code PrettyPageException} 有 {@code render()}，Laravel 的
 * {@code Handler::render()} 会<b>先</b>用它 —— 于是响应是
 * {@code response()->view('errors.pretty', ...)}，默认状态 200。异常里那个
 * {@code code}（这里没传，是 0）只出现在正文「错误代码: 0」。
 * PHP 实测：IE11 / MSIE9 / Chrome 52 全部 {@code 200 + Error occurred} 标题。
 * 见 {@link WebException#pretty} 与 PORT-NOTES §13.2。</p>
 *
 * <h2>三个容易错的匹配细节</h2>
 * <ol>
 *   <li>{@code preg_match} 只要有匹配就不返回 false（{@code Arr::has($matches, 1)}
 *       等价于「正则里有捕获组 1」，恒真），真正起作用的是 {@code $matches[1] &lt; 55}。
 *       所以 {@code Chrome/5} 会被拦、{@code Chrome/100} 不会；</li>
 *   <li>{@code Str::contains} 是<b>区分大小写</b>的子串匹配：{@code trident} 不拦；</li>
 *   <li>{@code userAgent()} 在没有 {@code User-Agent} 头时返回 {@code null}，
 *       {@code preg_match} 对 null 不报错（PHP 8.1+ 只是 deprecation），结果是不拦。</li>
 * </ol>
 */
public final class EnforceEverGreen implements Handler<RoutingContext> {

    /** {@code /Chrome\/(\d+)/}：只取主版本号，不看后面的 {@code .0.0.0}。 */
    private static final Pattern CHROME = Pattern.compile("Chrome/(\\d+)");

    /** 支持的最低 Chrome 主版本（{@code $matches[1] < 55} 即拦）。 */
    private static final int MIN_CHROME = 55;

    private final Translator translator;

    public EnforceEverGreen(Translator translator) {
        this.translator = translator;
    }

    @Override
    public void handle(RoutingContext ctx) {
        String userAgent = ctx.request().getHeader("User-Agent");

        if (isRejected(userAgent)) {
            ctx.fail(WebException.pretty(translator.trans("errors.http.ie", java.util.Map.of(), null), 0));
            return;
        }
        ctx.next();
    }

    /** 源里那个 {@code if} 的判定，抽出来便于单测。 */
    static boolean isRejected(String userAgent) {
        if (userAgent == null) {
            return false;
        }

        Matcher matcher = CHROME.matcher(userAgent);

        if (matcher.find() && Long.parseLong(matcher.group(1)) < MIN_CHROME) {
            return true;
        }
        return userAgent.contains("Trident") || userAgent.contains("MSIE");
    }
}
