package com.neoskin.web.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.CommunicationRenderer;
import com.neoskin.service.Communications;
import com.neoskin.service.LoginFailCounter;
import com.neoskin.service.Option;
import com.neoskin.service.VerificationMail;
import com.neoskin.service.cipher.BcryptCipher;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.RouteUrlGenerator;
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
 * {@code POST /user/email-verification} 与验证邮件签名链接的端到端测试。
 *
 * <p>邮件通道用 {@code MAIL_MAILER=log}，避免真实 SMTP；签名链接直接请求
 * {@code /auth/verify/{user}?signature=...} 验证「非临时签名」可被
 * {@code hasValidSignature} 接受。</p>
 */
class UserVerificationTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private NeoSkinSettings settings;
    private Option option;
    private UserRepository users;
    private BcryptCipher cipher;
    private Translator translator;

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
        settings.setKey("base64:NeoSkinTestKeyNeoSkinTestKey=");
        settings.getMail().setMailer("log");
        SchemaInstaller schema = new SchemaInstaller(ds, settings);
        schema.install();

        AppPaths paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());
        Files.createFile(paths.installLock());

        option = new Option(ds, schema, paths);
        translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");
        JavaScript javaScript = new JavaScript(paths, translator, null);
        BlessingFilter filter = new BlessingFilter();
        users = new UserRepository(ds);
        PlayerRepository players = new PlayerRepository(ds);
        TextureRepository textures = new TextureRepository(ds);
        cipher = new BcryptCipher(10);

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
                new LoginFailCounter(), users, players, cipher, "", false, ds, settings);
        HandlerResolver user = UserController.resolver(option, translator, users, players, ds,
                "Asia/Shanghai", cipher, "", javaScript, filter, false, service, settings);
        HandlerResolver resolver = new HandlerResolver() {
            @Override
            public Handler<RoutingContext> resolve(String action) {
                Handler<RoutingContext> handler = auth.resolve(action);

                return handler != null ? handler : user.resolve(action);
            }

            @Override
            public Handler<RoutingContext> resolve(String action, String uri) {
                Handler<RoutingContext> handler = auth.resolve(action, uri);

                return handler != null ? handler : user.resolve(action, uri);
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

    @Test
    void disabledWhenRequireVerificationIsOff() throws Exception {
        seedUser("u@a.com", 0);
        String cookie = login("u@a.com");

        HttpResponse<String> res = post("/user/email-verification", "", cookie);
        assertEquals(200, res.statusCode(), res.body());
        Map<String, Object> body = decode(res.body());
        assertEquals(1, num(body.get("code")));
        assertEquals("本站已关闭邮箱验证功能", body.get("message"));
    }

    @Test
    void sendsMailAndRateLimits() throws Exception {
        option.set("require_verification", "true");
        seedUser("u@a.com", 0);
        String cookie = login("u@a.com");

        HttpResponse<String> first = post("/user/email-verification", "", cookie);
        assertEquals(200, first.statusCode(), first.body());
        Map<String, Object> body = decode(first.body());
        assertEquals(0, num(body.get("code")), first.body());
        assertEquals("验证邮件已发送，请检查你的收件箱。", body.get("message"));

        HttpResponse<String> second = post("/user/email-verification", "", cookie);
        Map<String, Object> limited = decode(second.body());
        assertEquals(1, num(limited.get("code")), second.body());
        assertTrue(String.valueOf(limited.get("message")).contains("过 60 秒"),
                second.body());
    }

    @Test
    void verifiedUserGetsAlreadyVerifiedMessage() throws Exception {
        option.set("require_verification", "true");
        seedUser("v@a.com", 1);
        String cookie = login("v@a.com");

        HttpResponse<String> res = post("/user/email-verification", "", cookie);
        Map<String, Object> body = decode(res.body());
        assertEquals(1, num(body.get("code")), res.body());
        assertEquals("你已经验证过邮箱了", body.get("message"));
    }

    @Test
    void signedVerificationLinkIsAccepted() throws Exception {
        option.set("require_verification", "true");
        int uid = seedUser("u@a.com", 0);

        String host = "127.0.0.1:" + port;
        String url = VerificationMail.url(RouteUrlGenerator.forHost(host), settings.getKey(),
                host, uid);
        String path = url.substring(url.indexOf("/auth/verify"));

        assertEquals(200, get(path).statusCode(), "验证链接必须通过签名校验");

        HttpResponse<String> tampered = get(path.replaceAll("signature=(\\w)", "signature=x$1"));
        assertEquals(403, tampered.statusCode(), "篡改签名必须 403");
    }

    // ================================================================ 工具

    private int seedUser(String email, int verified) throws Exception {
        int uid = users.insert(email, email.substring(0, email.indexOf('@')), 1000,
                cipher.hash("secret123", ""), "127.0.0.1", "Asia/Shanghai");
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE users SET verified = ? WHERE uid = ?")) {
            stmt.setInt(1, verified);
            stmt.setInt(2, uid);
            stmt.executeUpdate();
        }

        return uid;
    }

    private String login(String email) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Sec-Fetch-Site", "same-origin")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "identification=" + enc(email) + "&password=secret123",
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, res.statusCode(), "login failed: " + res.body());

        return res.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith("BS_SESSION="))
                .map(h -> h.split(";")[0])
                .findFirst().orElseThrow(() -> new AssertionError("no session cookie"));
    }

    private HttpResponse<String> post(String path, String body, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Sec-Fetch-Site", "same-origin")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }

        return http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();

        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decode(String body) {
        return (Map<String, Object>) PhpJson.decode(body);
    }

    private static int num(Object value) {
        return ((Number) value).intValue();
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
