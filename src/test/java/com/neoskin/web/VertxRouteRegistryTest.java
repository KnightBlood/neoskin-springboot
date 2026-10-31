package com.neoskin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * Vert.x bootstrap 测试：173 条路由必须全部注册、匹配成功并把未移植
 * 的 action 兜底成 501、无路由命中兜底 404；服务器壳可起可停。
 */
class VertxRouteRegistryTest {

    private final Vertx vertx = Vertx.vertx();

    private final Routes routes = Routes.load();

    /** 测试 resolver：所有 action 都未移植。 */
    private static final class EmptyResolver implements HandlerResolver {
        @Override
        public Handler<RoutingContext> resolve(String action) {
            return null;
        }
    }

    /** 语料红线：173 条路由（AGENTS.md §2.1 / §6）。 */
    @Test
    void loadsAll173Routes() {
        assertEquals(173, routes.count());
    }

    /** action 索引：每个 action 至少一条路由，HomeController@index 能搜到。 */
    @Test
    void indexesByAction() {
        routes.actions().forEach(action ->
                assertFalse(routes.byAction(action).isEmpty(), action));
        assertEquals(1, routes.byAction("App\\Http\\Controllers\\HomeController@index").size());
    }

    /** method token → Vert.x 方法的映射。 */
    @Test
    void mapsMethodTokens() {
        assertEquals(io.vertx.core.http.HttpMethod.GET, VertxRouteRegistry.httpMethod("get"));
        assertEquals(io.vertx.core.http.HttpMethod.HEAD, VertxRouteRegistry.httpMethod("head"));
        assertEquals(io.vertx.core.http.HttpMethod.PATCH, VertxRouteRegistry.httpMethod("patch"));
        assertEquals(io.vertx.core.http.HttpMethod.OPTIONS, VertxRouteRegistry.httpMethod("options"));
        // Laravel 的 RESOURCE query 方法按 GET 语义处理
        assertEquals(io.vertx.core.http.HttpMethod.GET, VertxRouteRegistry.httpMethod("query"));
    }

    /** 未知 method token 必须立刻失败，不允许静默丢路由。 */
    @Test
    void rejectsUnknownMethod() {
        assertThrows(IllegalArgumentException.class,
                () -> VertxRouteRegistry.httpMethod("fetch"));
    }

