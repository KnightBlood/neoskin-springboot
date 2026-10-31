package com.neoskin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.vertx.core.Vertx;

/**
 * {@link StaticAssets} 的接线测试。
 *
 * <p>锁四条：</p>
 * <ol>
 *   <li>{@code /app/*.js} 能取到真实文件（前端预编译产物原样复用）；</li>
 *   <li>{@code /lang/*.js} 能取到（由 {@code JavaScript} 服务生成）；</li>
 *   <li>未命中的静态路径 fallthrough 到动态路由（不是 404 短路）；</li>
 *   <li>点文件与目录列表不可访问。</li>
 * </ol>
 */
class StaticAssetsTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        Path pub = dir.resolve("public");
        Files.createDirectories(pub.resolve("app"));
        Files.createDirectories(pub.resolve("lang"));
        Files.writeString(pub.resolve("app").resolve("app.7278b90.js"), "console.log('app');");
        Files.writeString(pub.resolve("app").resolve("style.1dd8a8a.css"), "body{color:#000}");
        Files.writeString(pub.resolve("lang").resolve("zh_CN.js"), "blessing.i18n = {};");
        Files.writeString(pub.resolve(".env"), "APP_KEY=secret");

        Routes routes = Routes.load();
        HandlerResolver resolver = action -> ctx ->
                ctx.response().setStatusCode(200).end("dynamic:" + ctx.request().path());

        vertx = Vertx.vertx();
        VertxRouteRegistry.BuildResult built = VertxRouteRegistry.build(
                routes, resolver, vertx, List.of(), null, pub);
        port = listen(built.router());
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void servesPrebuiltAppBundle() throws Exception {
        HttpResponse<String> res = get("/app/app.7278b90.js");
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("console.log('app')"), res.body());
    }

    @Test
    void servesGeneratedLanguagePack() throws Exception {
        HttpResponse<String> res = get("/lang/zh_CN.js");
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("blessing.i18n"), res.body());
    }

    @Test
    void servesStylesheet() throws Exception {
        HttpResponse<String> res = get("/app/style.1dd8a8a.css");
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("color:#000"));
    }

    /** 未命中的静态路径必须继续走动态路由（fallthrough），不能 404 短路。 */
    @Test
    void unknownStaticPathFallsThroughToRouter() throws Exception {
        HttpResponse<String> res = get("/app/does-not-exist.js");
        // 没命中任何 173 条路由 → 落到 404 兜底（说明确实穿过了静态 handler）
        assertEquals(404, res.statusCode(), res.body());
        assertTrue(res.body().contains("Not Found"), res.body());
    }

    /** 点文件不可访问。 */
    @Test
    void hiddenFilesAreNotServed() throws Exception {
        HttpResponse<String> res = get("/.env");
        assertFalse(res.body().contains("APP_KEY"), "不应泄露点文件内容: " + res.body());
    }

    // ================================================================ 工具

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private int listen(io.vertx.ext.web.Router router) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(router).listen(0)
                .onSuccess(s -> future.complete(s.actualPort()))
                .onFailure(future::completeExceptionally);
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
