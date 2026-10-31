package com.neoskin.web.middleware;

import com.neoskin.service.cipher.PhpEncrypter;
import com.neoskin.service.session.SessionStore;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@link PreventRequestForgery} 的 Vert.x 接线（web 组，在 StartSession 之后）。
 *
 * <p>请求侧：取会话 token 与三种来源的请求 token 做判定；不通过回 419
 * （Laravel 的 {@code TokenMismatchException} 状态码），通过则放行。</p>
 *
 * <p>响应侧：{@code headersEndHandler} 里补 {@code XSRF-TOKEN} cookie
 * （值 = 会话 token、<b>不加密</b>、{@code Max-Age=7200}、Path=/、SameSite=lax、
 * 非 HttpOnly —— 前端 js 要读它再放 {@code X-XSRF-TOKEN} 头）。</p>
 *
 * <p>与 StartSession 的响应钩子次序：StartSession 先注册，读到的就是它写入的
 * 会话数据；XSRF cookie 也在同一批 headers 里。</p>
 */
public final class VerifyCsrfToken implements Handler<RoutingContext> {

    /** X-XSRF-TOKEN 头名（值本身是加密过的会话 token + CookieValuePrefix）。 */
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    private final PhpEncrypter encrypter;

    /** XSRF cookie 的 Max-Age（分钟，跟随 SESSION_LIFETIME）。 */
    private final long lifetimeMinutes;

    public VerifyCsrfToken(PhpEncrypter encrypter) {
        this(encrypter, StartSession.LIFETIME_MINUTES);
    }

    /** @param lifetimeMinutes XSRF cookie 的 Max-Age（跟随 SESSION_LIFETIME） */
    public VerifyCsrfToken(PhpEncrypter encrypter, long lifetimeMinutes) {
        this.encrypter = encrypter;
        this.lifetimeMinutes = lifetimeMinutes <= 0
                ? StartSession.LIFETIME_MINUTES : lifetimeMinutes;
    }

    @Override
    public void handle(RoutingContext ctx) {
        SessionStore session = ctx.<SessionStore>get(StartSession.CTX_KEY);
        String sessionToken = session == null ? null
                : (session.token() == null ? null
                        : com.neoskin.support.PhpString.cast(session.token()));

        PreventRequestForgery.Request request = new PreventRequestForgery.Request(
                ctx.request().method().name(),
                ctx.normalizedPath(),
                ctx.request().getHeader("sec-fetch-site"),
                RequestInput.inputString(ctx, "_token"),
                ctx.request().getHeader(CSRF_HEADER),
                ctx.request().getHeader(XSRF_HEADER));

        PreventRequestForgery.Verdict verdict = PreventRequestForgery.verify(request,
                sessionToken, header -> {
                    try {
                        if (encrypter == null) {
                            return null;
                        }
                        // 只解密，不去前缀 —— 源里 CookieValuePrefix::remove 由
                        // getTokenFromRequest 统一做（去两次会把 token 削成空串）。
                        Object plain = encrypter.decrypt(header, false);

                        return plain == null ? null
                                : com.neoskin.support.PhpString.cast(plain);
                    } catch (PhpEncrypter.DecryptException e) {
                        return null;
                    }
                });

        if (!verdict.allowed()) {
            ctx.response().setStatusCode(419).end(
                    "{\"code\":419,\"message\":\"CSRF token mismatch.\"}");

            return;
        }

        ctx.addHeadersEndHandler(ignored -> {
            var cookie = PreventRequestForgery.xsrfCookie(sessionToken, true,
                    (int) lifetimeMinutes, "/", null, false, "lax");

            if (cookie != null && sessionToken != null) {
                var vertxCookie = io.vertx.core.http.Cookie.cookie(cookie.name(), cookie.value());

                vertxCookie.setPath(cookie.path())
                        .setHttpOnly(cookie.httpOnly())
                        .setSecure(cookie.secure())
                        .setSameSite(io.vertx.core.http.CookieSameSite.LAX)
                        .setMaxAge(cookie.maxAgeSeconds());

                ctx.response().addCookie(vertxCookie);
            }
        });

        ctx.next();
    }
}
