package com.neoskin.web.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * {@link UsersManagementController} 的端到端测试（真实 Vert.x + SQLite + 登录会话）。
 *
 * <p>锁定：分页与搜索串、邮箱唯一（忽略自己）、昵称/积分/密码校验、权限规则
 * （不能改自己 / 只有超级管理员能授予 admin / 不能操作同级）、验证开关、
 * 删除级联角色，以及 admin 门控。</p>
 */
class UsersManagementControllerTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private UserRepository users;
    private PlayerRepository players;
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
        users = new UserRepository(ds);
        players = new PlayerRepository(ds);
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
        HandlerResolver management = UsersManagementController.resolver(option, translator,
                users, cipher, "", ds);
        HandlerResolver resolver = new HandlerResolver() {
            @Override
            public Handler<RoutingContext> resolve(String action) {
                Handler<RoutingContext> handler = auth.resolve(action);

                return handler != null ? handler : management.resolve(action);
            }

            @Override
            public Handler<RoutingContext> resolve(String action, String uri) {
                Handler<RoutingContext> handler = auth.resolve(action, uri);

                return handler != null ? handler : management.resolve(action, uri);
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

    // ------------------------------------------------------------- list

    @Test
    void listReturnsPaginatorWithoutSecrets() throws Exception {
        seedUser("admin@a.com", "Admin", UserRepository.ADMIN);
        seedUser("u1@a.com", "User One", UserRepository.NORMAL);
        String cookie = login("admin@a.com");

        HttpResponse<String> res = send("GET", "/admin/users/list", null, cookie);
        assertEquals(200, res.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        assertEquals(1, ((Number) body.get("current_page")).intValue());
        assertEquals(10, ((Number) body.get("per_page")).intValue());
        assertEquals(2, ((Number) body.get("total")).intValue());
        assertEquals(1, ((Number) body.get("last_page")).intValue());

        PhpJson.Array data = (PhpJson.Array) body.get("data");
        assertEquals(2, data.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) data.get(0L);
        assertFalse(row.containsKey("password"), "password 必须隐藏");
        assertTrue(row.containsKey("email") && row.containsKey("nickname")
                && row.containsKey("permission") && row.containsKey("verified")
                && row.containsKey("register_at"));
        assertTrue(row.get("uid") instanceof Number);
        assertTrue(row.get("verified") instanceof Boolean);
    }

    @Test
    void listFiltersByKeywordAndFallsBackWhenInvalid() throws Exception {
        seedUser("admin@a.com", "Admin", UserRepository.ADMIN);
        seedUser("alice@a.com", "Alice", UserRepository.NORMAL);
        seedUser("bob@b.com", "Bob", UserRepository.NORMAL);
        String cookie = login("admin@a.com");

        String filtered = send("GET", "/admin/users/list?q=alice", null, cookie).body();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(filtered);
        PhpJson.Array data = (PhpJson.Array) body.get("data");
        assertEquals(1, data.size());
        assertEquals("alice@a.com", ((Map<?, ?>) data.get(0L)).get("email"));

        // 语法错误 → fail=all-results，不加过滤
        String invalid = send("GET", "/admin/users/list?q=foo:bar", null, cookie).body();
        @SuppressWarnings("unchecked")
        Map<String, Object> invalidBody = (Map<String, Object>) PhpJson.decode(invalid);
        assertEquals(3, ((Number) invalidBody.get("total")).intValue());
    }

    @Test
    void listPaginatesByTen() throws Exception {
        seedUser("admin@a.com", "Admin", UserRepository.ADMIN);
        for (int i = 1; i <= 11; i++) {
            seedUser("u" + i + "@a.com", "User " + i, UserRepository.NORMAL);
        }
        String cookie = login("admin@a.com");

        String page2 = send("GET", "/admin/users/list?page=2", null, cookie).body();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(page2);
        assertEquals(2, ((Number) body.get("current_page")).intValue());
        assertEquals(2, ((Number) body.get("last_page")).intValue());
        assertEquals(2, ((PhpJson.Array) body.get("data")).size());
    }

    // ------------------------------------------------------------ email

    @Test
    void emailValidatesUniqueIgnoringSelf() throws Exception {
        seedUser("admin@a.com", "Admin", UserRepository.ADMIN);
        int uid = seedUser("target@a.com", "Target", UserRepository.NORMAL);
        String cookie = login("admin@a.com");

        HttpResponse<String> ok = send("PUT", "/admin/users/" + uid + "/email",
                form(Map.of("email", "new@a.com")), cookie);
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals(0, code(ok));
        assertEquals("new@a.com", users.findById(uid).email());

        // 改成自己当前的邮箱 → unique 忽略自己 → 通过
        assertEquals(200, send("PUT", "/admin/users/" + uid + "/email",
                form(Map.of("email", "new@a.com")), cookie).statusCode());

        // 与别人的邮箱冲突 → 422
        HttpResponse<String> conflict = send("PUT", "/admin/users/" + uid + "/email",
                form(Map.of("email", "admin@a.com")), cookie);
        assertEquals(422, conflict.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(conflict.body());
        assertTrue(((Map<?, ?>) body.get("errors")).containsKey("email"));

        // 非邮箱格式 → 422
        assertEquals(422, send("PUT", "/admin/users/" + uid + "/email",
                form(Map.of("email", "not-an-email")), cookie).statusCode());
    }

    // --------------------------------------------------------- nickname

    @Test
    void nicknameRequiresValue() throws Exception {
        seedUser("admin@a.com", "Admin", UserRepository.ADMIN);
        int uid = seedUser("target@a.com", "Target", UserRepository.NORMAL);
        String cookie = login("admin@a.com");

        assertEquals(422, send("PUT", "/admin/users/" + uid + "/nickname",
                form(Map.of("nickname", "")), cookie).statusCode());

        HttpResponse<String> ok = send("PUT", "/admin/users/" + uid + "/nickname",
                form(Map.of("nickname", "新昵称")), cookie);
        assertEquals(200, ok.statusCode());
        assertEquals(0, code(ok));
        assertTrue(String.valueOf(((Map<?, ?>) PhpJson.decode(ok.body())).get("message"))
                .contains("新昵称"));
        assertEquals("新昵称", users.findById(uid).nickname());
    }

    // --------------------------------------------------------- password

    @Test
    void passwordLengthAndHashing() throws Exception {
        seedUser("admin@a.com", "Admin", UserRepository.ADMIN);
        int uid = seedUser("target@a.com", "Target", UserRepository.NORMAL);
        String cookie = login("admin@a.com");

        assertEquals(422, send("PUT", "/admin/users/" + uid + "/password",
                form(Map.of("password", "short")), cookie).statusCode());

        HttpResponse<String> ok = send("PUT", "/admin/users/" + uid + "/password",
                form(Map.of("password", "new-secret")), cookie);
        assertEquals(200, ok.statusCode());
        assertEquals(0, code(ok));

        String hash = users.findById(uid).password();
        assertTrue(cipher.verify("new-secret", hash, ""));

        // 用新密码登录（完整链路）
        assertEquals(200, loginResponse("target@a.com", "new-secret").statusCode());
    }

    // ------------------------------------------------------------ score

    @Test
    void scoreMustBeInteger() throws Exception {
        seedUser("admin@a.com", "Admin", UserRepository.ADMIN);
        int uid = seedUser("target@a.com", "Target", UserRepository.NORMAL);
        String cookie = login("admin@a.com");

        assertEquals(422, send("PUT", "/admin/users/" + uid + "/score",
                form(Map.of("score", "abc")), cookie).statusCode());

        HttpResponse<String> ok = send("PUT", "/admin/users/" + uid + "/score",
                form(Map.of("score", "-50")), cookie);
        assertEquals(200, ok.statusCode());
        assertEquals(0, code(ok));
        assertEquals(-50, users.findById(uid).score());
    }

    // ------------------------------------------------------- permission

    @Test
    void permissionRules() throws Exception {
        seedUser("admin@a.com", "Admin", UserRepository.ADMIN);
        int target = seedUser("target@a.com", "Target", UserRepository.NORMAL);
        String cookie = login("admin@a.com");
        int adminUid = users.findByEmail("admin@a.com").uid();

        // 不能改自己
        HttpResponse<String> self = send("PUT", "/admin/users/" + adminUid + "/permission",
                form(Map.of("permission", "0")), cookie);
        assertEquals(403, self.statusCode());
        assertEquals(1, code(self));

        // 普通管理员不能授予 admin
        HttpResponse<String> grant = send("PUT", "/admin/users/" + target + "/permission",
                form(Map.of("permission", "1")), cookie);
        assertEquals(403, grant.statusCode());
        assertEquals(1, code(grant));

        // 普通管理员不能操作同级管理员
        int otherAdmin = seedUser("admin2@a.com", "Admin 2", UserRepository.ADMIN);
        assertEquals(403, send("PUT", "/admin/users/" + otherAdmin + "/permission",
                form(Map.of("permission", "0")), cookie).statusCode());

        // 设置 banned 可以
        assertEquals(200, send("PUT", "/admin/users/" + target + "/permission",
                form(Map.of("permission", "-1")), cookie).statusCode());
        assertEquals(UserRepository.BANNED, users.findById(target).permission());
    }

    @Test
    void superAdminCanGrantAdmin() throws Exception {
        seedUser("root@a.com", "Root", UserRepository.SUPER_ADMIN);
        int target = seedUser("target@a.com", "Target", UserRepository.NORMAL);
        String cookie = login("root@a.com");

        HttpResponse<String> res = send("PUT", "/admin/users/" + target + "/permission",
                form(Map.of("permission", "1")), cookie);
        assertEquals(200, res.statusCode(), res.body());
        assertEquals(UserRepository.ADMIN, users.findById(target).permission());
    }

    // ------------------------------------------------- verification/delete

    @Test
    void verificationToggles() throws Exception {
        seedUser("admin@a.com", "Admin", UserRepository.ADMIN);
        int uid = seedUser("target@a.com", "Target", UserRepository.NORMAL);
        String cookie = login("admin@a.com");
        assertFalse(users.findById(uid).verified());

        assertEquals(200, send("PUT", "/admin/users/" + uid + "/verification", "", cookie)
                .statusCode());
        assertTrue(users.findById(uid).verified());

        assertEquals(200, send("PUT", "/admin/users/" + uid + "/verification", "", cookie)
                .statusCode());
        assertFalse(users.findById(uid).verified());
    }

    @Test
    void deleteRemovesUserAndPlayers() throws Exception {
        seedUser("admin@a.com", "Admin", UserRepository.ADMIN);
        int uid = seedUser("target@a.com", "Target", UserRepository.NORMAL);
        players.insert(uid, "Notch");
        assertEquals(1, players.countByUser(uid));
        String cookie = login("admin@a.com");

        HttpResponse<String> res = send("DELETE", "/admin/users/" + uid, null, cookie);
        assertEquals(200, res.statusCode());
        assertEquals(0, code(res));
        assertNull(users.findById(uid));
        assertEquals(0, players.countByUser(uid));
    }

    // ------------------------------------------------------------- 权限

    @Test
    void missingTargetIs404AndNormalUserIsForbidden() throws Exception {
        seedUser("admin@a.com", "Admin", UserRepository.ADMIN);
        seedUser("user@a.com", "User", UserRepository.NORMAL);
        String adminCookie = login("admin@a.com");

        assertEquals(404, send("PUT", "/admin/users/99999/email",
                form(Map.of("email", "x@a.com")), adminCookie).statusCode());

        String userCookie = login("user@a.com");
        assertEquals(403, send("GET", "/admin/users/list", null, userCookie).statusCode());

        HttpResponse<String> guest = send("GET", "/admin/users/list", null, "");
        assertEquals(302, guest.statusCode());
        assertNotNull(guest.headers().firstValue("Location").orElse(null));
    }

    // ================================================================ 工具

    private int seedUser(String email, String nickname, int permission) throws Exception {
        String hash = cipher.hash("secret123", "");
        String sql = "INSERT INTO users (`email`, `nickname`, `score`, `avatar`, `password`,"
                + " `ip`, `permission`, `last_sign_at`, `register_at`)"
                + " VALUES (?, ?, 0, 0, ?, '127.0.0.1', ?,"
                + " '2026-01-01 00:00:00', '2026-01-01 00:00:00')";
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            stmt.setString(2, nickname);
            stmt.setString(3, hash);
            stmt.setInt(4, permission);
            stmt.executeUpdate();
        }

        return users.findByEmail(email).uid();
    }

    private String login(String email) throws Exception {
        HttpResponse<String> res = loginResponse(email, "secret123");
        assertEquals(200, res.statusCode(), "login failed: " + res.body());

        return res.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith("BS_SESSION="))
                .map(h -> h.split(";")[0])
                .findFirst().orElseThrow(() -> new AssertionError("no session cookie"));
    }

    private HttpResponse<String> loginResponse(String email, String password) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Sec-Fetch-Site", "same-origin")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "identification=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        return http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

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

    private static int code(HttpResponse<String> response) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(response.body());

        return ((Number) body.get("code")).intValue();
    }

    private HttpResponse<String> send(String method, String path, String body, String cookie)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Sec-Fetch-Site", "same-origin");
        if (cookie != null && !cookie.isEmpty()) {
            builder.header("Cookie", cookie);
        }
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

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
