package com.neoskin.web.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.neoskin.service.ImageCache;
import com.neoskin.service.LoginFailCounter;
import com.neoskin.service.Option;
import com.neoskin.service.cipher.BcryptCipher;
import com.neoskin.service.UpdateSources;
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
 * {@link OptionsController} 的端到端测试。
 *
 * <p>锁定：四个设置页渲染（OptionForm HTML 注入）、POST {@code option=<id>} 的写回
 * 语义（含未勾选 checkbox=false、本地化后缀、site_url/cdn_address 清理）、
 * 更新源校验 + .env 写入 + 预设源切换、{@code ?clear-cache} 清空图片缓存。</p>
 */
class OptionsControllerTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private Option option;
    private NeoSkinSettings settings;
    private Translator translator;
    private ImageCache imageCache;
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
        schema.install();

        AppPaths paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());
        Files.createFile(paths.installLock());

        envFile = dir.resolve(".env");
        Files.writeString(envFile, "APP_ENV=local\nUPDATE_SOURCE=old\n", StandardCharsets.UTF_8);
        System.setProperty("neoskin.env.file", envFile.toString());

        option = new Option(ds, schema, paths);
        translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");
        JavaScript javaScript = new JavaScript(paths, translator, null);
        BlessingFilter filter = new BlessingFilter();
        UserRepository users = new UserRepository(ds);
        TextureRepository textures = new TextureRepository(ds);
        PlayerRepository players = new PlayerRepository(ds);
        imageCache = new ImageCache();
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
        HandlerResolver optionsResolver = OptionsController.resolver(option, translator,
                javaScript, filter, users, false, settings, paths, imageCache);
        HandlerResolver resolver = new HandlerResolver() {
            @Override
            public Handler<RoutingContext> resolve(String action) {
                Handler<RoutingContext> handler = auth.resolve(action);

                return handler != null ? handler : optionsResolver.resolve(action);
            }

            @Override
            public Handler<RoutingContext> resolve(String action, String uri) {
                Handler<RoutingContext> handler = auth.resolve(action, uri);

                return handler != null ? handler : optionsResolver.resolve(action, uri);
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

    // ------------------------------------------------------------- 渲染

    @Test
    void pagesRenderOptionForms() throws Exception {
        String cookie = login(seedAdmin());

        String customize = get("/admin/customize", cookie).body();
        assertTrue(customize.contains("name=\"option\" value=\"homepage\""));
        assertTrue(customize.contains("value=\"customJsCss\""));
        assertTrue(customize.contains("id=\"navbar-color-picker\""));
        assertTrue(customize.contains("id=\"blessing-extra\""));

        String options = get("/admin/options", cookie).body();
        assertTrue(options.contains("value=\"general\""));
        assertTrue(options.contains("value=\"updates\""));
        assertTrue(options.contains("reCAPTCHA"));
        assertTrue(options.contains("前往通知与公告"));

        String score = get("/admin/score", cookie).body();
        assertTrue(score.contains("value=\"rate\""));
        assertTrue(score.contains("value=\"report\""));
        assertTrue(score.contains("value=\"sign\""));
        assertTrue(score.contains("value=\"sharing\""));

        String resource = get("/admin/resource", cookie).body();
        assertTrue(resource.contains("value=\"resources\""));
        assertTrue(resource.contains("value=\"cache\""));
        assertTrue(resource.contains("?clear-cache"));
    }

    // ------------------------------------------------------- customize

    @Test
    void customizeSavesHomepageAndColors() throws Exception {
        String cookie = login(seedAdmin());

        // 预置 true，提交时不勾选 → 必须写回 false
        option.set("hide_intro", "true");

        HttpResponse<String> res = post("/admin/customize", Map.of(
                "option", "homepage",
                "home_pic_url", "/bg.png",
                "favicon_url", "/fav.ico",
                "transparent_navbar", "true",
                "fixed_bg", "true",
                "copyright_prefer", "3",
                "copyright_text", "custom ©"), cookie);
        assertEquals(200, res.statusCode(), res.body());

        assertEquals("true", option.get("transparent_navbar", null, true));
        assertEquals("false", option.get("hide_intro", null, true), "取消勾选 → false");
        assertEquals("/bg.png", option.get("home_pic_url", null, true));
        assertEquals("3", option.get("copyright_prefer_zh_CN", null, true));
        assertEquals("custom ©", option.get("copyright_text_zh_CN", null, true));

        // ?action=color
        assertEquals(200, post("/admin/customize?action=color",
                Map.of("action", "color", "navbar", "indigo", "sidebar", "dark-navy"), cookie)
                .statusCode());
        assertEquals("indigo", option.get("navbar_color", null, true));
        assertEquals("dark-navy", option.get("sidebar_color", null, true));
    }

    // ------------------------------------------------------------ score

    @Test
    void scoreSavesRatesAndSignRange() throws Exception {
        String cookie = login(seedAdmin());

        assertEquals(200, post("/admin/score", Map.of(
                "option", "rate",
                "score_per_storage", "10",
                "private_score_per_storage", "20",
                "score_per_closet_item", "30",
                "return_score", "true",
                "score_per_player", "40",
                "user_initial_score", "100"), cookie).statusCode());
        assertEquals("10", option.get("score_per_storage", null, true));
        assertEquals("true", option.get("return_score", null, true));

        assertEquals(200, post("/admin/score", Map.of(
                "option", "sign",
                "sign_score_from", "1",
                "sign_score_to", "5",
                "sign_gap_time", "24",
                "sign_after_zero", "true"), cookie).statusCode());
        assertEquals("1,5", option.get("sign_score", null, true), "from,to 合并为 sign_score");
    }

    // ---------------------------------------------------------- options

    @Test
    void generalSavesLocalizedValuesAndNormalizesUrl() throws Exception {
        String cookie = login(seedAdmin());

        assertEquals(200, post("/admin/options", Map.of(
                "option", "general",
                "site_name", "测试站点",
                "site_description", "描述",
                "site_url", "https://example.com/",
                "content_policy", "规则"), cookie).statusCode());

        assertEquals("https://example.com", option.get("site_url", null, true),
                "去掉结尾 /");
        assertEquals("测试站点", option.get("site_name_zh_CN", null, true));
        assertEquals("描述", option.get("site_description_zh_CN", null, true));
        assertEquals("规则", option.get("content_policy_zh_CN", null, true));
    }

    @Test
    void updatesValidationRedirectsWithErrors() throws Exception {
        String cookie = login(seedAdmin());

        HttpResponse<String> res = post("/admin/options", Map.of(
                "option", "updates",
                "update_source", "notaurl"), cookie);
        assertEquals(302, res.statusCode());

        // 错误进入 session，之后页面显示 alert
        String page = get("/admin/options", cookie).body();
        assertTrue(page.contains("alert alert-danger"), "校验失败提示");

        // 未写入任何更新源
        assertFalse(Files.readString(envFile, StandardCharsets.UTF_8)
                .contains("PLUGINS_REGISTRY"));
    }

    @Test
    void updatesWritesEnvAndRefreshesSettings() throws Exception {
        String cookie = login(seedAdmin());

        HttpResponse<String> res = post("/admin/options", Map.of(
                "option", "updates",
                "update_source", "https://updates.example.com/update.json",
                "plugins_registry", "https://plugins.example.com/registry_{lang}.json"), cookie);
        assertEquals(200, res.statusCode(), res.body());

        String env = Files.readString(envFile, StandardCharsets.UTF_8);
        assertTrue(env.contains(
                "UPDATE_SOURCE=\"https://updates.example.com/update.json\""), env);
        assertTrue(env.contains(
                "PLUGINS_REGISTRY=\"https://plugins.example.com/registry_{lang}.json\""), env);
        assertEquals("https://updates.example.com/update.json", settings.getUpdateSource());
        assertEquals("https://plugins.example.com/registry_{lang}.json",
                settings.getPlugins().getRegistry());
        assertEquals("https://updates.example.com/update.json",
                option.get("update_source", null, true));
    }

    @Test
    void updatesPresetSwitchesSource() throws Exception {
        String cookie = login(seedAdmin());

        assertEquals(200, post("/admin/options", Map.of(
                "option", "updates",
                "update_source_preset", "official"), cookie).statusCode());

        String env = Files.readString(envFile, StandardCharsets.UTF_8);
        assertTrue(env.contains("UPDATE_SOURCE=\"" + UpdateSources.OFFICIAL_UPDATE_SOURCE
                + "\""), env);
        assertEquals(UpdateSources.OFFICIAL_PLUGINS_REGISTRY,
                option.get("plugins_registry", null, true));
    }

    // --------------------------------------------------------- resource

    @Test
    void resourceTrimsCdnAndClearsImageCache() throws Exception {
        String cookie = login(seedAdmin());

        assertEquals(200, post("/admin/resource", Map.of(
                "option", "resources",
                "cache_expire_time", "3600",
                "cdn_address", "https://cdn.example.com/",
                "force_ssl", "true"), cookie).statusCode());
        assertEquals("https://cdn.example.com", option.get("cdn_address", null, true));
        assertEquals("3600", option.get("cache_expire_time", null, true));

        imageCache.put("k", new ImageCache.Entry(new byte[] {1}, "image/png", null,
                System.currentTimeMillis() + 60_000));
        assertEquals(1, imageCache.size());

        String page = get("/admin/resource?clear-cache", cookie).body();
        assertTrue(page.contains("缓存已清除"));
        assertEquals(0, imageCache.size(), "?clear-cache 必须清空图片缓存");
    }

    // ------------------------------------------------------------- 权限

    @Test
    void optionsPagesAreAdminOnly() throws Exception {
        String userCookie = login(seedUser("user@a.com", UserRepository.NORMAL));
        assertEquals(403, get("/admin/options", userCookie).statusCode());

        HttpResponse<String> guest = get("/admin/options", "");
        assertEquals(302, guest.statusCode());
    }

    // ================================================================ 工具

    private int seedAdmin() throws Exception {
        return seedUser("admin@a.com", UserRepository.ADMIN);
    }

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

    private HttpResponse<String> post(String path, Map<String, String> fields, String cookie)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(form(fields), StandardCharsets.UTF_8));

        return http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
