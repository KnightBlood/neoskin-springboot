package com.neoskin.web.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.service.cipher.PhpEncrypter;
import com.neoskin.service.session.FileSessionHandler;
import com.neoskin.service.session.SessionStore;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.Routes;
import com.neoskin.web.VertxRouteRegistry;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

/**
 * CSRF 的端到端接线测试（{@link PreventRequestForgery} + {@link VerifyCsrfToken}）。
 *
 * <p>锁五条源语义：</p>
 * <ol>
 *   <li>读法（GET/HEAD/OPTIONS）一律放行，且响应带 {@code XSRF-TOKEN} cookie
 *       （值 = 会话 {@code _token}，<b>不加密</b>，非 HttpOnly）；</li>
 *   <li>POST 不带 token → 419；</li>
 *   <li>POST 带 {@code X-CSRF-TOKEN} 头 → 放行；</li>
 *   <li>POST 带 {@code X-XSRF-TOKEN}（EncryptCookies 格式的密文 + 前缀）→ 放行；</li>
 *   <li>{@code Sec-Fetch-Site: same-origin} 不看 token 直接放行
 *       （本应用没有开 {@code allowSameSite}，{@code same-site} 不放行）。</li>
 * </ol>
 */
class VerifyCsrfTokenTest {

    @TempDir
    Path sessions;

    private Vertx vertx;
    private int port;
    private final Routes routes = Routes.load();
    private PhpEncrypter encrypter;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        encrypter = new PhpEncrypter(java.util.Base64.getDecoder().decode(testKey()), "aes-256-cbc");

        EncryptCookies cookies = new EncryptCookies(encrypter);

        Map<String, Handler<RoutingContext>> registry = new HashMap<>();
        MiddlewareChain.connectValueLayer(registry, cookies, sessions);

        // 桩：只实现 POST /auth/verify/{user}（web 组，无 authorize）与首页。
        HandlerResolver resolver = action -> switch (action) {
            case "App\\Http\\Controllers\\AuthController@handleVerify" -> ctx -> {
                SessionStore store = ctx.<SessionStore>get(StartSession.CTX_KEY);
                ctx.response().putHeader("content-type", "text/plain")
                        .end("verified|" + store.token());
            };
            case "App\\Http\\Controllers\\HomeController@index" -> ctx -> {
                SessionStore store = ctx.<SessionStore>get(StartSession.CTX_KEY);
                ctx.response().putHeader("content-type", "text/plain")
                        .end("home|" + store.token());
            };
            default -> null;
        };

        VertxRouteRegistry.BuildResult built = VertxRouteRegistry.build(
                routes, resolver, vertx, List.of(), registry);
        port = present(built.router());
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // --------------------------------------------------------------- 读法放行

    @Test
    void getIsAllowedAndSetsPlainXsrfCookie() throws Exception {
        HttpResponse<String> response = send("GET", "/", null, Map.of());

        assertEquals(200, response.statusCode());

        String token = response.body().split("\\|")[1];
        assertEquals(40, token.length());

        String xsrf = response.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith("XSRF-TOKEN=")).findFirst().orElse(null);

