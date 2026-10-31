package com.neoskin.web.middleware;

import com.neoskin.db.UserRepository;
import com.neoskin.support.PhpJson;
import com.neoskin.web.error.ErrorHandler;
import com.neoskin.web.i18n.Translator;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Middleware\RejectBannedUser} 的移植（authorize 组第 2 个）。
 *
 * <pre>
 * if ($request->route()->getName() === 'auth.logout') {
 *     return $next($request);
 * }
 *
 * if ($request->user()->permission == User::BANNED) {
 *     if ($request->expectsJson()) {
 *         $response = json(trans('auth.check.banned'), -1);
 *         $response->setStatusCode(403);
 *         return $response;
 *     } else {
 *         abort(403, trans('auth.check.banned'));
 *     }
 * }
 *
 * return $next($request);
 * </pre>
 *
 * <p>两个容易漏的点：{@code auth.logout} 显式放行（否则封禁用户永远登不出去）；
 * JSON 分支返回的 {@code code} 是 <b>-1</b> 而不是 1。</p>
 *
 * <p>本中间件必须在 {@link Authenticate} 之后执行（authorize 组的顺序），
 * 用户从 {@code ctx} 上取。拿不到用户时放行，由 {@code Authenticate} 负责拦截。</p>
 */
public final class RejectBannedUser implements Handler<RoutingContext> {

    private final Translator translator;

    public RejectBannedUser(Translator translator) {
        this.translator = translator;
    }

    @Override
    public void handle(RoutingContext ctx) {
        Object routeName = ctx.get(com.neoskin.web.middleware.StartSession.ROUTE_NAME_KEY);

        if ("auth.logout".equals(routeName)) {
            ctx.next();
            return;
        }

        UserRepository.UserRow user = Authenticate.currentUser(ctx);

        if (user == null || user.permission() != UserRepository.BANNED) {
            ctx.next();
            return;
        }

        String message = translator.trans("auth.check.banned", java.util.Map.of(), null);

        if (ErrorHandler.expectsJson(ctx)) {
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("code", -1);
            body.put("message", message);
            ctx.response()
                    .setStatusCode(403)
                    .putHeader("Content-Type", "application/json")
                    .end(Buffer.buffer(PhpJson.encode(body, 0), "UTF-8"));
            return;
        }

        ctx.fail(com.neoskin.web.error.WebException.http(403, message));
    }
}
