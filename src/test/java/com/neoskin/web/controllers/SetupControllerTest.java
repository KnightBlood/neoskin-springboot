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
import java.sql.ResultSet;
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
import com.neoskin.db.TextureRepository;
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
 * 安装向导（{@link SetupController} + {@code Route::view} 的 setup 页面）端到端测试。
 *
 * <p>初始不写 {@code install.lock}：验证欢迎页、数据库探测跳转、表单校验、
 * 完成安装（建表 + 超级管理员 + 站点选项 + install.lock）、以及安装完成后的
 * {@code setup.locked} 门控；另外覆盖数据库连接失败与 SQLite 配置写回 .env。</p>
 */
class SetupControllerTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private AppPaths paths;
    private Option option;
    private NeoSkinSettings settings;
    private BcryptCipher cipher;
    private Path envFile;

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

        settings = new NeoSkinSettings();
        settings.getDatabase().setConnection("sqlite");
        settings.getDatabase().setName(file.toAbsolutePath().toString());
        settings.getStorage().setRoot(dir.resolve("storage").toString());
        SchemaInstaller schema = new SchemaInstaller(ds, settings);
        // 故意不建表：模拟全新安装（finish 时再 install）

        paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());
        // 不创建 install.lock

        envFile = dir.resolve(".env");
        Files.writeString(envFile, "APP_ENV=local\nDB_CONNECTION=old\nDB_HOST=old\n",
                StandardCharsets.UTF_8);
        System.setProperty("neoskin.env.file", envFile.toString());

        option = new Option(ds, schema, paths);
        Translator translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");
        JavaScript javaScript = new JavaScript(paths, translator, null);
        BlessingFilter filter = new BlessingFilter();
        UserRepository users = new UserRepository(ds);
        TextureRepository textures = new TextureRepository(ds);
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

        HandlerResolver setup = SetupController.resolver(option, translator, users, cipher,
                settings.getSecure().getSalt(), settings, paths, ds, "Asia/Shanghai");
        HandlerResolver views = ViewController.resolver(option, translator, javaScript, filter,
                users, false, settings.getVersion());
        HandlerResolver auth = AuthController.resolver(option, translator, javaScript, filter,
                new LoginFailCounter(), users, players, cipher, "", false);
        HandlerResolver resolver = new HandlerResolver() {
            @Override
            public Handler<RoutingContext> resolve(String action) {
                Handler<RoutingContext> handler = setup.resolve(action);
                if (handler == null) {
                    handler = views.resolve(action);
                }

                return handler != null ? handler : auth.resolve(action);
            }

            @Override
            public Handler<RoutingContext> resolve(String action, String uri) {
                Handler<RoutingContext> handler = setup.resolve(action, uri);
                if (handler == null) {
                    handler = views.resolve(action, uri);
                }

                return handler != null ? handler : auth.resolve(action, uri);
            }
        };

        VertxRouteRegistry.BuildResult built = VertxRouteRegistry.build(
                routes, resolver, vertx, List.of(), registry);
        port = listen(built.router());
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("neoskin.env.file");
        ds.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------- wizard

    @Test
    void welcomeAndInfoPagesRender() throws Exception {
        String welcome = get("/setup").body();
        assertEquals(200, get("/setup").statusCode());
        assertTrue(welcome.contains("欢迎使用 Blessing Skin Server v6.0.11_patch"), welcome);
        assertTrue(welcome.contains("下一步"));

        HttpResponse<String> info = get("/setup/info");
        assertEquals(200, info.statusCode(), info.body());
        assertTrue(info.body().contains("填写信息"));
        assertTrue(info.body().contains("name=\"email\""));
        assertTrue(info.body().contains("value=\"Blessing Skin\""));
    }

    @Test
    void nonSetupRequestsRedirectToSetupBeforeInstallation() throws Exception {
        HttpResponse<String> res = get("/", null);
        assertEquals(302, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("").endsWith("/setup"));
    }

    @Test
    void databaseGetRedirectsWhenConnectionWorks() throws Exception {
        HttpResponse<String> res = get("/setup/database");
        assertEquals(302, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("").endsWith("/setup/info"));
    }

    @Test
    void databasePostWritesEnvAndRejectsBadConnection() throws Exception {
        String sqlitePath = dir.resolve("wizard.sqlite").toAbsolutePath().toString();
        HttpResponse<String> ok = post("/setup/database",
                "type=sqlite&db=" + URLEncoder.encode(sqlitePath, StandardCharsets.UTF_8)
                        + "&host=&port=&username=&password=&prefix=", null);
        assertEquals(302, ok.statusCode(), ok.body());
        String env = Files.readString(envFile);
        assertTrue(env.contains("DB_CONNECTION=sqlite"), env);
        assertTrue(env.contains("DB_DATABASE=" + sqlitePath), env);

        HttpResponse<String> bad = post("/setup/database",
                "type=mysql&host=127.0.0.1&port=1&username=x&password=y&db=z", null);
        assertEquals(500, bad.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) com.neoskin.support.PhpJson
                .decode(bad.body());
        assertTrue(String.valueOf(error.get("message")).contains("无法连接至 MySQL/MariaDB"),
                bad.body());
    }

    // ------------------------------------------------------------- finish

    @Test
    void finishValidationRedirectsWithErrors() throws Exception {
        HttpResponse<String> res = post("/setup/finish",
                "email=not-an-email&nickname=&password=short&password_confirmation=other"
                        + "&site_name=", null);
        assertEquals(302, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("").endsWith("/setup/info"));
        String cookie = res.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith("BS_SESSION="))
                .map(h -> h.split(";")[0]).findFirst().orElse("");

        String info = get("/setup/info", cookie).body();
        assertTrue(info.contains("toast toast-error"), info);
    }

    @Test
    void finishInstallsSchemaCreatesSuperAdminAndLock() throws Exception {
        HttpResponse<String> res = post("/setup/finish", form(Map.of(
                "email", "root@example.com",
                "nickname", "Root",
                "password", "super-secret",
                "password_confirmation", "super-secret",
                "site_name", "My Skin Site")), null);
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("安装成功"));

        assertTrue(Files.exists(paths.installLock()));

        // 超级管理员
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT uid, email, nickname, permission, verified, password, ip"
                             + " FROM users WHERE email = ?")) {
            stmt.setString(1, "root@example.com");
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt("permission"));
                assertTrue(rs.getBoolean("verified"));
                assertEquals("Root", rs.getString("nickname"));
                assertTrue(cipher.verify("super-secret", rs.getString("password"), ""));
                assertNotNull(rs.getString("ip"));
            }
        }

        // 站点选项
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT option_value FROM options WHERE option_name = 'site_name'");
             ResultSet rs = stmt.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("My Skin Site", rs.getString(1));
        }

        // 已安装后 setup 页面显示 locked
        HttpResponse<String> locked = get("/setup");
        assertEquals(200, locked.statusCode());
        assertTrue(locked.body().contains("已安装过"), locked.body());
        assertEquals(200, get("/setup/info").statusCode());
        assertTrue(get("/setup/info").body().contains("已安装过"));
    }

    // ================================================================ 工具

    private static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }

        return sb.toString();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return get(path, null);
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

    private HttpResponse<String> post(String path, String body, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Sec-Fetch-Site", "same-origin");
        if (cookie != null && !cookie.isEmpty()) {
            builder.header("Cookie", cookie);
        }
        builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body,
                StandardCharsets.UTF_8));

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
