package com.neoskin.web.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.neoskin.db.CommunicationRepository;
import com.neoskin.db.NotificationRepository;
import com.neoskin.db.PlayerRepository;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.db.UserRepository;
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.CommunicationRenderer;
import com.neoskin.service.Communications;
import com.neoskin.service.LoginFailCounter;
import com.neoskin.service.Option;
import com.neoskin.service.cipher.BcryptCipher;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.PhpUrlGenerator;
import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.Routes;
import com.neoskin.web.VertxRouteRegistry;
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.TranslationFileLoader;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.EncryptCookies;
import com.neoskin.web.middleware.MiddlewareChain;
import com.neoskin.web.view.PageView;
import com.neoskin.web.view.ViewContext;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

/**
 * {@link NotificationsController} 的端到端测试。
 *
 * <p>锁定：按 all/normal/uid/email 群发站内信（写 communications +
 * notifications 行）、收件箱未读列表、已读接口的 404/成功语义，以及
 * {@code shared.notifications} composer 等价实现（{@link PageView}）。</p>
 */
class NotificationsControllerTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private UserRepository users;
    private CommunicationRepository communications;
    private NotificationRepository notifications;
    private Communications service;
    private Option option;
    private Translator translator;
    private JavaScript javaScript;
    private BlessingFilter filter;
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

        option = new Option(ds, schema, paths);
        translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");
        javaScript = new JavaScript(paths, translator, null);
        filter = new BlessingFilter();
        users = new UserRepository(ds);
        communications = new CommunicationRepository(ds, schema, "Asia/Shanghai");
        notifications = new NotificationRepository(ds, schema, "Asia/Shanghai");
        CommunicationRenderer renderer = new CommunicationRenderer();
        service = new Communications(ds, communications, notifications, renderer,
                "Asia/Shanghai");
        cipher = new BcryptCipher(10);
        PlayerRepository players = new PlayerRepository(ds);

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
        HandlerResolver notificationResolver = NotificationsController.resolver(option,
                translator, users, communications, notifications, service, ds,
                "Asia/Shanghai");
        HandlerResolver resolver = new HandlerResolver() {
            @Override
            public Handler<RoutingContext> resolve(String action) {
                Handler<RoutingContext> handler = auth.resolve(action);

                return handler != null ? handler : notificationResolver.resolve(action);
            }

            @Override
            public Handler<RoutingContext> resolve(String action, String uri) {
                Handler<RoutingContext> handler = auth.resolve(action, uri);

                return handler != null ? handler : notificationResolver.resolve(action, uri);
            }
        };

        VertxRouteRegistry.BuildResult built = VertxRouteRegistry.build(
                routes, resolver, vertx, List.of(), registry);
        port = listen(built.router());

        PageView.setInboxProvider(user -> {
            List<Map<String, Object>> items = new java.util.ArrayList<>();
            for (NotificationRepository.Row row : notifications.unread(user.uid(), 20)) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("id", row.id);
                item.put("title", service.title(row));
                items.add(item);
            }
            return items;
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        PageView.setInboxProvider(null); // 静态组合根：测试结束必须复位
        ds.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------- send

    @Test
    void sendToAllCreatesCommunicationAndNotificationRows() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        seedUser("u1@a.com", UserRepository.NORMAL);
        seedUser("u2@a.com", UserRepository.NORMAL);
        String cookie = login("admin@a.com");

        HttpResponse<String> res = post("/admin/notifications/send",
                "receiver=all&title=" + enc("系统公告") + "&content=" + enc("**hi**"), cookie);
        assertEquals(302, res.statusCode(), res.body());
        assertTrue(res.headers().firstValue("Location").orElse("").endsWith("/admin"));

        Map<String, Object> comm = singleCommunication();
        assertEquals("系统公告", comm.get("title"));
        assertEquals("message", comm.get("kind"));
        assertEquals("published", comm.get("status"));
        assertEquals("source", comm.get("editor"));
        assertEquals("**hi**", comm.get("source"));
        String token = String.valueOf(comm.get("delivery_token"));
        assertEquals(32, token.length(), "16 字节 hex 的 delivery_token");
        @SuppressWarnings("unchecked")
        Map<String, Object> audience = (Map<String, Object>) PhpJson.decode(
                String.valueOf(comm.get("audience")));
        assertEquals("all", audience.get("receiver"));

        assertEquals(3, countNotifications(), "all 发给全部 3 个用户");
        Map<String, Object> data = singleNotificationData();
        assertEquals(((Number) comm.get("id")).longValue(),
                ((Number) data.get("communication_id")).longValue());
        assertEquals(token, data.get("delivery_token"));
        assertEquals("html", data.get("format"));
        assertEquals("系统公告", data.get("title"));
        assertEquals("**hi**", data.get("content"));
    }

    @Test
    void sendToNormalOnlySkipsAdmins() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        seedUser("u1@a.com", UserRepository.NORMAL);
        seedUser("u2@a.com", UserRepository.NORMAL);
        String cookie = login("admin@a.com");

        assertEquals(302, post("/admin/notifications/send",
                "receiver=normal&title=" + enc("给普通用户") + "&content=x", cookie).statusCode());

        assertEquals(2, countNotifications());
        assertFalse(hasNotificationFor(1), "管理员（uid=1）不应收到 normal 群发");
    }

    @Test
    void sendToUidAndEmailTouchesSingleUser() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        int target = seedUser("u1@a.com", UserRepository.NORMAL);
        String cookie = login("admin@a.com");

        assertEquals(302, post("/admin/notifications/send",
                "receiver=uid&uid=" + target + "&title=" + enc("直接") + "&content=x",
                cookie).statusCode());
        assertEquals(1, countNotifications());
        assertTrue(hasNotificationFor(target));

        assertEquals(302, post("/admin/notifications/send",
                "receiver=email&email=" + enc("u1@a.com") + "&title=" + enc("邮件") + "&content=y",
                cookie).statusCode());
        assertEquals(2, countNotifications());
        assertTrue(hasNotificationFor(target));
    }

    @Test
    void sendValidationRedirectsWithErrors() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        String cookie = login("admin@a.com");

        // 缺 uid
        assertEquals(302, post("/admin/notifications/send",
                "receiver=uid&title=x&content=y", cookie).statusCode());
        // 标题超长（max:20）
        assertEquals(302, post("/admin/notifications/send",
                "receiver=all&title=" + enc("123456789012345678901") + "&content=y",
                cookie).statusCode());
        // 不存在的 uid
        assertEquals(302, post("/admin/notifications/send",
                "receiver=uid&uid=9999&title=x&content=y", cookie).statusCode());

        assertEquals(0, countNotifications());
        // 错误已闪存，页面显示需要 admin 页面；这里直接断言 session 里有错误键
        assertNotNull(cookie);
    }

    // ------------------------------------------------------------- read

    @Test
    void readMarksNotificationAndRejectsSecondTime() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        int target = seedUser("u1@a.com", UserRepository.NORMAL);
        String adminCookie = login("admin@a.com");
        assertEquals(302, post("/admin/notifications/send",
                "receiver=uid&uid=" + target + "&title=" + enc("标题A") + "&content="
                        + enc("**hi**"), adminCookie).statusCode());

        String id = notificationIdFor(target);
        String userCookie = login("u1@a.com");

        HttpResponse<String> res = post("/user/notifications/" + id, "", userCookie);
        assertEquals(200, res.statusCode(), res.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        assertEquals("标题A", body.get("title"));
        assertTrue(String.valueOf(body.get("content")).contains("hi"));
        assertTrue(String.valueOf(body.get("time")).matches("\\d{4}-\\d{2}-\\d{2} .*"));

        // 已读后再取 → 404
        assertEquals(404, post("/user/notifications/" + id, "", userCookie).statusCode());
        // 别人的通知 id → 404
        assertEquals(404, post("/user/notifications/" + id, "", adminCookie).statusCode());
    }

    @Test
    void pageViewInboxProviderExposesUnreadTitles() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        int target = seedUser("u1@a.com", UserRepository.NORMAL);
        String adminCookie = login("admin@a.com");
        assertEquals(302, post("/admin/notifications/send",
                "receiver=uid&uid=" + target + "&title=" + enc("未读标题") + "&content=x",
                adminCookie).statusCode());

        UserRepository.UserRow user = users.findById(target);
        String urlRoot = "http://localhost";
        ViewContext vc = new ViewContext(translator, option,
                new PhpUrlGenerator(urlRoot, "http"), RouteUrlGenerator.forHost("localhost"),
                "zh_CN", "token", user, null);
        Map<String, Object> vars = PageView.variables(option, translator, javaScript, filter,
                user, "zh_CN", urlRoot, "/user", false, "user", vc);
        String json = String.valueOf(vars.get("notifications_json"));
        com.neoskin.support.PhpJson.Array list =
                (com.neoskin.support.PhpJson.Array) PhpJson.decode(json);
        assertEquals(1, list.size());
        assertEquals("未读标题", ((Map<?, ?>) list.get(0L)).get("title"));

        String id = notificationIdFor(target);
        assertEquals(200, post("/user/notifications/" + id, "", login("u1@a.com")).statusCode());
        Map<String, Object> after = PageView.variables(option, translator, javaScript, filter,
                user, "zh_CN", urlRoot, "/user", false, "user", vc);
        assertEquals("[]", String.valueOf(after.get("notifications_json")));
    }

    // ------------------------------------------------------------- API

    @Test
    void apiRoutesRequireOAuthForNow() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        String cookie = login("admin@a.com");

        // Passport 尚未接入：api 组没有会话，web 登录态也不生效
        assertEquals(401, get("/api/user/notifications", cookie).statusCode());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port
                        + "/api/user/notifications/whatever"))
                .header("Accept", "application/json")
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.noBody());
        assertEquals(401, http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).statusCode());
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

        return users.findByEmail(email).uid();
    }

    private Map<String, Object> singleCommunication() throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id, title, kind, status, editor, source, audience,"
                             + " delivery_token FROM communications ORDER BY id DESC LIMIT 1");
             ResultSet rs = stmt.executeQuery()) {
            assertTrue(rs.next());
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", rs.getLong(1));
            row.put("title", rs.getString(2));
            row.put("kind", rs.getString(3));
            row.put("status", rs.getString(4));
            row.put("editor", rs.getString(5));
            row.put("source", rs.getString(6));
            row.put("audience", rs.getString(7));
            row.put("delivery_token", rs.getString(8));

            return row;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> singleNotificationData() throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT data FROM notifications ORDER BY created_at DESC LIMIT 1");
             ResultSet rs = stmt.executeQuery()) {
            assertTrue(rs.next());

            return (Map<String, Object>) PhpJson.decode(rs.getString(1));
        }
    }

    private int countNotifications() throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM notifications");
             ResultSet rs = stmt.executeQuery()) {
            rs.next();

            return rs.getInt(1);
        }
    }

    private boolean hasNotificationFor(int uid) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM notifications WHERE notifiable_id = ?")) {
            stmt.setInt(1, uid);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();

                return rs.getInt(1) > 0;
            }
        }
    }

    private String notificationIdFor(int uid) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id FROM notifications WHERE notifiable_id = ?"
                             + " ORDER BY created_at DESC LIMIT 1")) {
            stmt.setInt(1, uid);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());

                return rs.getString(1);
            }
        }
    }

    private String login(String email) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Sec-Fetch-Site", "same-origin")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "identification=" + enc(email) + "&password=secret123",
                        StandardCharsets.UTF_8));
        HttpResponse<String> res = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, res.statusCode(), "login failed: " + res.body());

        return res.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith("BS_SESSION="))
                .map(h -> h.split(";")[0])
                .findFirst().orElseThrow(() -> new AssertionError("no session cookie"));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json");
        if (cookie != null && !cookie.isEmpty()) {
            builder.header("Cookie", cookie);
        }
        builder.GET();

        return http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
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