    /** 173 条全部注册（501 兜底）；未命中 404；真实监听口竖向冒烟。 */
    @Test
    void buildsRouterWithAllRoutesAndListens() throws Exception {
        VertxRouteRegistry.BuildResult built =
                VertxRouteRegistry.build(routes, new EmptyResolver(), vertx);

        assertEquals(173, built.registeredCount());

        int port = present(built.router());
        try {
            var client = java.net.http.HttpClient.newHttpClient();

            // routes.json 里根路由只有 GET|HEAD → 501（未移植）
            assertEquals(501, get(client, port, "GET", "/").code());

            // POST / 路径存在、方法不对 → 405（Laravel MethodNotAllowedHttpException）
            assertEquals(405, get(client, port, "POST", "/").code());

            // admin 分组（GET|HEAD）
            assertEquals(501, get(client, port, "GET", "/admin").code());

            // 无路由 → 404
            var miss = get(client, port, "GET", "/definitely/missing");
            assertEquals(404, miss.code());
            assertEquals("{\"code\":404,\"message\":\"Not Found\"}", miss.body());
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    /** 405 的 {@code Allow}：按 Laravel {@code Router::$verbs} 排序，逗号+空格。 */
    @Test
    void methodNotAllowedCarriesAllowHeader() throws Exception {
        VertxRouteRegistry.BuildResult built =
                VertxRouteRegistry.build(routes, new EmptyResolver(), vertx);
        int port = present(built.router());
        try {
            var client = java.net.http.HttpClient.newHttpClient();

            // 根路由只有 GET|HEAD
            var root = send(client, port, "PUT", "/");
            assertEquals(405, root.statusCode());
            assertEquals("GET, HEAD", root.headers().firstValue("Allow").orElse(""));

            // auth/login 是 GET|HEAD|POST
            var login = send(client, port, "DELETE", "/auth/login");
            assertEquals(405, login.statusCode());
            assertEquals("GET, HEAD, POST", login.headers().firstValue("Allow").orElse(""));
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    /** OPTIONS 命中已注册路径 → 200 空体 + {@code Allow}（逗号<b>不带</b>空格）。 */
    @Test
    void optionsReturns200WithCompactAllow() throws Exception {
        VertxRouteRegistry.BuildResult built =
                VertxRouteRegistry.build(routes, new EmptyResolver(), vertx);
        int port = present(built.router());
        try {
            var client = java.net.http.HttpClient.newHttpClient();

            var res = send(client, port, "OPTIONS", "/auth/login");
            assertEquals(200, res.statusCode());
            assertEquals("GET,HEAD,POST", res.headers().firstValue("Allow").orElse(""));
            assertEquals("", res.body());

            // OPTIONS 打不存在的路径 → 404，不是 200
            assertEquals(404, send(client, port, "OPTIONS", "/nope").statusCode());
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    /**
     * 双斜杠守卫：Vert.x 会折叠 {@code //}，源站不会。
     *
     * <p>源实测：{@code /auth//login} → 404，{@code //auth/login} → 404，
     * 而 {@code /auth/login//} → 200（尾斜杠容忍）。</p>
     */
    @Test
    void rejectsInnerDoubleSlashButToleratesTrailing() throws Exception {
        VertxRouteRegistry.BuildResult built =
                VertxRouteRegistry.build(routes, new EmptyResolver(), vertx);
        int port = present(built.router());
        try {
            var client = java.net.http.HttpClient.newHttpClient();

            assertEquals(404, get(client, port, "GET", "/auth//login").code());
            assertEquals(404, get(client, port, "GET", "//auth/login").code());
            assertEquals(501, get(client, port, "GET", "/auth/login/").code());
            assertEquals(501, get(client, port, "GET", "/auth/login//").code());
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    /** 路径归一：{@code Request::path()} 剥尾斜杠与前导斜杠，根是 {@code "/"}。 */
    @Test
    void laravelPathNormalization() {
        assertEquals("/", VertxRouteRegistry.laravelPathOf("/"));
        assertEquals("/", VertxRouteRegistry.laravelPathOf("///"));
        assertEquals("nope", VertxRouteRegistry.laravelPathOf("/nope"));
        assertEquals("nope", VertxRouteRegistry.laravelPathOf("/nope/"));
        assertEquals("nope", VertxRouteRegistry.laravelPathOf("/nope//"));
        // 中间双斜杠不折叠
        assertEquals("auth//login", VertxRouteRegistry.laravelPathOf("/auth//login"));
        // 前导斜杠被 trim（所以错误文案里看不到 //）
        assertEquals("auth/login", VertxRouteRegistry.laravelPathOf("//auth/login"));
    }

    /** 双斜杠判定只看「剥掉尾斜杠之后」。 */
    @Test
    void innerDoubleSlashDetection() {
        assertFalse(VertxRouteRegistry.hasInnerDoubleSlash("/a"));
        assertFalse(VertxRouteRegistry.hasInnerDoubleSlash("/a/"));
        assertFalse(VertxRouteRegistry.hasInnerDoubleSlash("/a///"));
        assertTrue(VertxRouteRegistry.hasInnerDoubleSlash("/a//b"));
        assertTrue(VertxRouteRegistry.hasInnerDoubleSlash("//a"));
    }

    /** 起真实监听口：端口可通、可优雅停机。 */
    @Test
    void serverListensAndShutdowns() throws Exception {
        VertxHttpServer server = new VertxHttpServer(routes, new EmptyResolver(), 0);

        server.startup().get(10, TimeUnit.SECONDS);
        server.shutdown();
    }

    // ------------------------------------------------------------------ 小工具

    /** router 挂到随机端口，返回端口号（同步等待就绪）。 */
    private int present(Router router) throws Exception {
        CompletableFuture<Integer> port = new CompletableFuture<>();

        vertx.createHttpServer().requestHandler(router)
                .listen(0)
                .onSuccess(s -> port.complete(s.actualPort()))
                .onFailure(port::completeExceptionally);

        return port.get(5, TimeUnit.SECONDS);
    }

    private record Result(int code, String body) {
    }

    /** 同步一发 HTTP 请求。 */
    private static Result get(java.net.http.HttpClient client, int port, String method, String path)
            throws Exception {
        var response = send(client, port, method, path);
        return new Result(response.statusCode(), response.body());
    }

    /** 同步一发 HTTP 请求（返回完整响应，可读头）。 */
    private static java.net.http.HttpResponse<String> send(java.net.http.HttpClient client, int port,
            String method, String path) throws Exception {
        return client.send(
                java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create("http://127.0.0.1:" + port + path))
                        .method(method, java.net.http.HttpRequest.BodyPublishers.noBody())
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    }
}
