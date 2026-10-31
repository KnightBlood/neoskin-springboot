package com.neoskin.web.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
import com.neoskin.service.PluginManager;
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
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@link PluginController} / {@link MarketController} 的端到端测试（清单层）。
 *
 * <p>覆盖：插件数据 JSON、启用/禁用/删除、依赖与冲突拒绝、readme 渲染、
 * config 的 404、ZIP 上传、按 URL 安装、市场列表（installed/can_update/依赖）、
 * 市场安装与错误分支，以及 admin / super-admin 门控。</p>
 */
class PluginControllerTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private Vertx sourceVertx;
    private int port;
    private int sourcePort;
    private HikariDataSource ds;
    private AppPaths paths;
    private Option option;
    private NeoSkinSettings settings;
    private BcryptCipher cipher;
    private byte[] pluginZip;

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
        schema.install();

        paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());
        Files.createFile(paths.installLock());
        Path pluginsDir = dir.resolve("plugins");
        Files.createDirectories(pluginsDir);

        option = new Option(ds, schema, paths);
        Translator translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");
        JavaScript javaScript = new JavaScript(paths, translator, null);
        BlessingFilter filter = new BlessingFilter();
        UserRepository users = new UserRepository(ds);
        TextureRepository textures = new TextureRepository(ds);
        PlayerRepository players = new PlayerRepository(ds);
        cipher = new BcryptCipher(10);
        PluginManager pluginManager = new PluginManager(option, translator, settings, paths);

        // 已安装插件：demo-plugin（旧版本）+ 缺依赖 + 冲突声明
        writePlugin("demo-plugin", "1.0.0", """
                {"name":"demo-plugin","title":"Demo Plugin","description":"Demo Desc",
                 "version":"1.0.0","namespace":"Demo\\\\Plugin",
                 "require":{"blessing-skin-server":">=6.0.0"},
                 "enchants":{"icon":{"fa":"star","bg":"red"}}}
                """, "# Demo\n\nHello **world**\n");
        writePlugin("needs-missing", "0.1.0", """
                {"name":"needs-missing","title":"Needs Missing","version":"0.1.0",
                 "require":{"other-plugin":"^1.0"}}
                """, null);
        writePlugin("conflicting", "0.1.0", """
                {"name":"conflicting","title":"Conflicting","version":"0.1.0",
                 "enchants":{"conflicts":{"demo-plugin":">=1.0.0"}}}
                """, null);

        pluginZip = buildPluginZip("demo-plugin", "1.2.0");

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
        HandlerResolver pluginResolver = PluginController.resolver(option, translator,
                pluginManager, paths, javaScript, filter, users, false);
        HandlerResolver marketResolver = MarketController.resolver(option, translator,
                pluginManager, settings);
        HandlerResolver resolver = new HandlerResolver() {
            @Override
            public Handler<RoutingContext> resolve(String action) {
                Handler<RoutingContext> handler = auth.resolve(action);
                if (handler == null) {
                    handler = pluginResolver.resolve(action);
                }

                return handler != null ? handler : marketResolver.resolve(action);
            }

            @Override
            public Handler<RoutingContext> resolve(String action, String uri) {
                Handler<RoutingContext> handler = auth.resolve(action, uri);
                if (handler == null) {
                    handler = pluginResolver.resolve(action, uri);
                }

                return handler != null ? handler : marketResolver.resolve(action, uri);
            }
        };

        VertxRouteRegistry.BuildResult built = VertxRouteRegistry.build(
                routes, resolver, vertx, List.of(), registry);
        port = listen(built.router(), vertx);

        // 本地插件市场 / 下载源（独立 Vertx 实例避免事件循环互锁）
        sourceVertx = Vertx.vertx();
        var sourceServer = sourceVertx.createHttpServer().requestHandler(req -> {
            String path = req.path();
            if ("/registry_zh_CN.json".equals(path)) {
                Map<String, Object> demo = new HashMap<>();
                demo.put("name", "demo-plugin");
                demo.put("version", "1.2.0");
                demo.put("description", "Market Demo");
                demo.put("require", Map.of("blessing-skin-server", ">=6.0.0"));
                demo.put("dist", Map.of("url", "http://127.0.0.1:" + sourcePort + "/plugin.zip"));
                Map<String, Object> dep = new HashMap<>();
                dep.put("name", "dep-plugin");
                dep.put("version", "2.0.0");
                dep.put("require", Map.of("other-plugin", "^1.0"));
                dep.put("dist", Map.of("url", "http://127.0.0.1:" + sourcePort + "/plugin.zip"));
                req.response().putHeader("Content-Type", "application/json")
                        .end(Buffer.buffer(PhpJson.encode(
                                Map.of("packages", List.of(demo, dep)), 0), "UTF-8"));
                return;
            }
            if ("/plugin.zip".equals(path)) {
                req.response().putHeader("Content-Type", "application/zip")
                        .end(Buffer.buffer(pluginZip));
                return;
            }
            req.response().setStatusCode(404).end();
        });
        CompletableFuture<Integer> sourceFuture = new CompletableFuture<>();
        sourceServer.listen(0).onSuccess(s -> sourceFuture.complete(s.actualPort()))
                .onFailure(sourceFuture::completeExceptionally);
        sourcePort = sourceFuture.get(5, TimeUnit.SECONDS);
        option.set("plugins_registry",
                "http://127.0.0.1:" + sourcePort + "/registry_{lang}.json");
    }

    @AfterEach
    void tearDown() throws Exception {
        ds.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (sourceVertx != null) {
            sourceVertx.close().toCompletionStage().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------- data

    @Test
    void pluginDataShape() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        String cookie = login("admin@a.com");

        HttpResponse<String> res = get("/admin/plugins/data", cookie);
        assertEquals(200, res.statusCode(), res.body());
        PhpJson.Array list = (PhpJson.Array) PhpJson.decode(res.body());
        assertEquals(3, list.size());

        Map<String, Object> demo = byName(list, "demo-plugin");
        assertEquals("Demo Plugin", demo.get("title"));
        assertEquals("Demo Desc", demo.get("description"));
        assertEquals("1.0.0", demo.get("version"));
        assertEquals(false, demo.get("enabled"));
        assertEquals(true, demo.get("readme"));
        assertEquals(false, demo.get("config"));
        @SuppressWarnings("unchecked")
        Map<String, Object> icon = (Map<String, Object>) demo.get("icon");
        assertEquals("star", icon.get("fa"));
        assertEquals("red", icon.get("bg"));

        Map<String, Object> missing = byName(list, "needs-missing");
        @SuppressWarnings("unchecked")
        Map<String, Object> defaultIcon = (Map<String, Object>) missing.get("icon");
        assertEquals("plug", defaultIcon.get("fa"));
        assertEquals("fas", defaultIcon.get("faType"));
        assertEquals("navy", defaultIcon.get("bg"));
    }

    @Test
    void enableDisableAndDelete() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        String cookie = login("admin@a.com");

        assertEquals(0, manage(cookie, "demo-plugin", "enable").get("code"));
        assertTrue(enabledNames().contains("demo-plugin"));
        assertTrue(Boolean.TRUE.equals(byName((PhpJson.Array) PhpJson.decode(
                get("/admin/plugins/data", cookie).body()), "demo-plugin").get("enabled")));

        assertEquals(0, manage(cookie, "demo-plugin", "disable").get("code"));
        assertFalse(enabledNames().contains("demo-plugin"));

        assertEquals(0, manage(cookie, "demo-plugin", "delete").get("code"));
        assertFalse(Files.exists(dir.resolve("plugins/demo-plugin")));

        // 未知插件 / 未知动作
        assertEquals(1, manage(cookie, "nope", "enable").get("code"));
        assertEquals(1, manage(cookie, "needs-missing", "whatever").get("code"));
    }

    @Test
    void enableRejectsMissingDependenciesAndConflicts() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        String cookie = login("admin@a.com");

        Map<String, Object> missing = manage(cookie, "needs-missing", "enable");
        assertEquals(1, missing.get("code"));
        assertTrue(String.valueOf(missing.get("message")).contains("无法启用"));
        @SuppressWarnings("unchecked")
        PhpJson.Array reason = (PhpJson.Array) ((Map<?, ?>) missing.get("data")).get("reason");
        assertTrue(String.valueOf(reason.get(0L)).contains("other-plugin"),
                String.valueOf(reason));

        // 冲突：先启用 demo，再启用 conflicting
        assertEquals(0, manage(cookie, "demo-plugin", "enable").get("code"));
        Map<String, Object> conflict = manage(cookie, "conflicting", "enable");
        assertEquals(1, conflict.get("code"));
        PhpJson.Array conflictReason = (PhpJson.Array) ((Map<?, ?>) conflict.get("data"))
                .get("reason");
        assertTrue(String.valueOf(conflictReason.get(0L)).contains("Demo Plugin"),
                String.valueOf(conflictReason));
    }

    // ----------------------------------------------------------- readme

    @Test
    void readmeRendersMarkdown() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        String cookie = login("admin@a.com");

        HttpResponse<String> res = get("/admin/plugins/readme/demo-plugin", cookie);
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("Demo Plugin"));
        assertTrue(res.body().contains("<strong>world</strong>"), res.body());
        assertTrue(res.body().contains("插件说明") || res.body().contains("readme"));

        assertEquals(404, get("/admin/plugins/readme/nope", cookie).statusCode());
        assertEquals(404, get("/admin/plugins/readme/needs-missing", cookie).statusCode());
    }

    @Test
    void configReturns404WithoutRunnableCode() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        String cookie = login("admin@a.com");
        assertEquals(0, manage(cookie, "demo-plugin", "enable").get("code"));

        // Java 侧无法执行插件 PHP 配置：与「没有配置」一致返回 404
        assertEquals(404, get("/admin/plugins/config/demo-plugin", cookie).statusCode());
    }

    // ----------------------------------------------------------- install

    @Test
    void uploadAndWgetInstallZip() throws Exception {
        seedUser("root@a.com", UserRepository.SUPER_ADMIN);
        String cookie = login("root@a.com");

        // 上传
        HttpResponse<String> upload = upload(cookie, "demo.zip", pluginZip);
        assertEquals(200, upload.statusCode(), upload.body());
        assertEquals(0, ((Number) ((Map<?, ?>) PhpJson.decode(upload.body())).get("code"))
                .intValue());

        // 非 ZIP → 422
        assertEquals(422, upload(cookie, "x.txt",
                "hello".getBytes(StandardCharsets.UTF_8)).statusCode());

        // 按 URL 安装
        HttpResponse<String> wget = post("/admin/plugins/wget",
                "url=" + URLEncoder.encode(
                        "http://127.0.0.1:" + sourcePort + "/plugin.zip",
                        StandardCharsets.UTF_8), cookie);
        assertEquals(200, wget.statusCode(), wget.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> wgetBody = (Map<String, Object>) PhpJson.decode(wget.body());
        assertEquals(0, ((Number) wgetBody.get("code")).intValue());

        // 404 URL → code 1
        @SuppressWarnings("unchecked")
        Map<String, Object> missing = (Map<String, Object>) PhpJson.decode(
                post("/admin/plugins/wget", "url=" + URLEncoder.encode(
                        "http://127.0.0.1:" + sourcePort + "/missing.zip",
                        StandardCharsets.UTF_8), cookie).body());
        assertEquals(1, ((Number) missing.get("code")).intValue());
        // 非法 URL → 422
        assertEquals(422, post("/admin/plugins/wget", "url=notaurl", cookie).statusCode());
    }

    @Test
    void uploadRequiresSuperAdmin() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        String cookie = login("admin@a.com");

        assertEquals(403, upload(cookie, "demo.zip", pluginZip).statusCode());
        assertEquals(403, post("/admin/plugins/wget", "url=https://x.example/p.zip", cookie)
                .statusCode());
    }

    // ------------------------------------------------------------ market

    @Test
    void marketListsInstalledVersions() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        String cookie = login("admin@a.com");

        HttpResponse<String> res = get("/admin/plugins/market/list", cookie);
        assertEquals(200, res.statusCode(), res.body());
        PhpJson.Array list = (PhpJson.Array) PhpJson.decode(res.body());
        assertEquals(2, list.size());

        Map<String, Object> demo = byName(list, "demo-plugin");
        assertEquals("1.0.0", demo.get("installed"));
        assertEquals(true, demo.get("can_update"));
        assertFalse(demo.containsKey("require"), "require 被移除并移入 dependencies");
        @SuppressWarnings("unchecked")
        Map<String, Object> dependencies = (Map<String, Object>) demo.get("dependencies");
        assertNotNull(dependencies.get("all"));
        assertEquals(0, ((Map<?, ?>) dependencies.get("unsatisfied")).size());

        Map<String, Object> dep = byName(list, "dep-plugin");
        assertEquals(false, dep.get("installed"));
        @SuppressWarnings("unchecked")
        Map<String, Object> depDependencies = (Map<String, Object>) dep.get("dependencies");
        @SuppressWarnings("unchecked")
        Map<String, Object> unsatisfied = (Map<String, Object>) depDependencies
                .get("unsatisfied");
        assertTrue(unsatisfied.containsKey("other-plugin"));
    }

    @Test
    void marketDownloadInstallsAndReportsErrors() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        String cookie = login("admin@a.com");

        // 依赖未满足 → code 1 + reason
        @SuppressWarnings("unchecked")
        Map<String, Object> blocked = (Map<String, Object>) PhpJson.decode(
                post("/admin/plugins/market/download", "name=dep-plugin", cookie).body());
        assertEquals(1, ((Number) blocked.get("code")).intValue());
        assertTrue(blocked.get("data") instanceof Map<?, ?>);

        // 正常安装：解压后清单版本变为 1.2.0
        @SuppressWarnings("unchecked")
        Map<String, Object> ok = (Map<String, Object>) PhpJson.decode(
                post("/admin/plugins/market/download", "name=demo-plugin", cookie).body());
        assertEquals(0, ((Number) ok.get("code")).intValue(), String.valueOf(ok));
        Map<String, Object> demo = byName((PhpJson.Array) PhpJson.decode(
                get("/admin/plugins/data", cookie).body()), "demo-plugin");
        assertEquals("1.2.0", demo.get("version"));

        // 未知插件
        @SuppressWarnings("unchecked")
        Map<String, Object> unknown = (Map<String, Object>) PhpJson.decode(
                post("/admin/plugins/market/download", "name=nope", cookie).body());
        assertEquals(1, ((Number) unknown.get("code")).intValue());
    }

    @Test
    void manageIsAdminOnly() throws Exception {
        seedUser("user@a.com", UserRepository.NORMAL);
        String cookie = login("user@a.com");

        assertEquals(403, get("/admin/plugins/data", cookie).statusCode());
        assertEquals(403, post("/admin/plugins/manage", "name=demo-plugin&action=enable",
                cookie).statusCode());
        assertEquals(302, get("/admin/plugins/data", "").statusCode());
    }

    // ================================================================ 工具

    private void writePlugin(String name, String version, String manifest, String readme)
            throws Exception {
        Path pluginDir = dir.resolve("plugins").resolve(name);
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("package.json"), manifest);
        if (readme != null) {
            Files.writeString(pluginDir.resolve("README.md"), readme);
        }
    }

    private static byte[] buildPluginZip(String name, String version) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            entry(zip, name + "/package.json", "{\"name\":\"" + name
                    + "\",\"title\":\"Demo Plugin\",\"version\":\"" + version
                    + "\",\"namespace\":\"Demo\\\\Plugin\","
                    + "\"require\":{\"blessing-skin-server\":\">=6.0.0\"}}");
            entry(zip, name + "/README.md", "# Demo\n\nHello **world**\n");
        }

        return out.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content)
            throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private Map<String, Object> byName(PhpJson.Array list, String name) {
        for (Object item : list.values()) {
            if (item instanceof Map<?, ?> map && name.equals(String.valueOf(map.get("name")))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;

                return typed;
            }
        }
        throw new AssertionError("plugin not found: " + name);
    }

    private String enabledNames() {
        Object raw = option.get("plugins_enabled", "[]", true);
        return raw == null ? "" : String.valueOf(raw);
    }

    private Map<String, Object> manage(String cookie, String name, String action)
            throws Exception {
        HttpResponse<String> res = post("/admin/plugins/manage",
                "name=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                        + "&action=" + action, cookie);
        assertEquals(200, res.statusCode(), res.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        body.put("code", ((Number) body.get("code")).intValue());

        return body;
    }

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

        return new UserRepository(ds).findByEmail(email).uid();
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

    private HttpResponse<String> upload(String cookie, String filename, byte[] content)
            throws Exception {
        String boundary = "----NeoSkinPlugin" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        String mime = filename.endsWith(".zip") ? "application/zip" : "text/plain";
        body.write(("Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/admin/plugins/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Sec-Fetch-Site", "same-origin")
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));

        return http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static int listen(io.vertx.ext.web.Router router, Vertx vertx) {
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
