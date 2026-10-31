package com.neoskin.web.middleware;

import java.sql.SQLException;

import com.neoskin.db.UserRepository;
import com.neoskin.service.session.SessionStore;
import com.neoskin.web.auth.SessionGuard;
import com.neoskin.web.error.ErrorHandler;
import com.neoskin.web.i18n.Translator;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Middleware\Authenticate} 的移植（{@code auth:web} 别名）。
 *
 * <pre>
 * protected function redirectTo($request)
 * {
 *     if (!$request->expectsJson()) {
 *         session([
 *             'last_requested_path' =&gt; $request-&gt;fullUrl(),
 *             'msg' =&gt; trans('auth.check.anonymous'),
 *         ]);
 *
 *         return route('auth.login');
 *     }
 * }
 * </pre>
 *
 * <p>{@code Illuminate\Auth\Middleware\Authenticate} 未登录时：JSON 请求 → 401
 * （Laravel 13 的 {@code unauthenticated()} 抛
 * {@code AuthenticationException}，不带自定义响应时由处理器返回 401）；
 * 非 JSON → {@code redirect()->guest($redirectTo)}，即 302 到登录页并写入上面两个
 * 会话键。</p>
 *
 * <p>{@code last_requested_path} 是 {@code handleLogin} 成功后的跳转目标
 * （{@code session.pull('last_requested_path') ?? url('/user')}）。</p>
 */
public final class Authenticate implements Handler<RoutingContext>, ParameterizedHandler {

    private final UserRepository users;

    private final Translator translator;

    public Authenticate(UserRepository users, Translator translator) {
        this.users = users;
        this.translator = translator;
    }

    /** 带 guard 参数：{@code auth:web} 走会话，{@code auth:oauth} 走 Passport。 */
    @Override
    public void handle(RoutingContext ctx, String params) {
        if ("oauth".equalsIgnoreCase(params)) {
            com.neoskin.oauth.OAuthService service = com.neoskin.oauth.OAuthRuntime.service();
            com.neoskin.oauth.OAuthService.AuthResult result =
                    service == null ? null : service.authenticate(ctx);
            if (result == null || result.user() == null) {
                deny(ctx, ctx.get(StartSession.CTX_KEY));
                return;
            }
            ctx.put(OAUTH_RESULT_KEY, result);
            ctx.put(CURRENT_USER_KEY, result.user());
            ctx.next();
            return;
        }

        handle(ctx);
    }

    /** OAuth 认证结果（供 {@code scope:*} 中间件读取）。 */
    public static final String OAUTH_RESULT_KEY = "neoskin.oauth_auth";

    @Override
    public void handle(RoutingContext ctx) {
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        if (session == null) {
            // api 组（无 StartSession）且 Passport guard 尚未接入 → 视为未认证
            deny(ctx, null);
            return;
        }
        SessionGuard guard = new SessionGuard(session, users);

        Integer uid = guard.id();

        if (uid != null) {
            // 登录态写进 ctx，供后续中间件（RejectBannedUser / EnsureEmailFilled）复用，
            // 避免重复查库。
            try {
                ctx.put(CURRENT_USER_KEY, guard.user());
            } catch (SQLException ignored) {
                // 查不到用户等价于未登录
                ctx.put(CURRENT_USER_KEY, null);
                deny(ctx, session);
                return;
            }
            Object user = ctx.get(CURRENT_USER_KEY);

            if (user != null) {
                ctx.next();
                return;
            }
        }

        deny(ctx, session);
    }

    private void deny(RoutingContext ctx, SessionStore session) {
        if (ErrorHandler.expectsJson(ctx)) {
            ctx.fail(com.neoskin.web.error.WebException.http(401, "Unauthenticated."));
            return;
        }

        if (session != null) {
            session.put(AuthControllerPaths.LAST_REQUESTED_PATH, fullUrl(ctx));
            session.put("msg", translator.trans("auth.check.anonymous",
                    java.util.Map.of(), null));
        }

        String host = ctx.request().getHeader("Host");
        String location = "http://" + (host == null || host.isEmpty() ? "localhost" : host)
                + "/auth/login";
        ctx.response()
                .setStatusCode(302)
                .putHeader("Location", location)
                .putHeader("Cache-Control", com.neoskin.web.error.ErrorHandler.DEFAULT_CACHE_CONTROL)
                .end();
    }

    /** {@code $request->fullUrl()}。 */
    private static String fullUrl(RoutingContext ctx) {
        String uri = ctx.request().uri();
        String host = ctx.request().getHeader("Host");
        String scheme = ctx.request().scheme() == null ? "http" : ctx.request().scheme();

        return scheme + "://" + (host == null || host.isEmpty() ? "localhost" : host)
                + (uri == null || uri.isEmpty() ? "/" : uri);
    }

    /** {@code ctx} 里当前登录用户的键（供 RejectBannedUser / EnsureEmailFilled 用）。 */
    public static final String CURRENT_USER_KEY = "neoskin.current_user";

    /** {@code last_requested_path} 等会话键的持有者（避免控制器与中间件各写一份）。 */
    static final class AuthControllerPaths {

        /** {@code last_requested_path}。 */
        static final String LAST_REQUESTED_PATH = "last_requested_path";

        private AuthControllerPaths() {
        }
    }

    /** 便捷读取：当前登录用户（未登录为 {@code null}）。 */
    public static UserRepository.UserRow currentUser(RoutingContext ctx) {
        Object user = ctx.get(CURRENT_USER_KEY);

        return user instanceof UserRepository.UserRow row ? row : null;
    }
}
