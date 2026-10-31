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
import com.neoskin.db.ReportRepository;
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
 * {@link ReportController} 的端到端测试。
 *
 * <p>锁定：举报提交（校验/重复/积分）、我的举报页（状态文案 + Bootstrap 分页）、
 * 管理端搜索串列表（关联模型），以及审核的 reject/delete/ban 三条分支与积分结算。</p>
 */
class ReportControllerTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private AppPaths paths;
    private Option option;
    private UserRepository users;
    private TextureRepository textures;
    private ReportRepository reports;
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

        paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());
        Files.createDirectories(paths.textures());
        Files.createFile(paths.installLock());

        option = new Option(ds, schema, paths);
        Translator translator = new Translator(new TranslationFileLoader(), "zh_CN", "en");
        JavaScript javaScript = new JavaScript(paths, translator, null);
        BlessingFilter filter = new BlessingFilter();
        users = new UserRepository(ds);
        textures = new TextureRepository(ds);
        reports = new ReportRepository(ds, users, textures);
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
        HandlerResolver reportResolver = ReportController.resolver(option, translator, users,
                textures, reports, paths, ds, javaScript, filter, false, "Asia/Shanghai");
        HandlerResolver resolver = new HandlerResolver() {
            @Override
            public Handler<RoutingContext> resolve(String action) {
                Handler<RoutingContext> handler = auth.resolve(action);

                return handler != null ? handler : reportResolver.resolve(action);
            }

            @Override
            public Handler<RoutingContext> resolve(String action, String uri) {
                Handler<RoutingContext> handler = auth.resolve(action, uri);

                return handler != null ? handler : reportResolver.resolve(action, uri);
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

    // ------------------------------------------------------------- submit

    @Test
    void submitCreatesReportAndDeductsScore() throws Exception {
        int uploader = seedUser("uploader@a.com", UserRepository.NORMAL, 100);
        int reporter = seedUser("reporter@a.com", UserRepository.NORMAL, 100);
        int tid = seedTexture(uploader);
        option.set("reporter_score_modification", "-5");
        String cookie = login("reporter@a.com");

        HttpResponse<String> res = post("/skinlib/report",
                "tid=" + tid + "&reason=" + enc("不合适"), cookie, true);
        assertEquals(200, res.statusCode(), res.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        assertEquals(0, ((Number) body.get("code")).intValue());
        assertEquals("举报已提交，请等待管理员处理", body.get("message"));

        ReportRepository.Row row = lastReport();
        assertEquals(tid, row.tid());
        assertEquals(uploader, row.uploader());
        assertEquals(reporter, row.reporter());
        assertEquals("不合适", row.reason());
        assertEquals(ReportRepository.PENDING, row.status());
        assertEquals(95, users.findById(reporter).score(), "负积分扣减");

        // 重复举报 → code 1
        @SuppressWarnings("unchecked")
        Map<String, Object> dup = (Map<String, Object>) PhpJson.decode(
                post("/skinlib/report", "tid=" + tid + "&reason=x", cookie, true).body());
        assertEquals(1, ((Number) dup.get("code")).intValue());
    }

    @Test
    void submitValidatesAndChecksScore() throws Exception {
        int uploader = seedUser("uploader@a.com", UserRepository.NORMAL, 100);
        int reporter = seedUser("reporter@a.com", UserRepository.NORMAL, 1);
        int tid = seedTexture(uploader);
        String cookie = login("reporter@a.com");

        // 不存在的 tid → 422
        assertEquals(422, post("/skinlib/report", "tid=9999&reason=x", cookie, true)
                .statusCode());
        // 缺 reason → 422
        assertEquals(422, post("/skinlib/report", "tid=" + tid, cookie, true).statusCode());

        // 积分不足（-50，仅 1 分）→ code 1
        option.set("reporter_score_modification", "-50");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(
                post("/skinlib/report", "tid=" + tid + "&reason=x", cookie, true).body());
        assertEquals(1, ((Number) body.get("code")).intValue());
        assertEquals(0, countReports());
    }

    @Test
    void submitIsRejectedForUnauthenticated() throws Exception {
        int uploader = seedUser("uploader@a.com", UserRepository.NORMAL, 100);
        int tid = seedTexture(uploader);

        assertEquals(401, post("/skinlib/report", "tid=" + tid + "&reason=x", "", true)
                .statusCode());
    }

    // -------------------------------------------------------------- track

    @Test
    void trackListsOwnReportsWithStatusAndPagination() throws Exception {
        int uploader = seedUser("uploader@a.com", UserRepository.NORMAL, 100);
        int reporter = seedUser("reporter@a.com", UserRepository.NORMAL, 100);
        int tid = seedTexture(uploader);
        String cookie = login("reporter@a.com");

        assertEquals(200, post("/skinlib/report", "tid=" + tid + "&reason=" + enc("理由甲"),
                cookie, true).statusCode());

        HttpResponse<String> page = get("/user/reports", cookie);
        assertEquals(200, page.statusCode(), page.body());
        String html = page.body();
        assertTrue(html.contains("我的举报"), "标题");
        assertTrue(html.contains("理由甲"));
        assertTrue(html.contains("正在处理"), "status=0 的中文文案");
        assertFalse(html.contains("<ul class=\"pagination\">"), "只有一页时 links() 为空");

        // 再造 11 条 → 分页链接出现
        for (int i = 0; i < 11; i++) {
            reports.insert(seedTexture(uploader), uploader, reporter, "r" + i,
                    ReportRepository.PENDING, PhpDate.now("Asia/Shanghai"));
        }
        String paged = get("/user/reports", cookie).body();
        assertTrue(paged.contains("<ul class=\"pagination\">"), "应出现 Bootstrap 分页");
        assertTrue(paged.contains("?page=2"), "第 2 页链接");

        // 他人看不到我的举报
        String other = get("/user/reports", login("uploader@a.com")).body();
        assertFalse(other.contains("理由甲"));
    }

    // ------------------------------------------------------------- manage

    @Test
    void manageListsReportsWithRelationsAndSearchString() throws Exception {
        int admin = seedUser("admin@a.com", UserRepository.ADMIN, 100);
        int uploader = seedUser("uploader@a.com", UserRepository.NORMAL, 100);
        int reporter = seedUser("reporter@a.com", UserRepository.NORMAL, 100);
        int tid = seedTexture(uploader);
        long pending = reports.insert(tid, uploader, reporter, "理由", ReportRepository.PENDING,
                PhpDate.now("Asia/Shanghai"));
        long resolved = reports.insert(tid, uploader, reporter, "已处理", ReportRepository.RESOLVED,
                PhpDate.now("Asia/Shanghai"));
        String cookie = login("admin@a.com");

        String body = get("/admin/reports/list?q=" + enc("status:0 sort:-report_at"), cookie)
                .body();
        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) PhpJson.decode(body);
        assertEquals(9, ((Number) page.get("per_page")).intValue());
        assertEquals(1, ((Number) page.get("total")).intValue());
        PhpJson.Array data = (PhpJson.Array) page.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) data.get(0L);
        assertEquals(pending, ((Number) row.get("id")).longValue());
        assertNotNull(row.get("texture"));
        assertNotNull(row.get("texture_uploader"));
        assertNotNull(row.get("informer"));
        @SuppressWarnings("unchecked")
        Map<String, Object> texture = (Map<String, Object>) row.get("texture");
        assertEquals(tid, ((Number) texture.get("tid")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> informer = (Map<String, Object>) row.get("informer");
        assertEquals("reporter", informer.get("nickname"));

        // status:1 只返回已处理
        @SuppressWarnings("unchecked")
        Map<String, Object> resolvedPage = (Map<String, Object>) PhpJson.decode(
                get("/admin/reports/list?q=" + enc("status:1"), cookie).body());
        assertEquals(1, ((Number) resolvedPage.get("total")).intValue());
        assertEquals(resolved, ((Number) ((Map<?, ?>) ((PhpJson.Array) resolvedPage.get("data"))
                .get(0L)).get("id")).longValue());
    }

    @Test
    void manageIsAdminOnly() throws Exception {
        seedUser("user@a.com", UserRepository.NORMAL, 100);
        assertEquals(403, get("/admin/reports/list", login("user@a.com")).statusCode());
        assertEquals(302, get("/admin/reports/list", "").statusCode());
    }

    // ------------------------------------------------------------- review

    @Test
    void reviewRejectRestoresPositiveReporterScore() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN, 100);
        int uploader = seedUser("uploader@a.com", UserRepository.NORMAL, 100);
        int reporter = seedUser("reporter@a.com", UserRepository.NORMAL, 100);
        int tid = seedTexture(uploader);
        long id = reports.insert(tid, uploader, reporter, "理由", ReportRepository.PENDING,
                PhpDate.now("Asia/Shanghai"));
        option.set("reporter_score_modification", "10");
        String cookie = login("admin@a.com");

        HttpResponse<String> res = put("/admin/reports/" + id, "action=reject", cookie, true);
        assertEquals(200, res.statusCode(), res.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        assertEquals(0, ((Number) body.get("code")).intValue());
        assertEquals(2, ((Number) ((Map<?, ?>) body.get("data")).get("status")).intValue());
        assertEquals(ReportRepository.REJECTED, reports.findById(id).status());
        assertEquals(90, users.findById(reporter).score(), "拒绝举报时扣回奖励");
    }

    @Test
    void reviewDeleteRemovesTextureAndSettlesScores() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN, 100);
        int uploader = seedUser("uploader@a.com", UserRepository.NORMAL, 100);
        int reporter = seedUser("reporter@a.com", UserRepository.NORMAL, 100);
        int tid = seedTexture(uploader);
        String hash = textures.findByTid(tid).hash();
        Path textureFile = paths.textures().resolve(hash);
        assertTrue(Files.exists(textureFile));
        long id = reports.insert(tid, uploader, reporter, "理由", ReportRepository.PENDING,
                PhpDate.now("Asia/Shanghai"));
        option.set("reporter_score_modification", "-5");
        option.set("reporter_reward_score", "20");
        String cookie = login("admin@a.com");

        HttpResponse<String> res = put("/admin/reports/" + id, "action=delete", cookie, true);
        assertEquals(200, res.statusCode(), res.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        assertEquals(1, ((Number) ((Map<?, ?>) body.get("data")).get("status")).intValue());

        assertEquals(null, textures.findByTid(tid), "材质记录被删除");
        assertFalse(Files.exists(textureFile), "材质文件被删除");
        assertEquals(ReportRepository.RESOLVED, reports.findById(id).status());
        // 退还 5 + 奖励 20
        assertEquals(125, users.findById(reporter).score());
    }

    @Test
    void reviewDeleteWhenTextureAlreadyGoneReturnsMessage() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN, 100);
        int uploader = seedUser("uploader@a.com", UserRepository.NORMAL, 100);
        int reporter = seedUser("reporter@a.com", UserRepository.NORMAL, 100);
        int tid = seedTexture(uploader);
        long id = reports.insert(tid, uploader, reporter, "理由", ReportRepository.PENDING,
                PhpDate.now("Asia/Shanghai"));
        textures.deleteByTid(tid);
        option.set("reporter_score_modification", "-5");
        option.set("reporter_reward_score", "20");
        String cookie = login("admin@a.com");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(
                put("/admin/reports/" + id, "action=delete", cookie, true).body());
        assertEquals("请求的材质已被删除", body.get("message"));
        assertEquals(ReportRepository.RESOLVED, reports.findById(id).status());
        assertEquals(105, users.findById(reporter).score(), "只退分，不给奖励");
    }

    @Test
    void reviewBanChecksPermission() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN, 100);
        int uploader = seedUser("uploader@a.com", UserRepository.NORMAL, 100);
        int reporter = seedUser("reporter@a.com", UserRepository.NORMAL, 100);
        int otherAdmin = seedUser("admin2@a.com", UserRepository.ADMIN, 100);
        int tid = seedTexture(uploader);
        long id = reports.insert(tid, uploader, reporter, "理由", ReportRepository.PENDING,
                PhpDate.now("Asia/Shanghai"));
        String cookie = login("admin@a.com");

        @SuppressWarnings("unchecked")
        Map<String, Object> ok = (Map<String, Object>) PhpJson.decode(
                put("/admin/reports/" + id, "action=ban", cookie, true).body());
        assertEquals(0, ((Number) ok.get("code")).intValue());
        assertEquals(UserRepository.BANNED, users.findById(uploader).permission());

        // 同级上传者（另一个管理员）→ code 1
        int tid2 = seedTexture(otherAdmin);
        long id2 = reports.insert(tid2, otherAdmin, reporter, "理由2",
                ReportRepository.PENDING, PhpDate.now("Asia/Shanghai"));
        @SuppressWarnings("unchecked")
        Map<String, Object> denied = (Map<String, Object>) PhpJson.decode(
                put("/admin/reports/" + id2, "action=ban", cookie, true).body());
        assertEquals(1, ((Number) denied.get("code")).intValue());

        // 上传者已不存在 → code 1
        long id3 = reports.insert(tid, 99999, reporter, "理由3", ReportRepository.PENDING,
                PhpDate.now("Asia/Shanghai"));
        @SuppressWarnings("unchecked")
        Map<String, Object> missing = (Map<String, Object>) PhpJson.decode(
                put("/admin/reports/" + id3, "action=ban", cookie, true).body());
        assertEquals(1, ((Number) missing.get("code")).intValue());
    }

    @Test
    void reviewValidatesActionAndTarget() throws Exception {
        seedUser("admin@a.com", UserRepository.ADMIN, 100);
        int uploader = seedUser("uploader@a.com", UserRepository.NORMAL, 100);
        int reporter = seedUser("reporter@a.com", UserRepository.NORMAL, 100);
        long id = reports.insert(seedTexture(uploader), uploader, reporter, "理由",
                ReportRepository.PENDING, PhpDate.now("Asia/Shanghai"));
        String cookie = login("admin@a.com");

        assertEquals(422, put("/admin/reports/" + id, "action=foo", cookie, true).statusCode());
        assertEquals(404, put("/admin/reports/99999", "action=reject", cookie, true)
                .statusCode());
        assertEquals(404, put("/admin/reports/not-a-number", "action=reject", cookie, true)
                .statusCode());
    }

    // ================================================================ 工具

    private int seedUser(String email, int permission, int score) throws Exception {
        String hash = cipher.hash("secret123", "");
        String sql = "INSERT INTO users (`email`, `nickname`, `score`, `avatar`, `password`,"
                + " `ip`, `permission`, `last_sign_at`, `register_at`, `verified`)"
                + " VALUES (?, ?, ?, 0, ?, '127.0.0.1', ?,"
                + " '2026-01-01 00:00:00', '2026-01-01 00:00:00', 1)";
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            stmt.setString(2, email.substring(0, email.indexOf('@')));
            stmt.setInt(3, score);
            stmt.setString(4, hash);
            stmt.setInt(5, permission);
            stmt.executeUpdate();
        }

        return users.findByEmail(email).uid();
    }

    private int seedTexture(int uploader) throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G'};
        String hash = "hash" + System.nanoTime();
        Files.write(paths.textures().resolve(hash), png);
        long tid = textures.insert("t", "steve", hash, png.length, uploader, true,
                "2024-01-01 00:00:00");

        return (int) tid;
    }

    private ReportRepository.Row lastReport() throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM reports ORDER BY id DESC LIMIT 1");
             ResultSet rs = stmt.executeQuery()) {
            assertTrue(rs.next());

            return new ReportRepository.Row(rs.getLong("id"), rs.getInt("tid"),
                    rs.getInt("uploader"), rs.getInt("reporter"), rs.getString("reason"),
                    rs.getInt("status"), rs.getString("report_at"));
        }
    }

    private int countReports() throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM reports");
             ResultSet rs = stmt.executeQuery()) {
            rs.next();

            return rs.getInt(1);
        }
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

    private HttpResponse<String> post(String path, String body, String cookie, boolean json)
            throws Exception {
        return send("POST", path, body, cookie, json);
    }

    private HttpResponse<String> put(String path, String body, String cookie, boolean json)
            throws Exception {
        return send("PUT", path, body, cookie, json);
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

    private HttpResponse<String> send(String method, String path, String body, String cookie,
            boolean json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Sec-Fetch-Site", "same-origin");
        if (json) {
            builder.header("Accept", "application/json");
        }
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
