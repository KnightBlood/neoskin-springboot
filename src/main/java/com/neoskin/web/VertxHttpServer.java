package com.neoskin.web;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * Vert.x HTTP 服务器的生命周期壳。
 *
 * <p>Spring（{@code WebApplicationType.NONE}）只做装配；本类持有
 * {@link Vertx} 实例，在启动时建 router（{@link VertxRouteRegistry}）并
 * {@code listen} 到 {@code server.http.port}。退出时 {@code shutdown()} 收尸。</p>
 *
 * <p>middleware 链（EncryptCookies、DetectLanguagePrefer …）在
 * registry 装配时按 Laravel 的顺序挂上去，本类只负责把「全局栈」与
 * 「异常出口」两样东西传进去。</p>
 */
public final class VertxHttpServer {

    private final Routes routes;

    private final HandlerResolver resolver;

    private final int port;

    /** {@code public_path()}；{@code null} 时不挂静态 handler（测试/无前端资产时）。 */
    private java.nio.file.Path staticRoot;

    /** 已移植的中间件（FQCN/别名 → handler）；{@code null} 表示不挂路由级中间件。 */
    private java.util.Map<String, Handler<RoutingContext>> middlewareRegistry;

    /** 全局栈（DetectLanguagePrefer 等）；空表表示不挂。 */
    private List<Handler<RoutingContext>> globalMiddleware = List.of();

    /** 异常出口（{@code ErrorHandler}）；{@code null} 时退到纯 JSON。 */
    private Handler<RoutingContext> failureHandler;

    /** SPA 壳与 /api/bootstrap；{@code null} 时不挂。 */
    private Handler<RoutingContext> spaHandler;

    private Vertx vertx;

    public VertxHttpServer(Routes routes, HandlerResolver resolver, int port) {
        this.routes = routes;
        this.resolver = resolver;
        this.port = port;
    }

    /** 指定静态资源根（{@code public_path()}），用于提供 {@code /app/*} 与 {@code /lang/*}。 */
    public VertxHttpServer withStaticRoot(java.nio.file.Path publicDir) {
        this.staticRoot = publicDir;
        return this;
    }

    /**
     * 指定已移植的路由级中间件（由 {@code MiddlewareChain.connectValueLayer} 装配）。
     * 不传时不挂任何中间件 —— 仅测试用。
     */
    public VertxHttpServer withMiddleware(
            java.util.Map<String, Handler<RoutingContext>> registry) {
        this.middlewareRegistry = registry;
        return this;
    }

    /** 指定全局栈（按 Laravel {@code $middleware->use()} 的顺序）。 */
    public VertxHttpServer withGlobalMiddleware(List<Handler<RoutingContext>> handlers) {
        this.globalMiddleware = handlers == null ? List.of() : handlers;
        return this;
    }

    /** 指定异常出口（{@code ErrorHandler}），把所有 4xx/5xx 渲染成源的错误页。 */
    public VertxHttpServer withFailureHandler(Handler<RoutingContext> handler) {
        this.failureHandler = handler;
        return this;
    }

    /** 指定 SPA 壳与 /api/bootstrap 的 handler。 */
    public VertxHttpServer withSpa(Handler<RoutingContext> handler) {
        this.spaHandler = handler;
        return this;
    }

    /**
     * 启动监听；返回的 future 在端口就绪时完成（装驱动用的联动入口）。
     */
    public CompletableFuture<Void> startup() {
        vertx = Vertx.vertx();
        VertxRouteRegistry.BuildResult built = VertxRouteRegistry.build(
                routes, resolver, vertx, globalMiddleware, middlewareRegistry, staticRoot,
                failureHandler, spaHandler);
        Router router = built.router();

        CompletableFuture<Void> ready = new CompletableFuture<>();

        // 防监听失败后进程挂着不退
        vertx.createHttpServer()
                .requestHandler(router)
                .listen(port)
                .<Void>mapEmpty()
                .onSuccess(ready::complete)
                .onFailure(e -> {
                    ready.completeExceptionally(e);
                    vertx.close();
                });

        return ready;
    }

    /** 优雅停机（测试用，进程退出时不强求）。 */
    public void shutdown() {
        if (vertx != null) {
            try {
                vertx.close().toCompletionStage().toCompletableFuture().get(3, SECONDS);
            } catch (Exception e) {
                // 停机失败不喊叫
            }

            vertx = null;
        }
    }
}
