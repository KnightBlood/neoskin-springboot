package com.neoskin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

/**
 * jar 内置前端产物的回退：磁盘上没有 {@code public/} 时，{@code /app/*} 从
 * {@code classpath:/static/app} 提供（{@code mvn package} 自动复制源
 * {@code public/app/}）。未命中的路径 fallthrough 给动态路由。
 */
class StaticAssetsClasspathTest {

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void servesBundledAppFromClasspath() throws Exception {
        Router router = Router.router(vertx);
        router.route().handler(StaticAssets.classpathHandler());
        router.route().handler(ctx -> ctx.response().setStatusCode(200)
                .end("dynamic:" + ctx.request().path()));
        int port = listen(router);

        HttpResponse<String> app = get(port, "/app/app.7278b90.js");
        assertEquals(200, app.statusCode());
        assertTrue(app.body().length() > 10, "classpath 前端产物应有内容");
        assertTrue(app.headers().firstValue("Content-Type").orElse("").contains("javascript"),
                app.headers().firstValue("Content-Type").orElse(""));

        HttpResponse<String> missing = get(port, "/app/definitely-missing.js");
        assertEquals(200, missing.statusCode());
        assertEquals("dynamic:/app/definitely-missing.js", missing.body(),
                "静态未命中应 fallthrough");
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int listen(Router router) throws Exception {
        CompletableFuture<Integer> port = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(router).listen(0)
                .onSuccess(s -> port.complete(s.actualPort()))
                .onFailure(port::completeExceptionally);

        return port.get(5, TimeUnit.SECONDS);
    }
}
