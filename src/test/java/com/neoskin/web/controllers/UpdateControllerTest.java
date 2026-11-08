package com.neoskin.web.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * {@link UpdateController} 的端到端测试。
 *
 * <p>用本地 Vert.x HTTP 服务模拟更新源，覆盖：更新页三种状态（可更新 / 已最新 /
 * 连接错误）、完整包下载+SHA-256+版本校验+安装（替换/备份/installed.json）、
 * 上传 ZIP、安装 id 不匹配 403、超级管理员门控。</p>
 */
class UpdateControllerTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private Vertx sourceVertx;
    private int port;
    private int sourcePort;
    private HikariDataSource ds;
    private Option option;
    private AppPaths paths;
    private BcryptCipher cipher;

    /** 模拟更新源的可变状态。 */
    private String sourceLatest = "6.0.12_patch";
    private String sourcePackage = "full";
    private String sourceSha;
    private byte[] sourceZip;
    private int sourceStatus = 200;
    private int sourceSpec = 2;

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
        settings.setVersion("6.0.11_patch");
        SchemaInstaller schema = new SchemaInstaller(ds, settings);
        schema.install();

        paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());
        Files.createDirectories(dir.resolve("storage/app"));
        Files.createFile(paths.installLock());
        Files.writeString(dir.resolve("config-app-target.txt"), "old");

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

        HandlerResolver auth = AuthController.resolver(option, translator, javaScript, filter,
                new LoginFailCounter(), users, players, cipher, "", false);
        HandlerResolver update = UpdateController.resolver(option, translator, users, settings,
                paths, javaScript, filter, false);
        HandlerResolver resolver = new HandlerResolver() {
            @Override
            public Handler<RoutingContext> resolve(String action) {
                Handler<RoutingContext> handler = auth.resolve(action);

                return handler != null ? handler : update.resolve(action);
            }

            @Override
            public Handler<RoutingContext> resolve(String action, String uri) {
                Handler<RoutingContext> handler = auth.resolve(action, uri);

                return handler != null ? handler : update.resolve(action, uri);
            }
        };

        VertxRouteRegistry.BuildResult built = VertxRouteRegistry.build(
                routes, resolver, vertx, List.of(), registry);
        port = listen(built.router());

        sourceZip = buildPackageZip("6.0.12_patch");
        sourceSha = sha256(sourceZip);

        // 更新源使用独立的 Vertx 实例：主实例的事件循环在 HttpClient.send 期间被阻塞，
        // 同一实例上的更新源无法响应（测试会等 8s 超时）。
        sourceVertx = Vertx.vertx();
        var sourceServer = sourceVertx.createHttpServer().requestHandler(req -> {
            String path = req.path();
            if ("/update.json".equals(path)) {
                if (sourceStatus != 200) {
                    req.response().setStatusCode(sourceStatus).end();
                    return;
                }
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("spec", sourceSpec);
                info.put("latest", sourceLatest);
                info.put("php", "8.5.0");
                info.put("package", sourcePackage);
                info.put("sha256", sourceSha);
                info.put("url", "http://127.0.0.1:" + sourcePort + "/package.zip");
                req.response().putHeader("Content-Type", "application/json")
                        .end(Buffer.buffer(PhpJson.encode(info, 0), "UTF-8"));
                return;
            }
            if ("/package.zip".equals(path)) {
                req.response().putHeader("Content-Type", "application/zip")
                        .end(Buffer.buffer(sourceZip));
                return;
            }
            req.response().setStatusCode(404).end();
        });
        CompletableFuture<Integer> sourceFuture = new CompletableFuture<>();
        sourceServer.listen(0).onSuccess(s -> sourceFuture.complete(s.actualPort()))
                .onFailure(sourceFuture::completeExceptionally);
        sourcePort = sourceFuture.get(5, TimeUnit.SECONDS);
        option.set("update_source", "http://127.0.0.1:" + sourcePort + "/update.json");
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

    // ------------------------------------------------------------- page

    @Test
    void pageShowsUpdateAvailable() throws Exception {
        seedUser("root@a.com", UserRepository.SUPER_ADMIN);
        String cookie = login("root@a.com");

        HttpResponse<String> res = get("/admin/update", cookie);
        assertEquals(200, res.statusCode(), res.body());
        String html = res.body();
        assertTrue(html.contains("v6.0.12_patch"), "最新版本");
        assertTrue(html.contains("v6.0.11_patch"), "当前版本");
        assertTrue(html.contains("ZIP 运行包更新"));
        assertTrue(html.contains("当前版本更新日志"));
        assertTrue(html.contains("6.0.12_patch") && html.contains("6.0.3_patch"),
                "版本更新日志按新到旧");
    }

    @Test
    void pageShowsUpToDate() throws Exception {
        sourceLatest = "6.0.11_patch";
        seedUser("root@a.com", UserRepository.SUPER_ADMIN);
        String cookie = login("root@a.com");

        String html = get("/admin/update", cookie).body();
        assertTrue(html.contains("已更新至最新版本"), html);
    }

    @Test
    void pageShowsSourceError() throws Exception {
        sourceStatus = 500;
        seedUser("root@a.com", UserRepository.SUPER_ADMIN);
        String cookie = login("root@a.com");

        String html = get("/admin/update", cookie).body();
        assertTrue(html.contains("500"), html);
    }

    // ---------------------------------------------------------- download

    @Test
    void downloadFullPackageThenInstall() throws Exception {
        seedUser("root@a.com", UserRepository.SUPER_ADMIN);
        String cookie = login("root@a.com");

        HttpResponse<String> download = post("/admin/update/download", "", cookie);
        assertEquals(200, download.statusCode(), download.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(download.body());
        assertEquals(0, ((Number) body.get("code")).intValue(), download.body());
        assertEquals(true, ((Map<?, ?>) body.get("data")).get("review"));

        // 页面显示待安装运行包
        HttpResponse<String> page = get("/admin/update", cookie);
        assertEquals(200, page.statusCode(), page.body());
        String html = page.body();
        assertTrue(html.contains("目标版本"), html);
        assertTrue(html.contains("6.0.12_patch"), html);

        String id = pendingPackageId(cookie);
        assertEquals(32, id.length());

        HttpResponse<String> install = post("/admin/update/install", "id=" + id, cookie);
        assertEquals(302, install.statusCode(), install.body());

        // Java 部署：PHP 运行包只部署前端产物与翻译；程序文件跳过
        Path asset = dir.resolve("public/app/app.js");
        assertTrue(Files.isRegularFile(asset), "public/app 必须更新");
        assertEquals("console.log(1)", Files.readString(asset));
        assertTrue(Files.notExists(dir.resolve("app/Test.php")), "PHP 程序文件必须跳过");
        assertTrue(Files.notExists(dir.resolve("artisan")), "artisan 必须跳过");
        assertTrue(Files.notExists(dir.resolve("vendor/autoload.php")), "vendor 必须跳过");

        // installed.json
        Path taskDir = paths.storage("app").resolve("system-updates").resolve(id);
        assertTrue(Files.isRegularFile(taskDir.resolve("installed.json")));

        // 安装后 session 清空
        String after = get("/admin/update", cookie).body();
        assertTrue(!after.contains("目标版本"));
    }

    @Test
    void installFromSpaJsonUsesPendingId() throws Exception {
        seedUser("root@a.com", UserRepository.SUPER_ADMIN);
        String cookie = login("root@a.com");

        assertEquals(200, post("/admin/update/download", "", cookie).statusCode());

        HttpResponse<String> install = postJson("/admin/update/install", "{}", cookie);
        assertEquals(200, install.statusCode(), install.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(install.body());
        assertEquals(0, ((Number) body.get("code")).intValue(), install.body());
        assertTrue(String.valueOf(body.get("message")).contains("已更新"));
    }

    @Test
    void downloadRejectsBadChecksumAndVersion() throws Exception {
        seedUser("root@a.com", UserRepository.SUPER_ADMIN);
        String cookie = login("root@a.com");

        sourceSha = "0".repeat(64);
        @SuppressWarnings("unchecked")
        Map<String, Object> bad = (Map<String, Object>) PhpJson.decode(
                post("/admin/update/download", "", cookie).body());
        assertEquals(1, ((Number) bad.get("code")).intValue());
        assertTrue(String.valueOf(bad.get("message")).contains("校验值"));

        // 版本不一致：包是 6.0.12，信息说 6.0.13
        sourceSha = sha256(sourceZip);
        sourceLatest = "6.0.13_patch";
        @SuppressWarnings("unchecked")
        Map<String, Object> mismatch = (Map<String, Object>) PhpJson.decode(
                post("/admin/update/download", "", cookie).body());
        assertEquals(1, ((Number) mismatch.get("code")).intValue());
        assertTrue(String.valueOf(mismatch.get("message")).contains("版本"));
    }

    // ------------------------------------------------------------ upload

    @Test
    void uploadZipPreparesPackage() throws Exception {
        seedUser("root@a.com", UserRepository.SUPER_ADMIN);
        String cookie = login("root@a.com");

        HttpResponse<String> res = upload(cookie, "package.zip", sourceZip);
        assertEquals(302, res.statusCode(), res.body());
        assertTrue(res.headers().firstValue("Location").orElse("").endsWith("/admin/update"));

        String html = get("/admin/update", cookie).body();
        assertTrue(html.contains("目标版本"), html);
        assertTrue(html.contains("6.0.12_patch"));
    }

    @Test
    void uploadRejectsNonZipAndBrokenZip() throws Exception {
        seedUser("root@a.com", UserRepository.SUPER_ADMIN);
        String cookie = login("root@a.com");

        assertEquals(302, upload(cookie, "package.txt", sourceZip).statusCode());
        assertTrue(get("/admin/update", cookie).body().contains("请选择 ZIP 运行包"));

        assertEquals(302, upload(cookie, "broken.zip",
                "not a zip".getBytes(StandardCharsets.UTF_8)).statusCode());
        assertTrue(get("/admin/update", cookie).body().contains("运行包"));
    }

    @Test
    void installRejectsMismatchedId() throws Exception {
        seedUser("root@a.com", UserRepository.SUPER_ADMIN);
        String cookie = login("root@a.com");
        assertEquals(200, post("/admin/update/download", "", cookie).statusCode());

        assertEquals(403, post("/admin/update/install", "id=" + "f".repeat(32), cookie)
                .statusCode());
        // size:32 校验失败走 HTML 分支 → 302 回更新页
        assertEquals(302, post("/admin/update/install", "id=short", cookie).statusCode());
    }

    // ------------------------------------------------------------- 权限

    @Test
    void requiresSuperAdmin() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN);
        String admin = login("admin@a.com");
        assertEquals(403, get("/admin/update", admin).statusCode());

        seedUser("root@a.com", UserRepository.SUPER_ADMIN);
        HttpResponse<String> guest = get("/admin/update", "");
        assertEquals(302, guest.statusCode());
    }

    // ================================================================ 工具

    /** 构造一个最小合法运行包（外层带目录前缀）。 */
    private static byte[] buildPackageZip(String version) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            entry(zip, "blessing/composer.json",
                    "{\"name\":\"bs-community/blessing-skin-server\","
                            + "\"require\":{\"php\":\">=8.5.0\"}}");
            entry(zip, "blessing/artisan", "#!/usr/bin/env php");
            entry(zip, "blessing/bootstrap/app.php", "<?php // bootstrap");
            entry(zip, "blessing/config/app.php",
                    "<?php return ['version' => '" + version + "'];");
            entry(zip, "blessing/public/index.php", "<?php // index");
            entry(zip, "blessing/vendor/autoload.php", "<?php // autoload");
            entry(zip, "blessing/resources/views/assets/app.twig", "{{ }}");
            entry(zip, "blessing/public/app/app.js", "console.log(1)");
            entry(zip, "blessing/app/Test.php", "new");
            // 白名单之外：不应被解压
            entry(zip, "blessing/public/textures/evil.png", "evil");
        }

        return out.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content)
            throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }

    private String pendingPackageId(String cookie) throws Exception {
        String html = get("/admin/update", cookie).body();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("name=\"id\" value=\"([a-f0-9]{32})\"").matcher(html);
        assertTrue(matcher.find(), "页面应包含待安装任务 id");

        return matcher.group(1);
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

    private HttpResponse<String> postJson(String path, String body, String cookie)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Neoskin-Spa", "1")
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
        String boundary = "----NeoSkinTest" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"package\"; filename=\""
                + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        body.write("Content-Type: application/zip\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/admin/update/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Sec-Fetch-Site", "same-origin")
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));

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
