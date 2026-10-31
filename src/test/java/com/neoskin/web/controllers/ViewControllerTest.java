package com.neoskin.web.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
import com.neoskin.db.PlayerRepository;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.db.UserRepository;
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.LoginFailCounter;
import com.neoskin.service.Option;
import com.neoskin.service.cipher.BcryptCipher;
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
 * {@link ViewController}（{@code Route::view}）的端到端测试。
 *
 * <p>锁定：已移植的 6 个后台静态页真实渲染 200（含 users 的
 * {@code blessing-extra.currentUser} 与 i18n 的建表表单），未移植视图仍 501，
 * 以及 admin 门控。</p>
 */
class ViewControllerTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private BcryptCipher cipher;

    private final Routes routes = Routes.load();
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

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
        SchemaInstaller schema = new SchemaInstaller(ds, settings);
        schema.install();

        AppPaths paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());
        Files.createFile(paths.installLock());

        Option option = new Option(ds, schema, paths);
        Translator translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");
        JavaScript javaScript = new JavaScript(paths, translator, null);
        BlessingFilter filter = new BlessingFilter();
        UserRepository users = new UserRepository(ds);
        PlayerRepository players = new PlayerRepository(ds);
        cipher = new BcryptCipher(10);

        vertx = Vertx.vertx();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        EncryptCookies cookies = new EncryptCookies(
                new com.neoskin.service.cipher.PhpEncrypter(key, "aes-256-cbc"));
        Map<String, Handler<RoutingContext>> registry = new HashMap<>();
        MiddlewareChain.connectValueLayer(registry, cookies, dir.resolve("sessions"),
                paths, settings.getVersion(), option, translator, users);

        HandlerResolver auth = AuthController.resolver(option, translator, javaScript, filter,
                new LoginFailCounter(), users, players, cipher, "", false);
        HandlerResolver views = ViewController.resolver(option, translator, javaScript, filter,
                users, false, settings.getVersion());
        HandlerResolver resolver = new HandlerResolver() {
            @Override
            public Handler<RoutingContext> resolve(String action) {
                Handler<RoutingContext> handler = auth.resolve(action);

                return handler != null ? handler : views.resolve(action);
            }

            @Override
            public Handler<RoutingContext> resolve(String action, String uri) {
                Handler<RoutingContext> handler = auth.resolve(action, uri);

                return handler != null ? handler : views.resolve(action, uri);
            }
        };

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
    void adminPagesRender() throws Exception {
        String cookie = login(seedUser("admin@a.com", UserRepository.ADMIN));

        HttpResponse<String> usersPage = get("/admin/users", cookie);
        assertEquals(200, usersPage.statusCode());
        assertTrue(usersPage.body().contains("用户管理"), "标题");
        assertTrue(usersPage.body().contains("id=\"blessing-extra\""), "users 页需要 extra");
        assertTrue(usersPage.body().contains("\"currentUser\""), "extra 必须带 currentUser");

        assertTrue(get("/admin/players", cookie).body().contains("角色管理"));
        assertTrue(get("/admin/reports", cookie).body().contains("举报管理"));
        assertTrue(get("/admin/plugins/manage", cookie).body().contains("插件管理"));
        assertTrue(get("/admin/plugins/market", cookie).body().contains("插件市场"));
    }

    @Test
    void i18nPageHasCreateForm() throws Exception {
        String cookie = login(seedUser("admin@a.com", UserRepository.ADMIN));

        HttpResponse<String> res = get("/admin/i18n", cookie);
        assertEquals(200, res.statusCode());
        String html = res.body();
        assertTrue(html.contains("多语言"));
        assertTrue(html.contains("name=\"group\""));
        assertTrue(html.contains("name=\"key\""));
        assertTrue(html.contains("name=\"text\""));
        assertTrue(html.contains("name=\"_token\""), "表单必须带 CSRF");
    }

    @Test
    void bindOauthAndSkinlibViewsRender() throws Exception {
        String cookie = login(seedUser("admin@a.com", UserRepository.ADMIN));

        // 源 EnsureEmailFilled：邮箱已填写的用户访问 auth/bind → 302 /user
        HttpResponse<String> bind = get("/auth/bind", cookie);
        assertEquals(302, bind.statusCode(), bind.body());
        assertTrue(bind.headers().firstValue("Location").orElse("").endsWith("/user"));

        HttpResponse<String> oauth = get("/user/oauth/manage", cookie);
        assertEquals(200, oauth.statusCode(), oauth.body());
        assertTrue(oauth.body().contains("OAuth2 应用"));

        HttpResponse<String> skinlib = get("/skinlib", cookie);
        assertEquals(200, skinlib.statusCode(), skinlib.body());
        assertTrue(skinlib.body().contains("id=\"blessing-extra\""));
        assertTrue(skinlib.body().contains("currentUid"), skinlib.body());

        // 未登录访问 bind（authorize）→ 302
        assertEquals(302, get("/auth/bind", "").statusCode());
    }

    @Test
    void setupViewRoutesArePortedAndLocked() throws Exception {
        // 已安装（install.lock 存在）→ CheckInstallation 渲染 setup.locked
        HttpResponse<String> info = get("/setup/info", "");
        assertEquals(200, info.statusCode());
        assertTrue(info.body().contains("已安装过"), info.body());
    }

    @Test
    void viewRoutesAreAdminOnly() throws Exception {
        String userCookie = login(seedUser("user@a.com", UserRepository.NORMAL));
        assertEquals(403, get("/admin/users", userCookie).statusCode());

        HttpResponse<String> guest = get("/admin/users", "");
        assertEquals(302, guest.statusCode());
        assertNotNull(guest.headers().firstValue("Location").orElse(null));
    }

    // ================================================================ 工具

    private int seedUser(String email, int permission) throws Exception {
        String hash = cipher.hash("secret123", "");
        String sql = "INSERT INTO users (`email`, `nickname`, `score`, `avatar`, `password`,"
                + " `ip`, `permission`, `last_sign_at`, `register_at`)"
                + " VALUES (?, ?, 0, 0, ?, '127.0.0.1', ?,"
                + " '2026-01-01 00:00:00', '2026-01-01 00:00:00')";
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            stmt.setString(2, email.substring(0, email.indexOf('@')));
            stmt.setString(3, hash);
            stmt.setInt(4, permission);
            stmt.executeUpdate();
        }

        return new UserRepository(ds).findByEmail(email).uid();
    }

    private String login(int uid) throws Exception {
        String email = "";
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT email FROM users WHERE uid = ?")) {
            stmt.setInt(1, uid);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    email = rs.getString(1);
                }
            }
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Sec-Fetch-Site", "same-origin")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "identification=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                                + "&password=secret123", StandardCharsets.UTF_8));
        HttpResponse<String> res = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, res.statusCode(), "login failed: " + res.body());

        return res.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith("BS_SESSION="))
                .map(h -> h.split(";")[0])
                .findFirst().orElseThrow(() -> new AssertionError("no session cookie"));
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "text/html");
        if (cookie != null && !cookie.isEmpty()) {
            builder.header("Cookie", cookie);
        }
        builder.GET();

        return http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
