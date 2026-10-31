package com.neoskin.web.controllers;

import com.neoskin.web.HandlerResolver;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * Laravel 内置的 {@code Illuminate\Routing\RedirectController}。
 *
 * <p>本项目只有一条路由用到它：{@code Route::redirect('change-password',
 * '/user/profile')}（{@code /.well-known/change-password}，默认 302）。
 * 源用 {@code UrlGenerator::toRoute()} 生成绝对地址，因此 Location 带 host。</p>
 */
public final class RedirectController {

    private static final String DESTINATION = "/user/profile";

    private RedirectController() {
    }

    public static Handler<RoutingContext> changePassword() {
        return ctx -> {
            String host = ctx.request().getHeader("Host");
            if (host == null || host.isEmpty()) {
                // HTTP/2 用 :authority 传输主机名，Vert.x 不合成 Host 头。
                io.vertx.core.net.HostAndPort authority = ctx.request().authority();
                if (authority != null) {
                    host = authority.port() > 0
                            ? authority.host() + ":" + authority.port()
                            : authority.host();
                }
            }
            String location = "http://"
                    + (host == null || host.isEmpty() ? "localhost" : host) + DESTINATION;
            ctx.response()
                    .setStatusCode(302)
                    .putHeader("Location", location)
                    .end();
        };
    }

    public static HandlerResolver resolver() {
        return action -> "Illuminate\\Routing\\RedirectController".equals(action)
                ? changePassword()
                : null;
    }
}
