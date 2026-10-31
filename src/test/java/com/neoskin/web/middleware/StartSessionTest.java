package com.neoskin.web.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.Routes;
import com.neoskin.web.VertxRouteRegistry;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

/**
 * {@link StartSession} 的端到端接线测试（真 HTTP，随机端口）。
 *
 * <p>锁四件事：</p>
 * <ol>
 *   <li>首个请求无 cookie → 服务端新建会话并回写 {@code BS_SESSION}（HttpOnly、
 *       Path=/、SameSite=Lax、Max-Age=7200）；</li>
 *   <li>带 cookie 再来 → id 不变（会话复用），且上个请求写入的数据能读到；</li>
 *   <li>响应头里 {@code Set-Cookie} 的值是加密串（EncryptCookies 未进 except）；</li>
 *   <li>controller 从 {@code ctx.get("session")} 拿到的就是可用 Store。</li>
 * </ol>
 */
class StartSessionTest {

    @TempDir
    Path sessions;

    private Vertx vertx;
    private int port;
    private final Routes routes = Routes.load();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        EncryptCookies cookies = new EncryptCookies(
                new com.neoskin.service.cipher.PhpEncrypter(java.util.Base64.getDecoder().decode(testKey()),
                        "aes-256-cbc"));

        Map<String, Handler<RoutingContext>> registry = new HashMap<>();
        MiddlewareChain.connectValueLayer(registry, cookies, sessions);

        // 只桩掉首页：读 session、写一个键、回显 id。
        HandlerResolver resolver = action -> action.endsWith("HomeController@index")
                ? ctx -> {
                    SessionStore store = ctx.get(StartSession.CTX_KEY);
                    Object counter = store.get("n");
                    long next = counter == null ? 1 : ((Number) counter).longValue() + 1;
                    store.put("n", next);
                    ctx.response().putHeader("content-type", "text/plain")
                            .end(store.getId() + "|" + next);
                }
                : null;

        VertxRouteRegistry.BuildResult built = VertxRouteRegistry.build(
                routes, resolver, vertx, List.of(), registry);
        port = present(built.router());
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void firstRequestCreatesSessionCookieAndSecondReusesIt() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> first = client.send(request("/", null),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, first.statusCode());
        List<String> setCookie = first.headers().allValues("set-cookie");
        assertTrue(setCookie.stream().anyMatch(h -> h.startsWith("BS_SESSION=")), setCookie.toString());

        String header = setCookie.stream().filter(h -> h.startsWith("BS_SESSION=")).findFirst().orElseThrow();
        assertTrue(header.contains("Path=/"), header);
        assertTrue(header.toLowerCase().contains("httponly"), header);
        assertTrue(header.toLowerCase().contains("samesite=lax"), header);
        assertTrue(header.contains("Max-Age=7200"), header);

        String cookiePair = header.split(";", 2)[0];
        String id = first.body().split("\\|")[0];
        assertEquals(40, id.length());

        // cookie 值是加密串，不是裸 id
        assertTrue(!cookiePair.equals("BS_SESSION=" + id), cookiePair);

        // 会话文件已落盘
        assertTrue(Files.isRegularFile(sessions.resolve(id)));

        // 第二次带上 cookie：同一个会话，计数递增
        HttpResponse<String> second = client.send(request("/", cookiePair),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, second.statusCode());
        assertEquals(id, second.body().split("\\|")[0]);
        assertEquals("2", second.body().split("\\|")[1]);
    }

    @Test
    void tamperedCookieFallsBackToFreshSession() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                request("/", "BS_SESSION=not-a-valid-payload"), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        // 解不开 → cookie 置 null → 新会话 id（不等于伪造值）
        String id = response.body().split("\\|")[0];
        assertEquals(40, id.length());
        assertTrue(!id.equals("not-a-valid-payload"));
    }

    @Test
    void sessionPayloadIsSerializedPhpArray() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request("/", null),
                HttpResponse.BodyHandlers.ofString());
        String id = response.body().split("\\|")[0];
        String raw = Files.readString(sessions.resolve(id));

        assertTrue(raw.startsWith("a:"), raw);
        assertTrue(raw.contains("s:1:\"n\";i:1;"), raw);
        assertTrue(raw.contains("s:6:\"_token\";s:40:\""), raw);
    }

    // ------------------------------------------------------------------ 工具

    private HttpRequest request(String path, String cookie) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));

        if (cookie != null) {
            builder.header("cookie", cookie);
        }

        return builder.GET().build();
    }

    private int present(io.vertx.ext.web.Router router) throws Exception {
        var future = new java.util.concurrent.CompletableFuture<Integer>();

        vertx.createHttpServer().requestHandler(router).listen(0)
                .onSuccess(s -> future.complete(s.actualPort()))
                .onFailure(future::completeExceptionally);

        return future.get(5, TimeUnit.SECONDS);
    }

    /** 固定 32 字节 base64 key（测试用，不对应任何真实 .env）。 */
    private static String testKey() {
        byte[] key = new byte[32];

        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }

        return java.util.Base64.getEncoder().encodeToString(key);
    }
}
