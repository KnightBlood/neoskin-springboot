package com.neoskin.web.middleware;

import com.neoskin.ignition.Ignition;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code Spatie\LaravelIgnition\Http\Middleware\RunnableSolutionsEnabled}：
 * 未开启可运行解决方案时对 {@code /_ignition/*} 直接 404。
 */
public final class RunnableSolutionsEnabled implements Handler<RoutingContext> {

    @Override
    public void handle(RoutingContext ctx) {
        if (!Ignition.check()) {
            ctx.fail(404);
            return;
        }

        ctx.next();
    }
}
