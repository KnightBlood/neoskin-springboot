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
import com.neoskin.db.CommunicationRepository;
import com.neoskin.db.NotificationRepository;
import com.neoskin.db.PlayerRepository;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.db.TextureRepository;
import com.neoskin.db.UserRepository;
import com.neoskin.oauth.OAuthRuntime;
import com.neoskin.oauth.OAuthService;
import com.neoskin.oauth.OAuthStore;
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.CommunicationRenderer;
import com.neoskin.service.Communications;
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
 * Passport OAuth2 端到端测试：作用域、客户端 CRUD、个人令牌、授权码/刷新令牌、
 * Bearer 鉴权与 scope 中间件、transient cookie。
 */
class PassportControllerTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private AppPaths paths;
    private UserRepository users;
    private OAuthStore store;
    private OAuthService oauth;
    private BcryptCipher cipher;
    private byte[] cookieKey;
    private Option option;

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

        paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());
        Files.createFile(paths.installLock());

        option = new Option(ds, schema, paths);
        Translator translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");
        JavaScript javaScript = new JavaScript(paths, translator, null);
        BlessingFilter filter = new BlessingFilter();
        users = new UserRepository(ds);
        TextureRepository textures = new TextureRepository(ds);
        PlayerRepository players = new PlayerRepository(ds);
        cipher = new BcryptCipher(10);
        cookieKey = new byte[32];
        for (int i = 0; i < cookieKey.length; i++) {
            cookieKey[i] = (byte) i;
        }
        store = new OAuthStore(ds, "Asia/Shanghai");
        oauth = new OAuthService(paths, store, users, cipher, cookieKey, "Asia/Shanghai");
        OAuthRuntime.set(oauth);

        CommunicationRepository communications = new CommunicationRepository(ds, schema,
                "Asia/Shanghai");
        NotificationRepository notifications = new NotificationRepository(ds, schema,
                "Asia/Shanghai");
        Communications service = new Communications(ds, communications, notifications,
                new CommunicationRenderer(), "Asia/Shanghai");

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
        HandlerResolver notificationsResolver = NotificationsController.resolver(option,
                translator, users, communications, notifications, service, ds,
                "Asia/Shanghai");
        HandlerResolver passportOauth = PassportOAuthController.resolver(oauth, translator,
                cipher);
        HandlerResolver passportApi = PassportApiController.resolver(oauth, translator,
                cipher);
        HandlerResolver resolver = new HandlerResolver() {
            @Override
            public Handler<RoutingContext> resolve(String action) {
                Handler<RoutingContext> handler = auth.resolve(action);
                if (handler == null) {
                    handler = notificationsResolver.resolve(action);
                }
                if (handler == null) {
                    handler = passportOauth.resolve(action);
                }

                return handler != null ? handler : passportApi.resolve(action);
            }

            @Override
            public Handler<RoutingContext> resolve(String action, String uri) {
                Handler<RoutingContext> handler = auth.resolve(action, uri);
                if (handler == null) {
                    handler = notificationsResolver.resolve(action, uri);
                }
                if (handler == null) {
                    handler = passportOauth.resolve(action, uri);
                }

                return handler != null ? handler : passportApi.resolve(action, uri);
            }
        };

        VertxRouteRegistry.BuildResult built = VertxRouteRegistry.build(
                routes, resolver, vertx, List.of(), registry);
        port = listen(built.router());
    }

    @AfterEach
    void tearDown() throws Exception {
        OAuthRuntime.set(null);
        ds.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------ scopes

    @Test
    void scopesListContainsDefaults() throws Exception {
        seedUser("a@a.com");
        String cookie = login("a@a.com");

        HttpResponse<String> res = get("/oauth/scopes", cookie, null);
        assertEquals(200, res.statusCode(), res.body());
        PhpJson.Array list = (PhpJson.Array) PhpJson.decode(res.body());
        boolean hasUserRead = false;
        for (Object item : list.values()) {
            if ("User.Read".equals(((Map<?, ?>) item).get("id"))) {
                hasUserRead = true;
            }
        }
        assertTrue(hasUserRead, res.body());
    }

    // ----------------------------------------------------------- clients

    @Test
    void clientCrud() throws Exception {
        seedUser("a@a.com");
        String cookie = login("a@a.com");

        HttpResponse<String> created = post("/oauth/clients",
                "name=MyApp&redirect=" + enc("https://example.com/callback&confidential=1"),
                cookie);
        assertEquals(200, created.statusCode(), created.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> client = (Map<String, Object>) PhpJson.decode(created.body());
        long id = ((Number) client.get("id")).longValue();
        assertNotNull(client.get("plain_secret"));
        assertFalse(client.containsKey("secret"), "secret 必须隐藏");
        assertEquals(false, client.get("revoked"));
        assertTrue(store.verifyClientSecret(store.findClient(id),
                String.valueOf(client.get("plain_secret")), cipher));

        String list = get("/oauth/clients", cookie, null).body();
        assertTrue(list.contains("\"name\":\"MyApp\""), list);

        HttpResponse<String> updated = put("/oauth/clients/" + id,
                "name=Renamed&redirect=" + enc("https://example.com/new"), cookie);
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals("Renamed", store.findClient(id).name());
        assertEquals("https://example.com/new", store.findClient(id).redirect());

        assertEquals(204, delete("/oauth/clients/" + id, cookie).statusCode());
        assertTrue(store.findClient(id).revoked());

        // 别人的客户端 → 404
        seedUser("b@b.com");
        String other = login("b@b.com");
        long otherClient = store.createClient(users.findByEmail("b@b.com").uid(), "Other",
                null, null, "https://x.example/cb", false, false);
        assertEquals(404, delete("/oauth/clients/" + otherClient, cookie).statusCode());
    }

    // --------------------------------------------------------- personal

    @Test
    void personalAccessTokens() throws Exception {
        int uid = seedUser("a@a.com");
        long personalClient = store.createClient(null, "Personal", null, null, "",
                true, false);
        String cookie = login("a@a.com");

        HttpResponse<String> created = post("/oauth/personal-access-tokens",
                "name=CLI&scopes[]=Notification.Read", cookie);
        assertEquals(200, created.statusCode(), created.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(created.body());
        assertEquals("Bearer", body.get("tokenType"));
        assertEquals(31536000, ((Number) body.get("expiresIn")).intValue());
        String accessToken = String.valueOf(body.get("accessToken"));
        String tokenId = String.valueOf(body.get("accessTokenId"));
        assertNotNull(store.findToken(tokenId));

        // 令牌可用于 API（scope 命中）
        assertEquals(200, get("/api/user/notifications", null,
                Map.of("Authorization", "Bearer " + accessToken)).statusCode());

        String list = get("/oauth/personal-access-tokens", cookie, null).body();
        assertTrue(list.contains("\"name\":\"CLI\""), list);

        assertEquals(204, delete("/oauth/personal-access-tokens/" + tokenId, cookie)
                .statusCode());
        assertTrue(store.findToken(tokenId).revoked());
        // 已撤销 → 401
        assertEquals(401, get("/api/user/notifications", null,
                Map.of("Authorization", "Bearer " + accessToken)).statusCode());

        assertEquals(0, personalClient - personalClient);
        assertEquals(uid, uid);
    }

    // ------------------------------------------------------ bearer/scope

    @Test
    void bearerRequiresValidTokenAndScope() throws Exception {
        int uid = seedUser("a@a.com");
        long client = store.createClient(uid, "App", null, null, "https://x.example/cb",
                false, false);
        OAuthService.Issued issued = oauth.issue(uid, client, null,
                List.of("User.Read"), false);

        // 缺 scope → 403
        HttpResponse<String> denied = get("/api/user/notifications", null,
                Map.of("Authorization", "Bearer " + issued.accessToken()));
        assertEquals(403, denied.statusCode(), denied.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) PhpJson.decode(denied.body());
        assertEquals("Invalid scope(s) provided.", error.get("message"));

        // 有效 token + 正确 scope → 200
        OAuthService.Issued ok = oauth.issue(uid, client, null,
                List.of("Notification.Read"), false);
        assertEquals(200, get("/api/user/notifications", null,
                Map.of("Authorization", "Bearer " + ok.accessToken())).statusCode());

        // 伪造 token → 401
        assertEquals(401, get("/api/user/notifications", null,
                Map.of("Authorization", "Bearer not-a-jwt")).statusCode());
    }

    // ---------------------------------------------------- code + refresh

    @Test
    void authorizationCodeAndRefreshFlow() throws Exception {
        int uid = seedUser("a@a.com");
        String secret = "client-secret";
        long client = store.createClient(uid, "App", cipher.hash(secret, ""), null,
                "https://x.example/cb", false, false);
        String code = OAuthService.randomHex(40);
        store.insertAuthCode(code, uid, client, List.of("Notification.Read"),
                PhpDate.at((System.currentTimeMillis() / 1000 + 600) * 1000, "Asia/Shanghai"));

        HttpResponse<String> token = post("/oauth/token",
                "grant_type=authorization_code&code=" + code + "&client_id=" + client
                        + "&client_secret=" + secret
                        + "&redirect_uri=" + enc("https://x.example/cb"), null);
        assertEquals(200, token.statusCode(), token.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(token.body());
        assertEquals("Bearer", body.get("token_type"));
        String access = String.valueOf(body.get("access_token"));
        String refresh = String.valueOf(body.get("refresh_token"));
        assertNotNull(store.findAuthCode(code));
        assertTrue(store.findAuthCode(code).revoked(), "授权码一次性");

        // 用 code 再用一次 → invalid_grant（客户端凭据仍需正确）
        HttpResponse<String> reused = post("/oauth/token",
                "grant_type=authorization_code&code=" + code + "&client_id=" + client
                        + "&client_secret=" + secret,
                null);
        assertEquals(400, reused.statusCode());
        assertTrue(reused.body().contains("invalid_grant"), reused.body());

        // 刷新
        HttpResponse<String> refreshed = post("/oauth/token",
                "grant_type=refresh_token&refresh_token=" + refresh
                        + "&client_id=" + client, null);
        assertEquals(200, refreshed.statusCode(), refreshed.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> refreshedBody = (Map<String, Object>) PhpJson.decode(
                refreshed.body());
        assertFalse(access.equals(refreshedBody.get("access_token")));
        assertTrue(store.findRefreshToken(refresh).revoked(), "旧 refresh token 已撤销");

        // 错误 grant
        HttpResponse<String> bad = post("/oauth/token", "grant_type=nope", null);
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("unsupported_grant_type"), bad.body());
    }

    @Test
    void clientCredentialsGrant() throws Exception {
        String secret = "first-party-secret";
        long client = store.createClient(null, "FirstParty", cipher.hash(secret, ""),
                null, "", false, false);

        HttpResponse<String> res = post("/oauth/token",
                "grant_type=client_credentials&client_id=" + client
                        + "&client_secret=" + secret + "&scope=User.Read", null);
        assertEquals(200, res.statusCode(), res.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        assertNotNull(body.get("access_token"));
        assertFalse(body.containsKey("refresh_token"));
        // client credentials 没有用户 → api 401
        assertEquals(401, get("/api/user/notifications", null,
                Map.of("Authorization", "Bearer " + body.get("access_token"))).statusCode());
    }

    // ----------------------------------------------------------- transient

    @Test
    void transientCookieAuthenticatesApi() throws Exception {
        seedUser("a@a.com");
        String cookie = login("a@a.com");

        HttpResponse<String> refreshed = post("/oauth/token/refresh", "", cookie);
        assertEquals(200, refreshed.statusCode());
        assertEquals("Refreshed.", refreshed.body());
        String setCookie = refreshed.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith("laravel_token="))
                .findFirst().orElseThrow(() -> new AssertionError("no laravel_token"));

        // 需要 CSRF 头与 cookie 内的 csrf 一致：从会话页面拿不到 token，
        // 这里直接解码 cookie 用其 csrf（模拟前端从 XSRF-TOKEN 得到同值）。
        String jwt = setCookie.substring("laravel_token=".length(),
                setCookie.indexOf(';'));
        Map<String, Object> claims = com.neoskin.oauth.Jwts.decodeHs256(jwt, cookieKey);
        assertNotNull(claims);
        String csrf = String.valueOf(claims.get("csrf"));

        String transientCookie = "laravel_token=" + jwt;
        assertEquals(200, get("/api/user/notifications", null, Map.of(
                "Cookie", transientCookie, "X-CSRF-TOKEN", csrf)).statusCode());
        assertEquals(401, get("/api/user/notifications", null,
                Map.of("Cookie", transientCookie)).statusCode());
    }

    // ----------------------------------------------------------- authorize

    @Test
    void authorizePageAndDenyRedirect() throws Exception {
        seedUser("a@a.com");
        long client = store.createClient(users.findByEmail("a@a.com").uid(), "App",
                null, null, "https://x.example/cb", false, false);
        String cookie = login("a@a.com");

        HttpResponse<String> page = get("/oauth/authorize?client_id=" + client
                + "&redirect_uri=" + enc("https://x.example/cb")
                + "&response_type=code&state=abc&scope=User.Read", cookie, null);
        assertEquals(200, page.statusCode(), page.body());
        assertTrue(page.body().contains("App"));

        HttpResponse<String> denied = delete("/oauth/authorize", cookie);
        assertEquals(302, denied.statusCode());
        String location = denied.headers().firstValue("Location").orElse("");
        assertTrue(location.startsWith("https://x.example/cb?"), location);
        assertTrue(location.contains("error=access_denied"), location);
        assertTrue(location.contains("state=abc"), location);
    }

    @Test
    void authorizeApproveIssuesCodeAndSkipsWhenGranted() throws Exception {
        int uid = seedUser("a@a.com");
        long client = store.createClient(uid, "App", null, null, "https://x.example/cb",
                false, false);
        String cookie = login("a@a.com");

        String query = "/oauth/authorize?client_id=" + client
                + "&redirect_uri=" + enc("https://x.example/cb")
                + "&response_type=code&state=s1&scope=User.Read";
        HttpResponse<String> page = get(query, cookie, null);
        assertEquals(200, page.statusCode(), page.body());
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("name=\"auth_token\" value=\"([^\"]+)\"").matcher(page.body());
        assertTrue(matcher.find(), page.body());
        String authToken = matcher.group(1);

        HttpResponse<String> approved = post("/oauth/authorize",
                "auth_token=" + authToken, cookie);
        assertEquals(302, approved.statusCode(), approved.body());
        String location = approved.headers().firstValue("Location").orElse("");
        assertTrue(location.startsWith("https://x.example/cb?code="), location);
        assertTrue(location.contains("state=s1"), location);
        String code = location.substring("https://x.example/cb?code=".length(),
                location.indexOf("&state="));

        HttpResponse<String> token = post("/oauth/token",
                "grant_type=authorization_code&code=" + code + "&client_id=" + client, null);
        assertEquals(200, token.statusCode(), token.body());

        // 同一 client + user + scope 已授权 → 直接 302 带 code（源 hasGrantedScopes）
        HttpResponse<String> again = get(query, cookie, null);
        assertEquals(302, again.statusCode(), again.body());
        assertTrue(again.headers().firstValue("Location").orElse("")
                .startsWith("https://x.example/cb?code="));

        // 错误的 auth_token → 403
        assertEquals(403, post("/oauth/authorize", "auth_token=wrong", cookie).statusCode());
    }

    // ----------------------------------------------------------- verified

    @Test
    void verifiedMiddlewareRejectsUnverifiedUsers() throws Exception {
        seedUser("u@u.com", 0);
        String cookie = login("u@u.com");

        // 默认 require_verification=false：未验证用户也放行
        assertEquals(200, get("/oauth/clients", cookie, null).statusCode());

        option.set("require_verification", "true");
        HttpResponse<String> denied = get("/oauth/clients", cookie, null);
        assertEquals(403, denied.statusCode(), denied.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) PhpJson.decode(denied.body());
        assertEquals("你必须验证邮箱后才能访问此页面", error.get("message"));

        // 验证邮箱后恢复访问
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE users SET verified = 1 WHERE email = ?")) {
            stmt.setString(1, "u@u.com");
            stmt.executeUpdate();
        }
        String verifiedCookie = login("u@u.com");
        assertEquals(200, get("/oauth/clients", verifiedCookie, null).statusCode());
    }

    // ================================================================ 工具

    private int seedUser(String email) throws Exception {
        return seedUser(email, 1);
    }

    private int seedUser(String email, int verified) throws Exception {
        String hash = cipher.hash("secret123", "");
        String sql = "INSERT INTO users (`email`, `nickname`, `score`, `avatar`, `password`,"
                + " `ip`, `permission`, `last_sign_at`, `register_at`, `verified`)"
                + " VALUES (?, ?, 0, 0, ?, '127.0.0.1', 0,"
                + " '2026-01-01 00:00:00', '2026-01-01 00:00:00', ?)";
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            stmt.setString(2, email.substring(0, email.indexOf('@')));
            stmt.setString(3, hash);
            stmt.setInt(4, verified);
            stmt.executeUpdate();
        }

        return users.findByEmail(email).uid();
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

    private HttpResponse<String> get(String path, String cookie, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json");
        if (cookie != null && !cookie.isEmpty()) {
            builder.header("Cookie", cookie);
        }
        if (headers != null) {
            headers.forEach(builder::header);
        }
        builder.GET();

        return http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Sec-Fetch-Site", "same-origin");
        if (cookie != null && !cookie.isEmpty()) {
            builder.header("Cookie", cookie);
        }
        builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body,
                StandardCharsets.UTF_8));

        return http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> put(String path, String body, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Sec-Fetch-Site", "same-origin");
        if (cookie != null && !cookie.isEmpty()) {
            builder.header("Cookie", cookie);
        }
        builder.PUT(HttpRequest.BodyPublishers.ofString(body == null ? "" : body,
                StandardCharsets.UTF_8));

        return http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> delete(String path, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json")
                .header("Sec-Fetch-Site", "same-origin");
        if (cookie != null && !cookie.isEmpty()) {
            builder.header("Cookie", cookie);
        }
        builder.DELETE();

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
