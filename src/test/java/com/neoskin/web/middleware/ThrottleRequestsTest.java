package com.neoskin.web.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

/**
 * {@link ThrottleRequests} 的端到端测试（固定窗口 + 429 + Retry-After）。
 */
class ThrottleRequestsTest {

    private Vertx vertx;
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        Router router = Router.router(vertx);
        ThrottleRequests throttle = new ThrottleRequests();
        router.route("/t").handler(ctx -> throttle.handle(ctx, "2,1"));
        router.route("/t").handler(ctx -> ctx.response().setStatusCode(200).end("ok"));
        port = listen(router);
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void blocksAfterLimitAndReportsRetryAfter() throws Exception {
        HttpResponse<String> first = get();
        assertEquals(200, first.statusCode());
        assertEquals("2", first.headers().firstValue("X-RateLimit-Limit").orElse(""));
        assertEquals("1", first.headers().firstValue("X-RateLimit-Remaining").orElse(""));

        HttpResponse<String> second = get();
        assertEquals(200, second.statusCode());
        assertEquals("0", second.headers().firstValue("X-RateLimit-Remaining").orElse(""));

        HttpResponse<String> third = get();
        assertEquals(429, third.statusCode(), third.body());
        assertTrue(third.body().contains("Too Many Requests"));
        assertTrue(third.headers().firstValue("Retry-After").isPresent());
        assertEquals("no-cache, private",
                third.headers().firstValue("Cache-Control").orElse(""));
    }

    /** file cache 持久化：新实例（模拟另一个进程）继续计数。 */
    @Test
    void sharedFileCacheSurvivesNewInstance() throws Exception {
        java.nio.file.Path cacheDir =
                java.nio.file.Files.createTempDirectory("neoskin-throttle-cache-");
        try {
            com.neoskin.service.cache.FileCache cache =
                    new com.neoskin.service.cache.FileCache(cacheDir);
            int firstPort = listenWith(new ThrottleRequests(cache));

            assertEquals(200, get(firstPort).statusCode());
            assertEquals(200, get(firstPort).statusCode());

            // 新实例、同一个缓存目录 → 第 3 次已经超限
            int secondPort = listenWith(new ThrottleRequests(
                    new com.neoskin.service.cache.FileCache(cacheDir)));
            assertEquals(429, get(secondPort).statusCode());
        } finally {
            try (var files = java.nio.file.Files.walk(cacheDir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (java.io.IOException ignored) {
                        // 忽略
                    }
                });
            }
        }
    }

    private int listenWith(ThrottleRequests throttle) {
        Router router = Router.router(vertx);
        router.route("/t").handler(ctx -> throttle.handle(ctx, "2,1"));
        router.route("/t").handler(ctx -> ctx.response().setStatusCode(200).end("ok"));

        return listen(router);
    }

    // ============================================================ 工具

    private HttpResponse<String> get() throws Exception {
        return get(port);
    }

    private HttpResponse<String> get(int targetPort) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + targetPort + "/t"))
                .GET()
                .build();

        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private int listen(Router router) {
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
