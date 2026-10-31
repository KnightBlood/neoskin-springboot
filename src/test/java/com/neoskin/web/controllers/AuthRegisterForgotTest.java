package com.neoskin.web.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.neoskin.db.PlayerRepository;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.db.UserRepository;
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.LoginFailCounter;
import com.neoskin.service.Option;
import com.neoskin.service.cipher.BcryptCipher;
import com.neoskin.service.cipher.PhpEncrypter;
import com.neoskin.service.session.FileSessionHandler;
import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.Routes;
import com.neoskin.web.SignedUrl;
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
 * 注册 / 忘记密码 / 重置密码 / 验证码 / 绑定邮箱的端到端测试。
 *
 * <p>覆盖 {@code AuthController} 里除 login/logout 之外的 10 个 action，
 * 全部走<b>真实 HTTP</b>：路由 → 中间件 → 控制器 → 模板。</p>
 *
 * <h2>为什么这些用例必须端到端</h2>
 * <p>它们的失败模式几乎都在「接线」上而不是算法上：</p>
 * <ul>
 *   <li>{@code errors} 是 Twig 的共享视图变量，Thymeleaf 没这个机制 ——
 *       少注入一次就 500；</li>
 *   <li>{@code auth/bind} 的 action 是 {@code ViewController}，只靠 action
 *       分不出模板，必须走 uri 分流；</li>
 *   <li>签名 URL 的 HMAC key 是 {@code app.key} 原串（不是解码后的字节），
 *       走错就是 403。</li>
 * </ul>
 *
 * <h2>刻意不测的路径</h2>
 * <p>{@code handleForgot} 的<b>成功</b>分支会真实连 SMTP（见 PORT-NOTES
 * 偏差 14.3），所以只测不碰网络的失败分支。</p>
 */
class AuthRegisterForgotTest {

    @TempDir
    Path dir;

    /** 测试用 APP_KEY（原串，签名 URL 直接拿它当 HMAC key）。 */
    private static final String APP_KEY = "base64:NeoSkinTestKeyNeoSkinTestKey=";

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private Option option;
    private Translator translator;
    private NeoSkinSettings settings;
    private EncryptCookies cookies;
    private Path sessionDir;

    private final Routes routes = Routes.load();

    @BeforeEach
    void setUp() throws Exception {
        Path file = dir.resolve("test.sqlite");
        Files.createFile(file);
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(2);
        ds = new HikariDataSource(cfg);

        settings = new NeoSkinSettings();
        settings.getDatabase().setConnection("sqlite");
        settings.getDatabase().setName(file.toAbsolutePath().toString());
        settings.getStorage().setRoot(dir.resolve("storage").toString());
        settings.setKey(APP_KEY);
        new SchemaInstaller(ds, settings).install();

        AppPaths paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());
        Files.createFile(paths.installLock());

        option = new Option(ds, new SchemaInstaller(ds, settings), paths);
        option.set("site_name", "NeoSkin 测试站");
        option.set("navbar_color", "cyan");

        translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");
        JavaScript javaScript = new JavaScript(paths, translator, null);

        sessionDir = dir.resolve("sessions");
        Files.createDirectories(sessionDir);

        vertx = Vertx.vertx();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        cookies = new EncryptCookies(new PhpEncrypter(key, "aes-256-cbc"));

        Map<String, Handler<RoutingContext>> registry = new HashMap<>();
        // 8 参重载：会挂 ShareErrorsFromSession / Authenticate / RejectBannedUser /
        // EnsureEmailFilled / RedirectIfAuthenticated。注册与忘记密码走 guest 组，
        // 未登录时全部放行，正好顺带验证「guest 不误拦」。
        MiddlewareChain.connectValueLayer(registry, cookies, sessionDir,
                paths, settings.getVersion(), option, translator, new UserRepository(ds));

        HandlerResolver resolver = AuthController.resolver(
                option, translator, javaScript, new BlessingFilter(),
                new LoginFailCounter(), new UserRepository(ds), new PlayerRepository(ds),
                new BcryptCipher(10), "", false, ds, settings);

