package com.neoskin.web.middleware;

import com.neoskin.db.UserRepository;
import com.neoskin.service.Option;
import com.neoskin.web.error.WebException;
import com.neoskin.web.i18n.Translator;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Middleware\CheckUserVerified} 的移植（{@code verified} 别名）。
 *
 * <pre>
 * abort_if(option('require_verification') &amp;&amp; !auth()->user()->verified,
 *          403, trans('auth.check.verified'));
 * </pre>
 *
 * <p>只在站点开启「必须验证邮箱」时生效；未登录时放行（由 {@code Authenticate} 负责拦截）。
 * 必须排在 {@code Authenticate} 之后。</p>
 */
public final class CheckUserVerified implements Handler<RoutingContext> {

    private final Option option;
    private final Translator translator;

    public CheckUserVerified(Option option, Translator translator) {
        this.option = option;
        this.translator = translator;
    }

    @Override
    public void handle(RoutingContext ctx) {
        if (!option.getBoolean("require_verification", false)) {
            ctx.next();
            return;
        }

        UserRepository.UserRow user = Authenticate.currentUser(ctx);

        if (user == null || user.verified()) {
            ctx.next();
            return;
        }

        String message = translator.trans("auth.check.verified", java.util.Map.of(), null);
        ctx.fail(WebException.http(403, message));
    }
}
