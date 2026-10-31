package com.neoskin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.neoskin.event.EventDispatcher;
import com.neoskin.event.EventRuntime;
import com.neoskin.service.Hook;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code ConfigureRoutes} 事件：插件通过 {@code Hook.addRoute} 注册的路由
 * 必须真的进入 Vert.x Router（源 {@code RouteServiceProvider} 的等价物）。
 */
class RouteHookTest {

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void reset() {
        Hook.reset();
        EventRuntime.set(null);
    }

    @Test
    void pluginRouteIsServed() throws Exception {
        EventRuntime.set(new EventDispatcher());
        Hook.addRoute(router -> ((io.vertx.ext.web.Router) router)
                .get("/plugin/hello")
                .handler(ctx -> ctx.response().end("hello")));

        VertxRouteRegistry.BuildResult built = VertxRouteRegistry.build(
                Routes.load(), action -> (Handler<RoutingContext>) null, vertx);

        int port = present(built.router());
        try {
            var response = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create("http://127.0.0.1:" + port + "/plugin/hello"))
                            .GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("hello", response.body());
        } finally {
            vertx.close();
        }
    }

    private int present(io.vertx.ext.web.Router router) throws Exception {
        CompletableFuture<Integer> port = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(router)
                .listen(0)
                .onSuccess(s -> port.complete(s.actualPort()))
                .onFailure(port::completeExceptionally);

        return port.get(5, TimeUnit.SECONDS);
    }
}
