package com.neoskin.web.middleware;

import com.neoskin.service.session.SessionStore;
import com.neoskin.web.auth.SessionGuard;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Middleware\RedirectIfAuthenticated} 的移植（{@code guest} 别名）。
 *
 * <pre>
 * return Auth::check() ? redirect('user') : $next($request);
 * </pre>
 *
 * <p>注意 {@code redirect('user')} 是<b>相对</b>路径，Laravel 会补成
 * {@code http://<host>/user}。</p>
 */
public final class RedirectIfAuthenticated implements Handler<RoutingContext> {

    @Override
    public void handle(RoutingContext ctx) {
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        SessionGuard guard = new SessionGuard(session, null);

        if (guard.check()) {
            String host = ctx.request().getHeader("Host");
            String location = "http://"
                    + (host == null || host.isEmpty() ? "localhost" : host) + "/user";
            ctx.response()
                    .setStatusCode(302)
                    .putHeader("Location", location)
                    .putHeader("Cache-Control",
                            com.neoskin.web.error.ErrorHandler.DEFAULT_CACHE_CONTROL)
                    .end();
            return;
        }

        ctx.next();
    }
}
