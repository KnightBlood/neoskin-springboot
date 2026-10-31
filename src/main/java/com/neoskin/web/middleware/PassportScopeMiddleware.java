package com.neoskin.web.middleware;

import java.util.ArrayList;
import java.util.List;

import com.neoskin.oauth.OAuthService;

import io.vertx.ext.web.RoutingContext;

/**
 * Passport 的 {@code CheckTokenForAnyScope} / {@code CheckToken} 中间件。
 *
 * <p>参数为逗号分隔的 scope 列表。认证失败（无 token）→ 401；scope 不足 →
 * 403 {@code Invalid scope(s) provided.}（Laravel AuthorizationException 的 JSON 形状）。</p>
 */
public final class PassportScopeMiddleware
        implements ParameterizedHandler, io.vertx.core.Handler<RoutingContext> {

    private final boolean anyScope;

    public PassportScopeMiddleware(boolean anyScope) {
        this.anyScope = anyScope;
    }

    /** 裸调用（缺参数）按 500 处理，与 CheckRole 一致。 */
    @Override
    public void handle(RoutingContext ctx) {
        ctx.fail(new IllegalStateException(
                "Too few arguments to function CheckToken::handle(): 3 passed, 2 expected"));
    }

    @Override
    public void handle(RoutingContext ctx, String params) {
        Object raw = ctx.get(Authenticate.OAUTH_RESULT_KEY);
        if (!(raw instanceof OAuthService.AuthResult result)) {
            ctx.fail(401);
            return;
        }
        List<String> required = new ArrayList<>();
        if (params != null) {
            for (String part : params.split(",")) {
                String scope = part.trim();
                if (!scope.isEmpty()) {
                    required.add(scope);
                }
            }
        }

        boolean allowed = anyScope
                ? required.stream().anyMatch(result::can)
                : required.stream().allMatch(result::can);
        if (!allowed) {
            ctx.fail(com.neoskin.web.error.WebException.http(403,
                    "Invalid scope(s) provided."));
            return;
        }

        ctx.next();
    }
}
