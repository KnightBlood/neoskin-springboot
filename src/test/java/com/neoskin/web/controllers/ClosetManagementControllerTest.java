package com.neoskin.web.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.neoskin.db.ClosetRepository;
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
 * {@link ClosetManagementController} 的端到端测试：管理员查看/添加/移除他人衣柜。
 */
class ClosetManagementControllerTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private UserRepository users;
    private TextureRepository textures;
    private ClosetRepository closet;
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
        textures = new TextureRepository(ds);
        closet = new ClosetRepository(ds);
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
        HandlerResolver management = ClosetManagementController.resolver(option, translator,
                users, textures, closet);
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
        // list 只有 api 路由（Passport 未接入前 401）；这里挂一条测试专用路由验证 JSON 结构
        built.router().get("/__closet/:user").handler(management.resolve(
                "App\\Http\\Controllers\\ClosetManagementController@list"));
        port = listen(built.router());
    }

    @AfterEach
    void tearDown() throws Exception {
        ds.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void listReturnsAllClosetItemsWithPivot() throws Exception {
        int admin = seedUser("admin@a.com", UserRepository.ADMIN);
        int target = seedUser("target@a.com", UserRepository.NORMAL);
        int skin = seedTexture(admin, "steve");
        int cape = seedTexture(admin, "cape");
        closet.attach(target, skin, "我的皮肤");
        closet.attach(target, cape, "披风");
        String cookie = login("admin@a.com");

        HttpResponse<String> res = get("/__closet/" + target, cookie);
        assertEquals(200, res.statusCode(), res.body());
        PhpJson.Array list = (PhpJson.Array) PhpJson.decode(res.body());
        assertEquals(2, list.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) list.get(0L);
        assertNotNull(first.get("pivot"));
        assertEquals(skin, ((Number) ((Map<?, ?>) first.get("pivot")).get("texture_tid"))
                .intValue());
        assertEquals("我的皮肤", ((Map<?, ?>) first.get("pivot")).get("item_name"));
        assertFalse(first.containsKey("model"), "Texture::toArray() 不含 model");

        // api 组没有 Passport → 401（web 登录态不生效，与源一致：api 需 OAuth）
        assertEquals(401, get("/api/admin/closet/" + target, cookie).statusCode());
        assertEquals(401, get("/api/admin/closet/" + target, "").statusCode());
    }

    @Test
    void addAndRemoveThroughWebRoutes() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        int target = seedUser("target@a.com", UserRepository.NORMAL);
        int skin = seedTexture(users.findByEmail("admin@a.com").uid(), "steve");
        String cookie = login("admin@a.com");

        // 添加
        HttpResponse<String> added = send("POST", "/admin/closet/" + target,
                form(Map.of("tid", String.valueOf(skin))), cookie);
        assertEquals(200, added.statusCode(), added.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(added.body());
        assertEquals(0, ((Number) body.get("code")).intValue());
        assertEquals("", body.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertEquals(target, ((Number) ((Map<?, ?>) data.get("user")).get("uid")).intValue());
        assertEquals(skin, ((Number) ((Map<?, ?>) data.get("texture")).get("tid")).intValue());
        assertFalse(((Map<?, ?>) data.get("texture")).containsKey("model"),
                "Texture::toArray() 不含 model 访问器");
        assertTrue(closet.exists(target, skin));
        assertEquals("材质steve", closet.find(target, skin).itemName());

        // 重复添加
        @SuppressWarnings("unchecked")
        Map<String, Object> dup = (Map<String, Object>) PhpJson.decode(
                send("POST", "/admin/closet/" + target,
                        form(Map.of("tid", String.valueOf(skin))), cookie).body());
        assertEquals(1, ((Number) dup.get("code")).intValue());

        // 移除
        HttpResponse<String> removed = send("DELETE", "/admin/closet/" + target,
                form(Map.of("tid", String.valueOf(skin))), cookie);
        assertEquals(200, removed.statusCode(), removed.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> removedBody = (Map<String, Object>) PhpJson.decode(removed.body());
        assertEquals(0, ((Number) removedBody.get("code")).intValue());
        assertFalse(closet.exists(target, skin));

        // 再移除 → code 1
        @SuppressWarnings("unchecked")
        Map<String, Object> missing = (Map<String, Object>) PhpJson.decode(
                send("DELETE", "/admin/closet/" + target,
                        form(Map.of("tid", String.valueOf(skin))), cookie).body());
        assertEquals(1, ((Number) missing.get("code")).intValue());
    }

    @Test
    void addUnknownTextureReportsNotFound() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        int target = seedUser("target@a.com", UserRepository.NORMAL);
        String cookie = login("admin@a.com");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(
                send("POST", "/admin/closet/" + target,
                        form(Map.of("tid", "9999")), cookie).body());
        assertEquals(1, ((Number) body.get("code")).intValue());
        assertEquals("该材质不存在", body.get("message"));
    }

    @Test
    void missingUserIs404AndGuestsRedirected() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        String cookie = login("admin@a.com");

        assertEquals(404, send("POST", "/admin/closet/99999",
                form(Map.of("tid", "1")), cookie).statusCode());
        assertEquals(404, send("DELETE", "/admin/closet/99999",
                form(Map.of("tid", "1")), cookie).statusCode());

        seedUser("user@a.com", UserRepository.NORMAL);
        assertEquals(403, send("POST", "/admin/closet/1", form(Map.of("tid", "1")),
                login("user@a.com")).statusCode());
        assertEquals(302, send("POST", "/admin/closet/1", form(Map.of("tid", "1")), "")
                .statusCode());
    }

    // ================================================================ 工具

    private int seedUser(String email, int permission) throws Exception {
        String hash = cipher.hash("secret123", "");
        String sql = "INSERT INTO users (`email`, `nickname`, `score`, `avatar`, `password`,"
                + " `ip`, `permission`, `last_sign_at`, `register_at`, `verified`)"
                + " VALUES (?, ?, 0, 0, ?, '127.0.0.1', ?,"
                + " '2026-01-01 00:00:00', '2026-01-01 00:00:00', 1)";
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

    private int seedTexture(int uploader, String type) throws Exception {
        String hash = "hash" + System.nanoTime();
        long tid = textures.insert("材质" + type, type, hash, 10, uploader, true,
                "2024-01-01 00:00:00");

        return (int) tid;
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

    private HttpResponse<String> send(String method, String path, String body, String cookie)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Sec-Fetch-Site", "same-origin");
        if (cookie != null && !cookie.isEmpty()) {
            builder.header("Cookie", cookie);
        }
        builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body,
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
