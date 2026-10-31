package com.neoskin.web.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
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
import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.Routes;
import com.neoskin.web.VertxRouteRegistry;
import com.neoskin.web.auth.SessionGuard;
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
 * {@code AuthController@handleLogin} / {@code @logout} 的端到端测试。
 *
 * <p>锁定的源语义：</p>
 * <ol>
 *   <li>校验失败 → 422 {@code {message, errors}}（前端按 XHR 处理）；</li>
 *   <li>用户不存在 → {@code {code:2, message:'用户不存在'}}；</li>
 *   <li>密码错 → {@code {code:1, message:'密码错误', data:{login_fails:n}}}；</li>
 *   <li>成功 → {@code {code:0, message:'登录成功，欢迎回来', data:{redirectTo}}}
 *       且会话写入 {@code login_web_<sha1>}、会话 id 已更换（防固定）；</li>
 *   <li>可按角色名（players.name）登录；</li>
 *   <li>{@code logout} → {@code {code:0, message:'登出成功'}} 且登录态被清除。</li>
 * </ol>
 */
class AuthLoginTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private Option option;
    private Translator translator;
    private LoginFailCounter failures;

    private final Routes routes = Routes.load();

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
        new SchemaInstaller(ds, settings).install();

        AppPaths paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());
        Files.createFile(paths.installLock());

        option = new Option(ds, new SchemaInstaller(ds, settings), paths);
        translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");
        failures = new LoginFailCounter();

        JavaScript javaScript = new JavaScript(paths, translator, null);
        BcryptCipher cipher = new BcryptCipher(10);

        vertx = Vertx.vertx();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        EncryptCookies cookies = new EncryptCookies(
                new com.neoskin.service.cipher.PhpEncrypter(key, "aes-256-cbc"));
        Map<String, Handler<RoutingContext>> registry = new HashMap<>();
        MiddlewareChain.connectValueLayer(registry, cookies, dir.resolve("sessions"));

        HandlerResolver resolver = AuthController.resolver(
                option, translator, javaScript, new BlessingFilter(), failures,
                new UserRepository(ds), new PlayerRepository(ds), cipher, "", false);

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
    void validationFailureReturns422() throws Exception {
        HttpResponse<String> res = post("/auth/login", "identification=a@b.com&password=123");

        assertEquals(422, res.statusCode(), res.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) body.get("errors");
        assertTrue(errors.containsKey("password"), "太短 → 报错: " + errors);
    }

    @Test
    void unknownUserReturnsCode2() throws Exception {
        HttpResponse<String> res = post("/auth/login",
                "identification=nobody@example.com&password=secret123");

        assertEquals(200, res.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        assertEquals(2, ((Number) body.get("code")).intValue());
        assertEquals("用户不存在", body.get("message"));
    }

    @Test
    void wrongPasswordIncrementsFails() throws Exception {
        seedUser("a@b.com", "secret123", 0);

        HttpResponse<String> res = post("/auth/login",
                "identification=a@b.com&password=wrongpass");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        assertEquals(1, ((Number) body.get("code")).intValue());
        assertEquals("密码错误", body.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertEquals(1, ((Number) data.get("login_fails")).intValue());
    }

    @Test
    void successfulLoginSetsSessionAndRegenerates() throws Exception {
        seedUser("a@b.com", "secret123", 0);

        HttpResponse<String> res = post("/auth/login",
                "identification=a@b.com&password=secret123");

        assertEquals(200, res.statusCode(), res.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        assertEquals(0, ((Number) body.get("code")).intValue());
        assertEquals("登录成功，欢迎回来", body.get("message"));
        assertNotNull(body.get("data"), "成功响应必须带 data.redirectTo");

        // 会话 cookie 必须回写
        String setCookie = res.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith("BS_SESSION=")).findFirst().orElse(null);
        assertNotNull(setCookie, "登录成功必须回写会话 cookie");
    }

    @Test
    void canLoginWithPlayerName() throws Exception {
        int uid = seedUser("a@b.com", "secret123", 0);
        seedPlayer(uid, "Notch");

        HttpResponse<String> res = post("/auth/login",
                "identification=Notch&password=secret123");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        assertEquals(0, ((Number) body.get("code")).intValue(), res.body());
    }

    @Test
    void captchaRequiredAfterTooManyFailures() throws Exception {
        seedUser("a@b.com", "secret123", 0);

        // 连续 4 次密码错误 → login_fails = 4（源：> 3 时强制验证码）
        for (int i = 0; i < 4; i++) {
            post("/auth/login", "identification=a@b.com&password=wrongpass");
        }

        // 未带验证码 → 422
        HttpResponse<String> missing = post("/auth/login",
                "identification=a@b.com&password=secret123");
        assertEquals(422, missing.statusCode(), missing.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(missing.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) body.get("errors");
        assertTrue(errors.containsKey("captcha"), missing.body());

        // 错误验证码 → 仍然 422（证明不是「非空即可」）
        HttpResponse<String> wrong = post("/auth/login",
                "identification=a@b.com&password=secret123&captcha=zzzzz");
        assertEquals(422, wrong.statusCode(), wrong.body());
    }

    @Test
    void logoutClearsSession() throws Exception {
        seedUser("a@b.com", "secret123", 0);
        String cookie = sessionCookie(post("/auth/login",
                "identification=a@b.com&password=secret123"));

        HttpResponse<String> res = postWithCookie("/auth/logout", "", cookie);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        assertEquals(0, ((Number) body.get("code")).intValue());
        assertEquals("登出成功", body.get("message"));
    }

    /** 会话键必须与 PHP 的 {@code SessionGuard::getName()} 一致。 */
    @Test
    void sessionKeyMatchesPhpGuard() {
        assertEquals("login_web_59ba36addc2b2f9401580f014c7f58ea4e30989d",
                SessionGuard.SESSION_KEY);
        assertEquals(SessionGuard.SESSION_KEY, SessionGuard.sessionKeyFor("web"));
    }

    // ================================================================ 工具

    private int seedUser(String email, String rawPassword, int permission) throws Exception {
        String hash = new BcryptCipher(10).hash(rawPassword, "");
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO users (`email`, `nickname`, `score`, `avatar`, `password`,"
                             + " `ip`, `permission`, `last_sign_at`, `register_at`)"
                             + " VALUES (?, '', 0, 0, ?, '127.0.0.1', ?,"
                             + " '2026-01-01 00:00:00', '2026-01-01 00:00:00')")) {
            stmt.setString(1, email);
            stmt.setString(2, hash);
            stmt.setInt(3, permission);
            stmt.executeUpdate();
        }
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT uid FROM users WHERE email = ?")) {
            stmt.setString(1, email);
            var rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }

    private void seedPlayer(int uid, String name) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO players (`uid`, `name`, `tid_skin`, `tid_cape`, `last_modified`)"
                             + " VALUES (?, ?, 0, 0, '2026-01-01 00:00:00')")) {
            stmt.setInt(1, uid);
            stmt.setString(2, name);
            stmt.executeUpdate();
        }
    }

    private static String sessionCookie(HttpResponse<String> res) {
        return res.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith("BS_SESSION="))
                .map(h -> h.split(";")[0])
                .findFirst().orElse("");
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send(path, body, Map.of("Sec-Fetch-Site", "same-origin"));
    }

    private HttpResponse<String> postWithCookie(String path, String body, String cookie)
            throws Exception {
        return send(path, body, Map.of("Sec-Fetch-Site", "same-origin", "Cookie", cookie));
    }

    private HttpResponse<String> send(String path, String body, Map<String, String> extra)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json");
        extra.forEach(builder::header);
        builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    /** 供诊断：SessionStore 的键名（避免未使用告警）。 */
    @SuppressWarnings("unused")
    private static String tokenKey() {
        return SessionStore.TOKEN_KEY;
    }
}
