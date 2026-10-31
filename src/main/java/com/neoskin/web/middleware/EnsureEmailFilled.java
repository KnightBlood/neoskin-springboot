package com.neoskin.web.middleware;

import com.neoskin.db.UserRepository;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Middleware\EnsureEmailFilled} 的移植（authorize 组第 3 个）。
 *
 * <pre>
 * if ($request->user()->email != '' &amp;&amp; $request->is('auth/bind')) {
 *     return redirect('/user');
 * } elseif ($request->user()->email == '' &amp;&amp; !$request->is('auth/bind')) {
 *     return redirect('/auth/bind');
 * }
 *
 * return $next($request);
 * </pre>
 *
 * <p>{@code $request->is('auth/bind')} 是 Laravel 的路径模式匹配（支持
 * {@code *} 通配），这里只用到精确路径 {@code auth/bind}，所以按「剥掉前后斜杠后
 * 相等」判定即可。</p>
 */
public final class EnsureEmailFilled implements Handler<RoutingContext> {

    @Override
    public void handle(RoutingContext ctx) {
        UserRepository.UserRow user = Authenticate.currentUser(ctx);

        if (user == null) {
            ctx.next();
            return;
        }

        boolean filled = user.email() != null && !user.email().isEmpty();
        boolean isBind = isBindPath(ctx.request().path());

        if (filled && isBind) {
            redirect(ctx, "/user");
            return;
        }

        if (!filled && !isBind) {
            redirect(ctx, "/auth/bind");
            return;
        }

        ctx.next();
    }

    /** {@code $request->is('auth/bind')}。 */
    static boolean isBindPath(String path) {
        if (path == null) {
            return false;
        }
        String p = path.replaceAll("/+$", "").replaceFirst("^/+", "");

        return "auth/bind".equals(p);
    }

    private static void redirect(RoutingContext ctx, String path) {
        String host = ctx.request().getHeader("Host");
        String location = "http://" + (host == null || host.isEmpty() ? "localhost" : host) + path;

        ctx.response()
                .setStatusCode(302)
                .putHeader("Location", location)
                .putHeader("Cache-Control",
                        com.neoskin.web.error.ErrorHandler.DEFAULT_CACHE_CONTROL)
                .end();
    }
}
