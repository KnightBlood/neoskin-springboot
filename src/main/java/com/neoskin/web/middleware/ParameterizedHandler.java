package com.neoskin.web.middleware;

import io.vertx.ext.web.RoutingContext;

/**
 * 带参数的中间件（{@code role:admin}、{@code throttle:30,1}）。
 *
 * <p>别名展开把参数留在 token 冒号后面（{@code CheckRole:admin}），而
 * 注册表按类名登记。实现此接口的中间件在链装配时由
 * {@code MiddlewareChain.handlers} 包一层看不到的转发，把参数传进来。</p>
 */
public interface ParameterizedHandler {

    /**
     * 处理请求。
     *
     * @param ctx    路由上下文
     * @param params 冒号后的参数原串（如 {@code admin}、{@code 30,1}）；未带参数时为 null
     */
    void handle(RoutingContext ctx, String params);
}