        VertxRouteRegistry.BuildResult built = VertxRouteRegistry.build(
                routes, resolver, vertx, List.of(), registry);
        port = listen(built.router());
    }

    @AfterEach
    void tearDown() throws Exception {
        ds.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ================================================================ 注册

    /** {@code register()}：标题、notice 文案、form 占位、extra 脚本块。 */
    @Test
    void registerPageRenders() throws Exception {
        HttpResponse<String> res = get("/auth/register");

        assertEquals(200, res.statusCode(), res.body());
        String body = res.body();
        assertTrue(body.contains("<title>注册 - NeoSkin 测试站</title>"), head(body));
        // notice 行的 :sitename 替换（模板里曾把 ${} 嵌进 ${}，会 500）
        assertTrue(body.contains("欢迎使用 NeoSkin 测试站！"), head(body));
        assertTrue(body.contains("<main></main>"), "form 行占位");
        assertTrue(body.contains("id=\"blessing-extra\""), "extra 脚本块");
        // 源 config/options.php: register_with_player_name 默认 'true'
        assertTrue(body.contains("\"player\":true"), body);
    }

    /** 校验失败 → 422 + {@code errors.password}。 */
    @Test
    void registerValidationFailureReturns422() throws Exception {
        HttpResponse<String> res = postJson("/auth/register",
                form("email", "a@b.com", "password", "123", "captcha", "abcde",
                        "player_name", "Tester"),
                sessionCookie("abcde"));

        assertEquals(422, res.statusCode(), res.body());
        Map<String, Object> body = decode(res.body());
        assertTrue(errors(body).containsKey("password"),
                "密码太短必须报错: " + errors(body));
    }

    /** 邮箱已存在 → 422（{@code unique:users} 走真实 DB）。 */
    @Test
    void registerDuplicateEmailReturns422() throws Exception {
        seedUser("taken@example.com", "secret123", 0);

        HttpResponse<String> res = postJson("/auth/register",
                form("email", "taken@example.com", "password", "secret123",
                        "captcha", "abcde", "player_name", "Tester"),
                sessionCookie("abcde"));

        assertEquals(422, res.statusCode(), res.body());
        assertTrue(errors(decode(res.body())).containsKey("email"),
                "重复邮箱必须报错: " + errors(decode(res.body())));
    }

    /** 成功 → {@code {code:0}}，用户落库，且会话被写登录态。 */
    @Test
    void registerSucceedsAndLogsIn() throws Exception {
        HttpResponse<String> res = postJson("/auth/register",
                form("email", "new@example.com", "password", "secret123",
                        "captcha", "abcde", "player_name", "Newbie"),
                sessionCookie("abcde"));

        assertEquals(200, res.statusCode(), res.body());
        Map<String, Object> body = decode(res.body());
        assertEquals(0, num(body.get("code")), res.body());
        assertEquals("注册成功，正在跳转...", body.get("message"));

        // 用户落库
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT uid, nickname, score FROM users WHERE email = ?")) {
            stmt.setString(1, "new@example.com");
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "注册必须写入 users");
                assertEquals("Newbie", rs.getString("nickname"));
                assertEquals(1000, rs.getInt("score"), "user_initial_score 默认 1000");
            }
        }

        // 会话 cookie 必须回写（登录态）
        String setCookie = res.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith("BS_SESSION=")).findFirst().orElse(null);
        assertNotNull(setCookie, "注册成功必须回写会话 cookie");
    }

    /** {@code require_verification} 打开时注册仍需成功（验证邮件走 log mailer）。 */
    @Test
    void registerSucceedsWhenVerificationMailEnabled() throws Exception {
        option.set("require_verification", "true");
        settings.getMail().setMailer("log");

        HttpResponse<String> res = postJson("/auth/register",
                form("email", "verify@example.com", "password", "secret123",
                        "captcha", "abcde", "player_name", "Verifier"),
                sessionCookie("abcde"));

        assertEquals(200, res.statusCode(), res.body());
        Map<String, Object> body = decode(res.body());
        assertEquals(0, num(body.get("code")), res.body());
    }

    /** {@code regs_per_ip} 上限 → {@code {code:1}} + 上限文案。 */
    @Test
    void registerRespectsRegsPerIp() throws Exception {
        option.set("regs_per_ip", 1);
        seedUser("first@example.com", "secret123", 0);

        HttpResponse<String> res = postJson("/auth/register",
                form("email", "second@example.com", "password", "secret123",
                        "captcha", "abcde", "player_name", "Tester"),
                sessionCookie("abcde"));

        assertEquals(200, res.statusCode(), res.body());
        Map<String, Object> body = decode(res.body());
        assertEquals(1, num(body.get("code")), res.body());
        assertTrue(String.valueOf(body.get("message")).contains("上限"),
                "必须提示注册上限: " + body.get("message"));
    }

    /** 验证码错误 → 422 + {@code errors.captcha}。 */
    @Test
    void registerWrongCaptchaReturns422() throws Exception {
        HttpResponse<String> res = postJson("/auth/register",
                form("email", "c@d.com", "password", "secret123",
                        "captcha", "zzzzz", "player_name", "Tester"),
                sessionCookie("abcde"));

        assertEquals(422, res.statusCode(), res.body());
        assertTrue(errors(decode(res.body())).containsKey("captcha"),
                "验证码不匹配必须报错: " + errors(decode(res.body())));
    }

    // ================================================================ 忘记密码

    /** {@code forgot()}：mailer 非空 → 正常渲染。 */
    @Test
    void forgotPageRenders() throws Exception {
        HttpResponse<String> res = get("/auth/forgot");

        assertEquals(200, res.statusCode(), res.body());
        String body = res.body();
        assertTrue(body.contains("<title>忘记密码 - NeoSkin 测试站</title>"), head(body));
        assertTrue(body.contains("<main></main>"), "form 行占位");
        assertTrue(body.contains("id=\"blessing-extra\""), "extra 脚本块");
    }

    /** 未注册邮箱 → {@code {code:1, message:'该邮箱尚未注册'}}（不碰 SMTP）。 */
    @Test
    void forgotUnknownEmailReturnsCode1() throws Exception {
        HttpResponse<String> res = postJson("/auth/forgot",
                form("email", "nobody@example.com", "captcha", "abcde"),
                sessionCookie("abcde"));

        assertEquals(200, res.statusCode(), res.body());
        Map<String, Object> body = decode(res.body());
        assertEquals(1, num(body.get("code")), res.body());
        assertEquals("该邮箱尚未注册", body.get("message"));
    }

    /** 校验失败 → 422（邮箱格式错）。 */
    @Test
    void forgotInvalidEmailReturns422() throws Exception {
        HttpResponse<String> res = postJson("/auth/forgot",
                form("email", "not-an-email", "captcha", "abcde"),
                sessionCookie("abcde"));

        assertEquals(422, res.statusCode(), res.body());
        assertTrue(errors(decode(res.body())).containsKey("email"),
                "邮箱格式必须报错: " + errors(decode(res.body())));
    }

    // ================================================================ 重置密码

    /** 没有签名 → 403（{@code hasValidSignature(false)} 拦截）。 */
    @Test
    void resetWithoutSignatureIsForbidden() throws Exception {
        int uid = seedUser("reset@example.com", "secret123", 0);

        HttpResponse<String> res = get("/auth/reset/" + uid);

        assertEquals(403, res.statusCode(), res.body());
    }

    /** 签名被篡改 → 403。 */
    @Test
    void resetWithTamperedSignatureIsForbidden() throws Exception {
        int uid = seedUser("reset2@example.com", "secret123", 0);
        String path = signedPath("auth.reset", uid, 3600).replaceAll("signature=[0-9a-f]+",
                "signature=deadbeef");

        HttpResponse<String> res = get(path);

        assertEquals(403, res.statusCode(), res.body());
    }

    /** 签名过期 → 403。 */
    @Test
    void resetWithExpiredSignatureIsForbidden() throws Exception {
        int uid = seedUser("reset3@example.com", "secret123", 0);
        // expires 在过去
        String path = signedPath("auth.reset", uid, -60);

        HttpResponse<String> res = get(path);

        assertEquals(403, res.statusCode(), res.body());
    }

    /** 合法签名 → 200，标题 + {@code auth.reset.message} 里的昵称。 */
    @Test
    void resetWithValidSignatureRenders() throws Exception {
        int uid = seedUser("reset4@example.com", "secret123", 0);
        setNickname(uid, "重置者");

        HttpResponse<String> res = get(signedPath("auth.reset", uid, 3600));

        assertEquals(200, res.statusCode(), res.body());
        String body = res.body();
        assertTrue(body.contains("<title>重置密码 - NeoSkin 测试站</title>"), head(body));
        // 模板里曾把 ${user.nickname} 嵌进 ${...}，会 500
        assertTrue(body.contains("重置者，在这里重置你的密码"), head(body));
        assertTrue(body.contains("<main></main>"), "form 行占位");
    }

    // ================================================================ 验证码

    /** {@code captcha()}：image/jpeg + {@code Cache-Control: no-store}（与源 AuthController.php:333 一致）。 */
    @Test
    void captchaReturnsJpeg() throws Exception {
        HttpResponse<byte[]> res = getBytes("/auth/captcha");

        assertEquals(200, res.statusCode());
        assertEquals("image/jpeg", res.headers().firstValue("content-type").orElse(null));
        assertEquals("no-store",
                res.headers().firstValue("cache-control").orElse(null));
        byte[] body = res.body();
        assertTrue(body.length > 100, "必须是真的 JPEG: " + body.length);
        // JPEG 的 SOI 标记
        assertEquals((byte) 0xFF, body[0]);
        assertEquals((byte) 0xD8, body[1]);
    }

    /**
     * 验证码写进会话且可被消费：{@code captcha()} 之后，用<b>同一个</b>会话
     * 提交注册，验证码校验能通过（不会报 {@code errors.captcha}）。
     *
     * <p>phrase 是随机的，所以从会话文件里读回来 —— 这正是要验证的：
     * {@code captcha()} 把它写进了 {@code BS_SESSION} 对应的文件。</p>
     */
    @Test
    void captchaIsStoredInSession() throws Exception {
        HttpResponse<byte[]> img = getBytes("/auth/captcha");
        String cookie = sessionCookieOf(img);

        assertNotNull(cookie, "验证码必须回写会话 cookie");

        String phrase = captchaPhraseFromSession(cookie);
        assertNotNull(phrase, "会话里必须有 captcha 键");

        HttpResponse<String> res = postJson("/auth/register",
                form("email", "cap@example.com", "password", "secret123",
                        "captcha", phrase, "player_name", "Tester"),
                cookie);

        assertEquals(200, res.statusCode(), res.body());
        Map<String, Object> body = decode(res.body());
        assertEquals(0, num(body.get("code")), res.body());
        assertTrue(!errors(body).containsKey("captcha"),
                "正确验证码不该报错: " + errors(body));
    }

    // ================================================================ 绑定邮箱

    /** {@code auth/bind} 由 {@code ViewController} + uri 分流 → 渲染 {@code auth/bind}。 */
    @Test
    void bindViewRendersViaViewController() throws Exception {
        int uid = seedUser("bind@example.com", "secret123", 0);
        // EnsureEmailFilled：已有邮箱时访问 auth/bind 会被重定向到 /user，
        // 所以要测「渲染绑定页」必须先把邮箱清空（这正是该页存在的场景）。
        setEmail(uid, "");
        String cookie = loggedInCookie(uid);

        HttpResponse<String> res = getWithCookie("/auth/bind", cookie);

        assertEquals(200, res.statusCode(), res.body());
        String body = res.body();
        assertTrue(body.contains("<title>绑定邮箱 - NeoSkin 测试站</title>"), head(body));
        assertTrue(body.contains("你需要绑定邮箱地址以继续使用本站"), head(body));
    }

    /** 已有邮箱的用户访问 {@code auth/bind} → {@code EnsureEmailFilled} 302 到 {@code /user}。 */
    @Test
    void bindViewRedirectsWhenEmailAlreadyFilled() throws Exception {
        int uid = seedUser("filled@example.com", "secret123", 0);
        String cookie = loggedInCookie(uid);

        HttpResponse<String> res = getWithCookie("/auth/bind", cookie);

        assertEquals(302, res.statusCode(), res.body());
        assertTrue(res.headers().firstValue("location").orElse("").endsWith("/user"));
    }

    /** 未登录访问 {@code auth/bind} → {@code Authenticate} 302 到登录页。 */
    @Test
    void bindViewRedirectsAnonymousToLogin() throws Exception {
        HttpResponse<String> res = get("/auth/bind");

        assertEquals(302, res.statusCode(), res.body());
        String location = res.headers().firstValue("location").orElse("");
        assertTrue(location.endsWith("/auth/login"), "应跳登录页: " + location);
    }

    /** {@code fillEmail}：邮箱被占用 → 422。 */
    @Test
    void fillEmailDuplicateReturns422() throws Exception {
        int uid = seedUser("me@example.com", "secret123", 0);
        seedUser("taken@example.com", "secret123", 0);
        // 把当前用户邮箱清空，否则 EnsureEmailFilled 会把它重定向走
        setEmail(uid, "");
        String cookie = loggedInCookie(uid);

        HttpResponse<String> res = postJsonWithCookie("/auth/bind",
                form("email", "taken@example.com"), cookie);

        assertEquals(422, res.statusCode(), res.body());
        assertTrue(errors(decode(res.body())).containsKey("email"),
                "重复邮箱必须报错: " + errors(decode(res.body())));
    }

    /** {@code fillEmail} 成功 → 302 到 {@code /user}，邮箱落库。 */
    @Test
    void fillEmailSucceedsAndRedirects() throws Exception {
        int uid = seedUser("me2@example.com", "secret123", 0);
        setEmail(uid, "");
        String cookie = loggedInCookie(uid);

        HttpResponse<String> res = postJsonWithCookie("/auth/bind",
                form("email", "fresh@example.com"), cookie);

        assertEquals(302, res.statusCode(), res.body());
        assertTrue(res.headers().firstValue("location").orElse("").endsWith("/user"));

        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT email FROM users WHERE uid = ?")) {
            stmt.setInt(1, uid);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("fresh@example.com", rs.getString("email"));
            }
        }
    }

    // ================================================================ 工具

    private static Map<String, Object> decode(String body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) PhpJson.decode(body);
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errors(Map<String, Object> body) {
        Object errors = body.get("errors");
        return errors instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static int num(Object value) {
        return value instanceof Number n ? n.intValue() : -1;
    }

    private static String head(String body) {
        return body.substring(0, Math.min(1200, body.length()));
    }

    /** {@code application/x-www-form-urlencoded} 的键值对拼装。 */
    private static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(kv[i], StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * 建一个已播种 {@code captcha} 的会话，返回可用的 {@code Cookie} 头值。
     *
     * <p>{@code captcha} 是 {@code CaptchaRule} 从会话里 pull 的，所以每个
     * 需要过验证码的请求都得配一个新会话（pull 会消费掉）。</p>
     */
    private String sessionCookie(String captcha) {
        FileSessionHandler handler = new FileSessionHandler(sessionDir, 120);
        SessionStore store = new SessionStore("BS_SESSION", handler)
                .setId(SessionStore.generateSessionId());
        store.start();
        store.put("captcha", captcha);
        store.save();

        return "BS_SESSION=" + cookies.encrypt("BS_SESSION", store.getId());
    }

    /** 建一个已登录会话（{@code login_web_<sha1>} = uid）。 */
    private String loggedInCookie(int uid) {
        FileSessionHandler handler = new FileSessionHandler(sessionDir, 120);
        SessionStore store = new SessionStore("BS_SESSION", handler)
                .setId(SessionStore.generateSessionId());
        store.start();
        store.put(com.neoskin.web.auth.SessionGuard.SESSION_KEY, (long) uid);
        store.save();

        return "BS_SESSION=" + cookies.encrypt("BS_SESSION", store.getId());
    }

    /** 从响应里取会话 cookie（形如 {@code BS_SESSION=...}）。 */
    private static String sessionCookieOf(HttpResponse<?> res) {
        return res.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith("BS_SESSION="))
                .map(h -> h.split(";", 2)[0])
                .findFirst().orElse(null);
    }

    /**
     * 从会话 cookie 反查 {@code captcha} 值：解密 cookie 拿会话 id，
     * 再按 {@code FileSessionHandler} 读出属性包。
     *
     * <p>{@code captcha()} 的 phrase 是随机的，测试只能这样取回。</p>
     */
    private String captchaPhraseFromSession(String cookieHeader) {
        String encrypted = cookieHeader.substring("BS_SESSION=".length());
        Object id = cookies.encrypter().decrypt(encrypted, false);
        // 加密值前面还带 CookieValuePrefix::create(name, key)，必须先剥掉，
        // 否则拿到的「id」长度不对，SessionStore.setId 会当成非法 id 换新的。
        String plainId = com.neoskin.web.cookie.CookieValuePrefix.remove(
                com.neoskin.support.PhpString.cast(id));

        SessionStore store = new SessionStore("BS_SESSION",
                new FileSessionHandler(sessionDir, 120)).setId(plainId);
        store.start();
        Object phrase = store.get("captcha");

        return phrase == null ? null : String.valueOf(phrase);
    }

    /** 用 {@link SignedUrl} 生成带签名的路径（{@code absolute=false}）。 */
    private String signedPath(String routeName, int uid, long ttlSeconds) {
        long expiry = System.currentTimeMillis() / 1000L + ttlSeconds;

        return SignedUrl.temporarySignedRoute(routeName, expiry,
                Map.of("uid", uid), false,
                RouteUrlGenerator.forHost("127.0.0.1:" + port), APP_KEY);
    }

    private int seedUser(String email, String rawPassword, int permission) throws Exception {
        String hash = new BcryptCipher(10).hash(rawPassword, "");
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO users (`email`, `nickname`, `score`, `avatar`, `password`,"
                             + " `ip`, `permission`, `last_sign_at`, `register_at`)"
                             + " VALUES (?, '', 0, 0, ?, '127.0.0.1', ?,"
                             + " '2026-01-01 00:00:00', '2026-01-01 00:00:00')",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, email);
            stmt.setString(2, hash);
            stmt.setInt(3, permission);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : 0;
            }
        }
    }

    private void setNickname(int uid, String nickname) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE users SET nickname = ? WHERE uid = ?")) {
            stmt.setString(1, nickname);
            stmt.setInt(2, uid);
            stmt.executeUpdate();
        }
    }

    private void setEmail(int uid, String email) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE users SET email = ? WHERE uid = ?")) {
            stmt.setString(1, email);
            stmt.setInt(2, uid);
            stmt.executeUpdate();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> getWithCookie(String path, String cookie) throws Exception {
        return send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", cookie)
                .GET(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<byte[]> getBytes(String path) throws Exception {
        return send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** 以 JSON 期望 + 同源来源提交表单（同源即免 CSRF，与前端 fetch 一致）。 */
    private HttpResponse<String> postJson(String path, String body, String cookie)
            throws Exception {
        return postJsonWithCookie(path, body, cookie);
    }

    private HttpResponse<String> postJsonWithCookie(String path, String body, String cookie)
            throws Exception {
        return send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Cookie", cookie == null ? "" : cookie)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private <T> HttpResponse<T> send(HttpRequest.Builder builder,
            HttpResponse.BodyHandler<T> handler) throws Exception {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(builder.build(), handler);
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
