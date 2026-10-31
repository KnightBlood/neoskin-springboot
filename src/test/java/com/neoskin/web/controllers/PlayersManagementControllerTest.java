package com.neoskin.web.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import com.neoskin.db.TextureRepository;
import com.neoskin.db.UserRepository;
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.LoginFailCounter;
import com.neoskin.service.Option;
import com.neoskin.service.cipher.BcryptCipher;
import com.neoskin.support.PhpDate;
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
 * {@link PlayersManagementController} 的端到端测试。
 *
 * <p>锁定：列表 JSON（含 {@code skin}/{@code cape} 别名）、改名规则与唯一性、
 * 转移归属、设置纹理（含不存在 tid / tid=0）、删除，以及基于归属用户权限的闭包。</p>
 */
class PlayersManagementControllerTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private UserRepository users;
    private PlayerRepository players;
    private TextureRepository textures;
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
        textures = new TextureRepository(ds);
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
        HandlerResolver management = PlayersManagementController.resolver(option, translator,
                players, users, textures, ds, "Asia/Shanghai");
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
    void listReturnsPlayerJsonWithSearchAliases() throws Exception {
        int admin = seedUser("admin@a.com", UserRepository.ADMIN);
        int owner = seedUser("owner@a.com", UserRepository.NORMAL);
        int pid = players.insert(owner, "Notch", now());
        players.insert(owner, "jeb_", now());
        int tid = (int) textures.insert("skin", "steve", "h1", 10, owner, true, now());
        players.updateTextures(pid, tid, 0, now());
        String cookie = login("admin@a.com");

        HttpResponse<String> res = send("GET", "/admin/players/list", null, cookie);
        assertEquals(200, res.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        assertEquals(2, ((Number) body.get("total")).intValue());
        assertEquals(1, ((Number) body.get("last_page")).intValue());

        PhpJson.Array data = (PhpJson.Array) body.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) data.get(0L);
        assertTrue(first.containsKey("pid") && first.containsKey("tid_skin")
                && first.containsKey("tid_cape") && first.containsKey("last_modified"));
        assertTrue(first.get("pid") instanceof Number);
        assertTrue(first.get("tid_cape") instanceof Number);

        // skin=… 别名命中 tid_skin（搜索串正则键）
        @SuppressWarnings("unchecked")
        Map<String, Object> filtered = (Map<String, Object>) PhpJson.decode(
                send("GET", "/admin/players/list?q=skin=" + tid, null, cookie).body());
        assertEquals(1, ((Number) filtered.get("total")).intValue());

        // 全文搜索 name
        @SuppressWarnings("unchecked")
        Map<String, Object> byName = (Map<String, Object>) PhpJson.decode(
                send("GET", "/admin/players/list?q=Notch", null, cookie).body());
        assertEquals(1, ((Number) byName.get("total")).intValue());
    }

    @Test
    void listPaginatesByTen() throws Exception {
        int admin = seedUser("admin@a.com", UserRepository.ADMIN);
        int owner = seedUser("owner@a.com", UserRepository.NORMAL);
        for (int i = 0; i < 11; i++) {
            players.insert(owner, "p" + i, now());
        }
        String cookie = login("admin@a.com");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(
                send("GET", "/admin/players/list?page=2", null, cookie).body());
        assertEquals(2, ((Number) body.get("current_page")).intValue());
        assertEquals(2, ((Number) body.get("last_page")).intValue());
        assertEquals(1, ((PhpJson.Array) body.get("data")).size());
    }

    // ------------------------------------------------------------- name

    @Test
    void renameValidatesPlayerNameRules() throws Exception {
        int admin = seedUser("admin@a.com", UserRepository.ADMIN);
        int pid = players.insert(admin, "OldName", now());
        players.insert(admin, "Taken", now());
        String cookie = login("admin@a.com");

        // 空 → 422
        assertEquals(422, send("PUT", "/admin/players/" + pid + "/name",
                form(Map.of("player_name", "")), cookie).statusCode());
        // official 规则：不允许空格 → 422
        assertEquals(422, send("PUT", "/admin/players/" + pid + "/name",
                form(Map.of("player_name", "bad name")), cookie).statusCode());
        // 太短（min=3） → 422
        assertEquals(422, send("PUT", "/admin/players/" + pid + "/name",
                form(Map.of("player_name", "ab")), cookie).statusCode());
        // 重名 → 422
        assertEquals(422, send("PUT", "/admin/players/" + pid + "/name",
                form(Map.of("player_name", "Taken")), cookie).statusCode());

        HttpResponse<String> ok = send("PUT", "/admin/players/" + pid + "/name",
                form(Map.of("player_name", "NewName")), cookie);
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals(0, code(ok));
        assertEquals("NewName", players.findByPid(pid).name());
    }

    // ------------------------------------------------------------ owner

    @Test
    void transferOwner() throws Exception {
        int admin = seedUser("admin@a.com", UserRepository.ADMIN);
        int owner = seedUser("owner@a.com", UserRepository.NORMAL);
        int newOwner = seedUser("new@a.com", UserRepository.NORMAL);
        int pid = players.insert(owner, "Notch", now());
        String cookie = login("admin@a.com");

        // 不存在的用户 → 200 code=1
        HttpResponse<String> missing = send("PUT", "/admin/players/" + pid + "/owner",
                form(Map.of("uid", "9999")), cookie);
        assertEquals(200, missing.statusCode());
        assertEquals(1, code(missing));
        assertEquals(owner, players.findByPid(pid).uid());

        // 非整数 → 422
        assertEquals(422, send("PUT", "/admin/players/" + pid + "/owner",
                form(Map.of("uid", "abc")), cookie).statusCode());

        HttpResponse<String> ok = send("PUT", "/admin/players/" + pid + "/owner",
                form(Map.of("uid", String.valueOf(newOwner))), cookie);
        assertEquals(200, ok.statusCode());
        assertEquals(0, code(ok));
        assertEquals(newOwner, players.findByPid(pid).uid());
    }

    // ---------------------------------------------------------- texture

    @Test
    void setTextureValidatesTypAndExistence() throws Exception {
        int admin = seedUser("admin@a.com", UserRepository.ADMIN);
        int pid = players.insert(admin, "Notch", now());
        int tid = (int) textures.insert("skin", "steve", "h1", 10, admin, true, now());
        String cookie = login("admin@a.com");

        // type 非法 → 422
        assertEquals(422, send("PUT", "/admin/players/" + pid + "/textures",
                form(Map.of("tid", String.valueOf(tid), "type", "hat")), cookie).statusCode());
        // 纹理不存在 → 200 code=1
        HttpResponse<String> missing = send("PUT", "/admin/players/" + pid + "/textures",
                form(Map.of("tid", "9999", "type", "skin")), cookie);
        assertEquals(200, missing.statusCode());
        assertEquals(1, code(missing));
        // 设置皮肤
        assertEquals(200, send("PUT", "/admin/players/" + pid + "/textures",
                form(Map.of("tid", String.valueOf(tid), "type", "skin")), cookie).statusCode());
        assertEquals(tid, players.findByPid(pid).tidSkin());
        // tid=0 允许清空
        assertEquals(200, send("PUT", "/admin/players/" + pid + "/textures",
                form(Map.of("tid", "0", "type", "skin")), cookie).statusCode());
        assertEquals(0, players.findByPid(pid).tidSkin());
    }

    // ------------------------------------------------------------ delete

    @Test
    void deleteRemovesPlayer() throws Exception {
        int admin = seedUser("admin@a.com", UserRepository.ADMIN);
        int pid = players.insert(admin, "Notch", now());
        String cookie = login("admin@a.com");

        HttpResponse<String> res = send("DELETE", "/admin/players/" + pid, null, cookie);
        assertEquals(200, res.statusCode());
        assertEquals(0, code(res));
        assertNull(players.findByPid(pid));
    }

    // ------------------------------------------------------------ 守卫

    @Test
    void ownerPermissionGuard() throws Exception {
        int admin = seedUser("admin@a.com", UserRepository.ADMIN);
        int otherAdmin = seedUser("admin2@a.com", UserRepository.ADMIN);
        int normal = seedUser("normal@a.com", UserRepository.NORMAL);
        int adminPid = players.insert(otherAdmin, "OwnedByAdmin", now());
        int normalPid = players.insert(normal, "OwnedByUser", now());
        String cookie = login("admin@a.com");

        // 同权限的别人的角色 → 403 code=1
        HttpResponse<String> denied = send("PUT", "/admin/players/" + adminPid + "/name",
                form(Map.of("player_name", "Hacked")), cookie);
        assertEquals(403, denied.statusCode());
        assertEquals(1, code(denied));

        // 低权限用户的角色 → 允许
        assertEquals(200, send("PUT", "/admin/players/" + normalPid + "/name",
                form(Map.of("player_name", "Renamed")), cookie).statusCode());

        // 不存在 → 404
        assertEquals(404, send("PUT", "/admin/players/99999/name",
                form(Map.of("player_name", "Ghost")), cookie).statusCode());

        // 普通用户 → 403（role:admin）
        String userCookie = login("normal@a.com");
        assertEquals(403, send("GET", "/admin/players/list", null, userCookie).statusCode());
        // 未登录 → 302
        assertEquals(302, send("GET", "/admin/players/list", null, "").statusCode());
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

    private static String now() {
        return PhpDate.now("Asia/Shanghai");
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
