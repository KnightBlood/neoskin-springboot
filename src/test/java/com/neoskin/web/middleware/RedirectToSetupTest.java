package com.neoskin.web.middleware;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.service.Option;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.Routes;
import com.neoskin.web.VertxRouteRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

/**
 * {@link RedirectToSetup} 的端到端测试。
 *
 * <p>源语义：没有 {@code storage/install.lock} 时，除 {@code setup*} 之外一律
 * 302 到 {@code /setup}；有 lock 时正常放行。</p>
 */
class RedirectToSetupTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private AppPaths paths;
    private Option option;

    @BeforeEach
    void setUp() throws Exception {
        Path file = dir.resolve("t.sqlite");
        Files.createFile(file);
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(1);
        ds = new HikariDataSource(cfg);

        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getDatabase().setConnection("sqlite");
        settings.getDatabase().setName(file.toAbsolutePath().toString());
        settings.getStorage().setRoot(dir.resolve("storage").toString());
        new SchemaInstaller(ds, settings).install();

        paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());
        option = new Option(ds, new SchemaInstaller(ds, settings), paths);

        vertx = Vertx.vertx();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        EncryptCookies cookies = new EncryptCookies(
                new com.neoskin.service.cipher.PhpEncrypter(key, "aes-256-cbc"));
        Map<String, Handler<RoutingContext>> registry = new HashMap<>();
        MiddlewareChain.connectValueLayer(registry, cookies, dir.resolve("sessions"),
                paths, "6.0.11_patch", option);

        // 所有 action 都回 200 + 路径，便于观察「是否被放行」
        HandlerResolver resolver = action -> ctx ->
                ctx.response().setStatusCode(200).end("ok:" + ctx.request().path());

        VertxRouteRegistry.BuildResult built = VertxRouteRegistry.build(
                Routes.load(), resolver, vertx, List.of(), registry);
        port = listen(built.router());
    }

    @AfterEach
    void tearDown() throws Exception {
        ds.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void withoutLockRedirectsToSetup() throws Exception {
        // 没有 install.lock
        HttpResponse<String> res = get("/");

        assertEquals(302, res.statusCode(), res.body());
        assertTrue(res.headers().firstValue("Location").orElse("").endsWith("/setup"),
                res.headers().firstValue("Location").orElse(""));
    }

    @Test
    void setupPathIsAllowedWithoutLock() throws Exception {
        // setup 组路由本身必须放行（否则安装向导自己也被重定向）
        HttpResponse<String> res = get("/setup");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("ok:/setup"));
    }

    @Test
    void withLockEverythingIsAllowed() throws Exception {
        Files.createFile(paths.installLock());

        assertEquals(200, get("/").statusCode());
        assertEquals(200, get("/setup").statusCode());
    }

    @Test
    void setupPrefixMatchesPathSegmentOnly() {
        assertTrue(RedirectToSetup.isSetupPath("/setup"));
        assertTrue(RedirectToSetup.isSetupPath("/setup/database"));
        assertTrue(RedirectToSetup.isSetupPath("setup"));
        assertFalse(RedirectToSetup.isSetupPath("/setupx"));
        assertFalse(RedirectToSetup.isSetupPath("/"));
    }

    @Test
    void versionComparison() {
        assertTrue(RedirectToSetup.isNewer("6.0.11", "6.0.10"));
        assertFalse(RedirectToSetup.isNewer("6.0.11", "6.0.11"));
        assertFalse(RedirectToSetup.isNewer("6.0.9", "6.0.11"));
        // 6.0.11_patch 与 6.0.11 视为同级（patch 后缀不参与比较）
        assertFalse(RedirectToSetup.isNewer("6.0.11_patch", "6.0.11"));
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
