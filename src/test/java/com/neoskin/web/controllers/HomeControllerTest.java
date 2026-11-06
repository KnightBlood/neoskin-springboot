package com.neoskin.web.controllers;

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
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.TranslationFileLoader;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.EncryptCookies;
import com.neoskin.web.middleware.MiddlewareChain;
import com.neoskin.web.view.AssetManifest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

/**
 * {@link HomeController#index()} 的端到端渲染测试。
 *
 * <p>锁定：{@code GET /} 不再是 501 占位，而是渲染出首页 HTML，
 * 且翻译层、站点配置、共享片段（head / foot / languages / copyright）
 * 都真的进了输出。</p>
 */
class HomeControllerTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private Option option;
    private Translator translator;

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
        option.set("site_description", "一个测试站点");
        option.set("navbar_color", "cyan");
        option.set("transparent_navbar", "false");

        translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");
        JavaScript javaScript = new JavaScript(paths, translator, null);

        vertx = Vertx.vertx();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        EncryptCookies cookies = new EncryptCookies(
                new com.neoskin.service.cipher.PhpEncrypter(key, "aes-256-cbc"));
        Map<String, Handler<RoutingContext>> registry = new HashMap<>();
        MiddlewareChain.connectValueLayer(registry, cookies, dir.resolve("sessions"));

        HandlerResolver resolver = HomeController.resolver(option, translator, javaScript);

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
    void rootRendersHomePage() throws Exception {
        HttpResponse<String> res = send("/");

        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.headers().firstValue("Content-Type").orElse("").contains("text/html"),
                "必须是 HTML 而不是 JSON 501 占位");

        String body = res.body();
        // 站点配置进了输出
        assertTrue(body.contains("NeoSkin 测试站"), "site_name 必须渲染");
        assertTrue(body.contains("一个测试站点"), "site_description 必须渲染");
        // 翻译层生效（zh_CN 语料）
        assertTrue(body.contains("登录"), "trans('general.login') 的中文行");
        assertTrue(body.contains("注册"), "trans('general.register') 的中文行");
        // 未登录分支
        assertFalse(body.contains("logout-button"), "未登录不应有用户菜单");
        // 共享片段
        assertTrue(body.contains("name=\"csrf-token\""), "shared.head 的 csrf meta");
        assertTrue(body.contains("window.blessing"), "shared.head 注入了 blessing");
        assertTrue(body.contains("/lang/zh_CN.js?t="), "shared.foot 注入了 i18n 脚本");
        assertTrue(body.contains("blessing-skin-server"), "shared.copyright 的链接");
        // 资源路径来自 AssetManifest（按实际 static/app 解析哈希），不是模板硬编码
        Map<String, String> assets = AssetManifest.urls(false, "");
        assertTrue(body.contains(assets.get("app")), "app.js: " + assets.get("app"));
        assertTrue(body.contains(assets.get("style")), "style.css: " + assets.get("style"));
        assertTrue(body.contains(assets.get("home")), "home.js: " + assets.get("home"));
        assertTrue(body.contains(assets.get("home_css")),
                "home-css.css: " + assets.get("home_css"));
    }

    @Test
    void apiRootStillReturnsJson() throws Exception {
        HttpResponse<String> res = send("/api");

        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("\"blessing_skin\""), res.body());
        assertTrue(res.body().contains("6.0.11_patch"), res.body());
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
