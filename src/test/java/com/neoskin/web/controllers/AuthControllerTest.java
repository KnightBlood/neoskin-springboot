package com.neoskin.web.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.LoginFailCounter;
import com.neoskin.service.Option;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.Routes;
import com.neoskin.web.VertxRouteRegistry;
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.TranslationFileLoader;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.EncryptCookies;
import com.neoskin.web.middleware.MiddlewareChain;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code AuthController@login}（GET /auth/login）的端到端渲染测试。
 */
class AuthControllerTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private Option option;
    private Translator translator;
    private LoginFailCounter failures;

    private final Routes routes = Routes.load();

    @BeforeEach
    void setUp() throws Exception {
        Path file = dir.resolve("test.sqlite");
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

        AppPaths paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());
        Files.createFile(paths.installLock());

        option = new Option(ds, new SchemaInstaller(ds, settings), paths);
        option.set("site_name", "NeoSkin 测试站");
        option.set("navbar_color", "cyan");

        translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");
        JavaScript javaScript = new JavaScript(paths, translator, null);
        failures = new LoginFailCounter();

        vertx = Vertx.vertx();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        EncryptCookies cookies = new EncryptCookies(
                new com.neoskin.service.cipher.PhpEncrypter(key, "aes-256-cbc"));
        Map<String, Handler<RoutingContext>> registry = new HashMap<>();
        MiddlewareChain.connectValueLayer(registry, cookies, dir.resolve("sessions"));

        HandlerResolver resolver = AuthController.resolver(
                option, translator, javaScript, new BlessingFilter(), failures,
                new com.neoskin.db.UserRepository(ds),
                new com.neoskin.db.PlayerRepository(ds),
                new com.neoskin.service.cipher.BcryptCipher(10), "", false);

        VertxRouteRegistry.BuildResult built = VertxRouteRegistry.build(
                routes, resolver, vertx, List.of(), registry);
        port = listen(built.router());
    }

    @AfterEach
    void tearDown() throws Exception {
        ds.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void loginPageRenders() throws Exception {
        HttpResponse<String> res = send("/auth/login");

        assertEquals(200, res.statusCode(), res.body());
        String body = res.body();

        // 标题 = trans('auth.login.title') + ' - ' + site_name
        assertTrue(body.contains("<title>登录 - NeoSkin 测试站</title>"), body.substring(0, Math.min(400, body.length())));
        // rows 四项都渲染出来
        assertTrue(body.contains("登录以管理您的角色与皮肤"), "notice row 的中文文案");
        assertTrue(body.contains("<main></main>"), "form row 的占位");
        assertTrue(body.contains("注册新账号"), "registration-link row");
        // extra JSON
        assertTrue(body.contains("blessing-extra"), "extra 脚本块");
        assertTrue(body.contains("\"tooManyFails\":false"), body);
        // 共享片段
        assertTrue(body.contains("csrf-token"));
        assertTrue(body.contains("/lang/zh_CN.js?t="));
    }

    @Test
    void tooManyFailsFlipsAfterFourFailures() throws Exception {
        // 源语义是 cache($key) > 3，即第 4 次失败之后才为真
        String key = LoginFailCounter.keyForIp("127.0.0.1");
        for (int i = 0; i < 3; i++) {
            failures.increment(key);
        }
        assertTrue(send("/auth/login").body().contains("\"tooManyFails\":false"),
                "3 次失败仍为 false");

        failures.increment(key);
        assertTrue(send("/auth/login").body().contains("\"tooManyFails\":true"),
                "第 4 次失败后为 true");
    }

    // ================================================================ 工具

    private HttpResponse<String> send(String path) throws Exception {
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
