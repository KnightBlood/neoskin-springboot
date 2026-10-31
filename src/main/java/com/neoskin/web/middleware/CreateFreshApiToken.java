package com.neoskin.web.middleware;

import com.neoskin.oauth.OAuthRuntime;
import com.neoskin.oauth.OAuthService;
import com.neoskin.service.session.SessionStore;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code Laravel\Passport\Http\Middleware\CreateFreshApiToken} 的移植。
 *
 * <p>源：web 组每个 GET 响应（已登录、响应成功、且尚未带 {@code laravel_token}）
 * 都种一个 HS256 JWT cookie，前端 {@code fetch} 同源请求自动携带它，
 * {@code auth:oauth} 的 TokenGuard 即可从 cookie 认证（免 Bearer token）。</p>
 *
 * <p>JWT 内容：{@code sub}（uid）、{@code csrf}（会话 token）、{@code exp}
 * （session lifetime）。前端把会话 token 放在 {@code X-CSRF-TOKEN} 头里，
 * 与我们 {@link OAuthService#authenticate} 的 cookie 分支对齐。</p>
 */
public final class CreateFreshApiToken implements Handler<RoutingContext> {

    /** cookie 生命周期（分钟，跟随 SESSION_LIFETIME）。 */
    private final long lifetimeMinutes;

    /** 会话用户解析（无 auth 中间件的页面也要种 token）。 */
    private final com.neoskin.db.UserRepository users;

    public CreateFreshApiToken(com.neoskin.db.UserRepository users) {
        this(users, StartSession.LIFETIME_MINUTES);
    }

    public CreateFreshApiToken(com.neoskin.db.UserRepository users, long lifetimeMinutes) {
        this.users = users;
        this.lifetimeMinutes = lifetimeMinutes <= 0
                ? StartSession.LIFETIME_MINUTES : lifetimeMinutes;
    }

    @Override
    public void handle(RoutingContext ctx) {
        ctx.addHeadersEndHandler(ignored -> {
            if (!"GET".equals(ctx.request().method().name())) {
                return;
            }
            com.neoskin.db.UserRepository.UserRow user = Authenticate.currentUser(ctx);
            if (user == null) {
                SessionStore session = ctx.get(StartSession.CTX_KEY);
                if (session != null && users != null) {
                    try {
                        user = new com.neoskin.web.auth.SessionGuard(session, users).user();
                    } catch (java.sql.SQLException e) {
                        return;
                    }
                }
            }
            if (user == null || ctx.response().getStatusCode() >= 400) {
                return;
            }
            boolean already = ctx.response().headers().getAll("Set-Cookie").stream()
                    .anyMatch(header -> header.startsWith(OAuthService.COOKIE_NAME + "="));
            if (already) {
                return;
            }
            OAuthService service = OAuthRuntime.service();
            if (service == null) {
                return;
            }
            SessionStore session = ctx.get(StartSession.CTX_KEY);
            String csrf = session == null || session.token() == null ? ""
                    : String.valueOf(session.token());
            ctx.response().addCookie(service.transientCookie(user.uid(), csrf,
                    (int) lifetimeMinutes));
        });
        ctx.next();
    }
}
