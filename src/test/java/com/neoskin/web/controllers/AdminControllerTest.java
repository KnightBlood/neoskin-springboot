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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
import com.neoskin.support.PhpJson;
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
 * {@link AdminController} 的端到端测试（真实 Vert.x + SQLite + 登录会话）。
 *
 * <p>锁定：</p>
 * <ol>
 *   <li>{@code GET admin} 渲染仪表盘（统计数字、占用空间、图表容器），登录 + role:admin 门控；</li>
 *   <li>{@code GET admin/chart} 的 {@code {labels,xAxis,data}} 形状与按日聚合；</li>
 *   <li>{@code GET admin/status} 的版本 / 数据库 / 插件区块；</li>
 *   <li>非管理员 403，未登录 302。</li>
 * </ol>
 */
class AdminControllerTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private Option option;
    private TextureRepository textures;

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

        option = new Option(ds, schema, paths);
        Translator translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");
        JavaScript javaScript = new JavaScript(paths, translator, null);
        BlessingFilter filter = new BlessingFilter();
        UserRepository users = new UserRepository(ds);
        PlayerRepository players = new PlayerRepository(ds);
        textures = new TextureRepository(ds);
        BcryptCipher cipher = new BcryptCipher(10);

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
        HandlerResolver admin = AdminController.resolver(option, translator, javaScript, filter,
                users, players, textures, false, paths, settings, "Asia/Shanghai");
        HandlerResolver resolver = new HandlerResolver() {
            @Override
            public Handler<RoutingContext> resolve(String action) {
                Handler<RoutingContext> handler = auth.resolve(action);

                return handler != null ? handler : admin.resolve(action);
            }

            @Override
            public Handler<RoutingContext> resolve(String action, String uri) {
                Handler<RoutingContext> handler = auth.resolve(action, uri);

                return handler != null ? handler : admin.resolve(action, uri);
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

    // ------------------------------------------------------------- dashboard

    @Test
    void dashboardShowsStatisticsAndWidgets() throws Exception {
        seedUser("admin@a.com", 1);
        seedUser("u1@a.com", 0);
        seedUser("u2@a.com", 0);
        long t1 = textures.insert("t1", "steve", "h1", 1000, 1, true, today());
        long t2 = textures.insert("t2", "steve", "h2", 500, 1, true, today());
        assertTrue(t1 > 0 && t2 > 0);

        String cookie = login("admin@a.com");
        HttpResponse<String> res = get("/admin", cookie);

        assertEquals(200, res.statusCode());
        String html = res.body();
        assertTrue(html.contains("注册用户"), "usage widget 缺失");
        assertTrue(html.contains("占用空间大小"));
        assertTrue(html.contains("1.5MB"), "1500KB 应显示为 1.5MB: " + snippet(html));
        assertTrue(html.contains("chart-users-registration"), "chart widget 缺失");
        assertTrue(html.contains(">3<"), "用户总数应为 3: " + snippet(html));
        assertTrue(html.contains(">2<"), "材质总数应为 2");
    }

    @Test
    void chartDataGroupsByDay() throws Exception {
        seedUser("admin@a.com", 1);
        // 今天 2 个注册、昨天 1 个
        seedUserAt("today1@a.com", today());
        seedUserAt("today2@a.com", today());
        seedUserAt("yesterday@a.com", yesterday());

        // 今天 1 个上传、昨天 2 个上传
        textures.insert("u-today", "steve", "h1", 10, 1, true, today());
        textures.insert("u-y1", "steve", "h2", 10, 1, true, yesterday());
        textures.insert("u-y2", "cape", "h3", 10, 1, true, yesterday());

        String cookie = login("admin@a.com");
        HttpResponse<String> res = get("/admin/chart", cookie);
        assertEquals(200, res.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        assertTrue(res.body().startsWith("{\"labels\":["), "labels 必须编码成 JSON 数组: "
                + res.body());

        com.neoskin.support.PhpJson.Array labels =
                (com.neoskin.support.PhpJson.Array) body.get("labels");
        assertEquals("用户注册", labels.get(0L));
        assertEquals("材质上传", labels.get(1L));

        com.neoskin.support.PhpJson.Array xAxis =
                (com.neoskin.support.PhpJson.Array) body.get("xAxis");
        assertEquals(31, xAxis.size());

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        DateTimeFormatter carbonL = DateTimeFormatter.ofPattern("yyyy/M/d");
        assertEquals(carbonL.format(today.minusDays(30)), xAxis.get(0L));
        assertEquals(carbonL.format(today), xAxis.get(30L));

        com.neoskin.support.PhpJson.Array data =
                (com.neoskin.support.PhpJson.Array) body.get("data");
        assertEquals(2, data.size());
        com.neoskin.support.PhpJson.Array registration =
                (com.neoskin.support.PhpJson.Array) data.get(0L);
        com.neoskin.support.PhpJson.Array uploads =
                (com.neoskin.support.PhpJson.Array) data.get(1L);
        assertEquals(2, ((Number) registration.get(30L)).intValue(), "今天注册数");
        assertEquals(1, ((Number) registration.get(29L)).intValue(), "昨天注册数");
        assertEquals(1, ((Number) uploads.get(30L)).intValue(), "今天上传数");
        assertEquals(2, ((Number) uploads.get(29L)).intValue(), "昨天上传数");
    }

    @Test
    void statusPageShowsRuntimeInformation() throws Exception {
        seedUser("admin@a.com", 1);
        String cookie = login("admin@a.com");

        HttpResponse<String> res = get("/admin/status", cookie);
        assertEquals(200, res.statusCode());
        String html = res.body();
        assertTrue(html.contains("运行状态"));
        assertTrue(html.contains("6.0.11_patch"), "应用版本");
        assertTrue(html.contains("13.38.0"), "Laravel 版本");
        assertTrue(html.contains("SQLite"), "数据库类型");
        assertTrue(html.contains("已开启的插件 (0)"), "插件数量");
    }

    @Test
    void statusEndpointReturnsJsonForSpa() throws Exception {
        seedUser("admin@a.com", 1);
        String cookie = login("admin@a.com");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/admin/status"))
                .header("Accept", "application/json")
                .header("Cookie", cookie)
                .GET().build();
        HttpResponse<String> res = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("Content-Type").orElse("")
                .contains("application/json"), "状态页 SPA 请求必须返回 JSON");
        String json = res.body();
        assertTrue(json.contains("\"bs\""), "bs 区块");
        assertTrue(json.contains("\"server\""), "server 区块");
        assertTrue(json.contains("\"db\""), "db 区块");
        assertTrue(json.contains("\"plugins\""), "plugins 区块");
    }

    // ---------------------------------------------------------------- 权限

    @Test
    void nonAdminIsForbidden() throws Exception {
        seedUser("user@a.com", 0);
        String cookie = login("user@a.com");

        assertEquals(403, get("/admin", cookie).statusCode());
        assertEquals(403, get("/admin/chart", cookie).statusCode());
        assertEquals(403, get("/admin/status", cookie).statusCode());
    }

    @Test
    void guestIsRedirected() throws Exception {
        HttpResponse<String> res = get("/admin", "");
        assertEquals(302, res.statusCode());
        assertNotNull(res.headers().firstValue("Location").orElse(null));
    }

    // ================================================================ 工具

    private static String today() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + " 12:00:00";
    }

    private static String yesterday() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + " 12:00:00";
    }

    private void seedUser(String email, int permission) throws Exception {
        seedUserAt(email, "2026-01-01 00:00:00", permission);
    }

    private void seedUserAt(String email, String registerAt) throws Exception {
        seedUserAt(email, registerAt, 0);
    }

    private void seedUserAt(String email, String registerAt, int permission) throws Exception {
        String hash = new BcryptCipher(10).hash("secret123", "");
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO users (`email`, `nickname`, `score`, `avatar`, `password`,"
                             + " `ip`, `permission`, `last_sign_at`, `register_at`)"
                             + " VALUES (?, '', 0, 0, ?, '127.0.0.1', ?,"
                             + " '2026-01-01 00:00:00', ?)")) {
            stmt.setString(1, email);
            stmt.setString(2, hash);
            stmt.setInt(3, permission);
            stmt.setString(4, registerAt);
            stmt.executeUpdate();
        }
    }

    private String login(String email) throws Exception {
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

    private static String snippet(String html) {
        return html.length() > 400 ? html.substring(0, 400) + "…" : html;
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
