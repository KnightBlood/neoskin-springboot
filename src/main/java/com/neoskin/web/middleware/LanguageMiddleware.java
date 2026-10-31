package com.neoskin.web.middleware;

import java.util.List;

import io.vertx.core.Handler;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.ext.web.RoutingContext;

/**
 * {@link DetectLanguagePrefer} 的 Vert.x 接线（全局栈最后一个）。
 *
 * <p>请求侧：{@code lang} 输入 → {@code locale} cookie → {@code Accept-Language}
 * 三级回退，经 {@link DetectLanguagePrefer#resolve} 归一后写进
 * {@code Option} 的 ThreadLocal —— 之后所有 {@code trans()} 与
 * {@code option_localized()} 都读这个值。</p>
 *
 * <p>响应侧：{@code headersEndHandler} 里排 {@code locale} cookie
 * （{@code Max-Age=7200}、Path=/、HttpOnly、SameSite=lax）。</p>
 *
 * <h2>⚠ 这个 cookie 是明文，且读写的都是明文</h2>
 * <p>两个方向都要和源一致，原因是同一个：{@link EncryptCookies} 属于 <b>web 组</b>，
 * 而本中间件是<b>全局栈</b>的一员 —— 全局栈包在 web 组<b>外面</b>。</p>
 * <ul>
 *   <li>请求侧：源 {@code $request->cookie('locale')} 在本中间件里读，此时
 *       {@code EncryptCookies::decrypt()} 还没跑，拿到的是<b>原始值</b>；</li>
 *   <li>响应侧：源的 {@code $response->cookie(...)} 发生在
 *       {@code $next($request)} 返回<b>之后</b>，而
 *       {@code EncryptCookies::encrypt()} 早在返回前就跑完了，所以这个 cookie
 *       <b>不会被加密</b>。</li>
 * </ul>
 * <p>PHP 侧实测：{@code locale=zh_TW; expires=...; Max-Age=7200; path=/; httponly; samesite=lax}
 * —— 明文、可读、无 {@code XSRF-TOKEN} 那种 base64 外壳。若这里「顺手」加密，
 * 浏览器第二个请求就会因为解不开而回落到 {@code Accept-Language}，语言切换失效。</p>
 *
 * <h2>为什么 api 判定要延后</h2>
 * <p>源里那句是
 * {@code if (!in_array('api', optional($request->route())->middleware() ?? []))}，
 * 位置在 {@code $next($request)} <b>返回之后</b> —— 此刻路由已绑定，
 * {@code $request->route()} 才有值。全局中间件执行期间它是 {@code null}
 * （{@code optional(null)} → {@code null} → {@code ?? []}）。所以本类也在
 * {@code headersEndHandler} 里才读 {@link #ROUTE_MIDDLEWARE_KEY}。</p>
 */
public final class LanguageMiddleware implements Handler<RoutingContext> {

    /** 路由自己的中间件清单在 {@code ctx} 里的键（组名原样，如 {@code web} / {@code api}）。 */
    public static final String ROUTE_MIDDLEWARE_KEY = "neoskin.route.middleware";

    @Override
    public void handle(RoutingContext ctx) {
        // $request->input('lang')：body 优先、回落 query（两者都已归一化）
        String lang = RequestInput.inputString(ctx, "lang");

        // $request->cookie('locale')：此刻还没解密，读到的就是原始明文
        Cookie raw = ctx.request().getCookie(DetectLanguagePrefer.COOKIE_NAME);

        // routeMiddleware 传 null：源在这一步也拿不到路由，cookie 恒会排队，
        // 是否真的写由响应侧再判。
        DetectLanguagePrefer.Result result = DetectLanguagePrefer.resolve(
                new DetectLanguagePrefer.Input(lang, raw == null ? null : raw.getValue(),
                        ctx.request().getHeader("Accept-Language"), null, null));

        DetectLanguagePrefer.Cookie cookie = result.cookie();

        if (cookie != null) {
            ctx.addHeadersEndHandler(ignored -> {
                if (!isApiRoute(ctx)) {
                    queueLocaleCookie(ctx, cookie);
                }
            });
        }

        ctx.next();
    }

    /** 源 {@code in_array('api', optional($request->route())->middleware() ?? [])}。 */
    @SuppressWarnings("unchecked")
    static boolean isApiRoute(RoutingContext ctx) {
        Object stored = ctx.get(ROUTE_MIDDLEWARE_KEY);

        return stored instanceof List<?> list && ((List<String>) list).contains("api");
    }

    /** 明文排 cookie（不加解密层，见类注释）。 */
    private static void queueLocaleCookie(RoutingContext ctx, DetectLanguagePrefer.Cookie cookie) {
        Cookie vertxCookie = Cookie.cookie(cookie.name(), cookie.value());

        vertxCookie.setPath(cookie.path())
                .setHttpOnly(cookie.httpOnly())
                .setSecure(cookie.secure())
                .setMaxAge((long) cookie.maxAgeSeconds());

        if (cookie.sameSite() != null) {
            vertxCookie.setSameSite(CookieSameSite.LAX);
        }

        ctx.response().addCookie(vertxCookie);
    }
}