        assertNotNull(xsrf, response.headers().allValues("set-cookie").toString());
        // 值就是会话 token 本身（不加密、不加 CookieValuePrefix）
        assertEquals("XSRF-TOKEN=" + token, xsrf.split(";", 2)[0]);
        assertTrue(xsrf.contains("Max-Age=7200"), xsrf);
        assertTrue(!xsrf.toLowerCase().contains("httponly"), xsrf);
    }

    // ------------------------------------------------------------- 写法的判定

    @Test
    void postWithoutTokenIsRejected() throws Exception {
        HttpResponse<String> response = send("POST", "/auth/verify/1", null, Map.of());

        assertEquals(419, response.statusCode());
        assertTrue(response.body().contains("CSRF token mismatch"), response.body());
    }

    @Test
    void postWithXsrfHeaderIsAllowed() throws Exception {
        String cookie = newSessionCookie();
        String token = tokenOf(cookie);

        // 把明文 token 按 EncryptCookies 的响应侧格式加密（含 CookieValuePrefix），
        // 这正是 Laravel 塞进 XSRF-TOKEN cookie、前端 axios 再放进 header 的值。
        String encrypted = new EncryptCookies(encrypter).encrypt("XSRF-TOKEN", token);

        HttpResponse<String> response = send("POST", "/auth/verify/1", cookie,
                Map.of("X-XSRF-TOKEN", encrypted));

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().startsWith("verified|"), response.body());
    }

    @Test
    void postWithCsrfHeaderIsAllowed() throws Exception {
        String cookie = newSessionCookie();
        String token = tokenOf(cookie);

        HttpResponse<String> response = send("POST", "/auth/verify/1", cookie,
                Map.of("X-CSRF-TOKEN", token));

        assertEquals(200, response.statusCode(), response.body());
    }

    @Test
    void postWithFormTokenIsAllowed() throws Exception {
        String cookie = newSessionCookie();
        String token = tokenOf(cookie);

        HttpResponse<String> response = send("POST", "/auth/verify/1", cookie,
                Map.of("Content-Type", "application/x-www-form-urlencoded"),
                "_token=" + token);

        assertEquals(200, response.statusCode(), response.body());
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        String cookie = newSessionCookie();

        HttpResponse<String> response = send("POST", "/auth/verify/1", cookie,
                Map.of("X-CSRF-TOKEN", "0".repeat(40)));

        assertEquals(419, response.statusCode());
    }

    @Test
    void sameOriginBypassesTokenCheck() throws Exception {
        String cookie = newSessionCookie();

        HttpResponse<String> response = send("POST", "/auth/verify/1", cookie,
                Map.of("Sec-Fetch-Site", "same-origin"));

        assertEquals(200, response.statusCode(), response.body());
    }

    @Test
    void sameSiteDoesNotBypassBecauseAllowSameSiteIsOff() throws Exception {
        String cookie = newSessionCookie();

        HttpResponse<String> response = send("POST", "/auth/verify/1", cookie,
                Map.of("Sec-Fetch-Site", "same-site"));

        assertEquals(419, response.statusCode());
    }

    @Test
    void undecryptableXsrfHeaderIsRejected() throws Exception {
        String cookie = newSessionCookie();

        HttpResponse<String> response = send("POST", "/auth/verify/1", cookie,
                Map.of("X-XSRF-TOKEN", "not-a-payload"));

        assertEquals(419, response.statusCode());
    }

    /** 没有 APP_KEY（安装向导）时 encrypter 为 null：不能 NPE，按普通 419 处理。 */
    @Test
    void nullEncrypterDoesNotThrow() throws Exception {
        io.vertx.ext.web.Router router = io.vertx.ext.web.Router.router(vertx);
        router.route().handler(RequestInput.normalizer());
        router.post("/x").handler(ctx -> {
            FileSessionHandler handler =
                    new FileSessionHandler(sessions, StartSession.LIFETIME_MINUTES);
            ctx.put(StartSession.CTX_KEY,
                    new SessionStore(StartSession.COOKIE_NAME, handler)
                            .setId(SessionStore.generateSessionId()).start());
            ctx.next();
        });
        router.post("/x").handler(new VerifyCsrfToken(null));
        router.post("/x").handler(ctx -> ctx.response().setStatusCode(200).end("ok"));
        int separatePort = present(router);

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + separatePort + "/x"))
                        .header("X-XSRF-TOKEN", "whatever")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(419, response.statusCode(), response.body());
    }

    // ------------------------------------------------------------------ 工具

    /** 发一个 GET 拿到会话 cookie（{@code BS_SESSION=<密文>}）。 */
    private String newSessionCookie() throws Exception {
        HttpResponse<String> response = send("GET", "/", null, Map.of());

        return response.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith(StartSession.COOKIE_NAME + "="))
                .map(h -> h.split(";", 2)[0])
                .findFirst().orElseThrow();
    }

    /** 从会话 cookie 解出 id，再读会话文件拿 {@code _token}。 */
    private String tokenOf(String cookiePair) throws Exception {
        String encrypted = cookiePair.substring((StartSession.COOKIE_NAME + "=").length());
        String plain = (String) encrypter.decrypt(encrypted, false);
        String id = com.neoskin.web.cookie.CookieValuePrefix.remove(plain);

        assertEquals(40, id.length());

        FileSessionHandler handler = new FileSessionHandler(sessions, StartSession.LIFETIME_MINUTES);
        return (String) SessionStore.begin(StartSession.COOKIE_NAME, handler, id).start().token();
    }

    private HttpResponse<String> send(String method, String path, String cookie,
            Map<String, String> headers) throws Exception {
        return send(method, path, cookie, headers, null);
    }

    private HttpResponse<String> send(String method, String path, String cookie,
            Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path));

        if (cookie != null) {
            builder.header("cookie", cookie);
        }

        headers.forEach(builder::header);

        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));

        return HttpClient.newHttpClient().send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int present(io.vertx.ext.web.Router router) {
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

    private static String testKey() {
        byte[] key = new byte[32];

        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }

        return java.util.Base64.getEncoder().encodeToString(key);
    }
}
