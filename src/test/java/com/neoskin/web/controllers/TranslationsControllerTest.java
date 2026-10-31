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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.service.session.FileSessionHandler;
import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.Routes;
import com.neoskin.web.VertxRouteRegistry;
import com.neoskin.web.i18n.DbTranslationLoader;
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.LanguageLine;
import com.neoskin.web.i18n.TranslationFileLoader;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.EncryptCookies;
import com.neoskin.web.middleware.MiddlewareChain;
import com.neoskin.web.middleware.StartSession;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

/**
 * {@link TranslationsController} 的端到端接线测试（真实 Vert.x + SQLite）。
 *
 * <p>锁定的源行为：</p>
 * <ol>
 *   <li>{@code GET list} → {@code paginate(10)} 的 JSON，含 current_page/data/last_page 等；</li>
 *   <li>{@code POST} 校验失败：XHR → 422 + {@code {message, errors}}；表单 → 302；</li>
 *   <li>{@code POST} 成功 → 302 {@code /admin/i18n} + session success；</li>
 *   <li>{@code PUT {line}} / {@code DELETE {line}} → {@code json(trans(...), 0)}；</li>
 *   <li>group 为 {@code front-end} 时触发 {@code JavaScript::resetTime}；</li>
 *   <li>{@code {line}} 不存在 → 404（路由模型绑定）。</li>
 * </ol>
 */
class TranslationsControllerTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private int port;
    private HikariDataSource ds;
    private Translator translator;
    private JavaScript javaScript;
    private DbTranslationLoader dbLoader;
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

        // 先建 DB 覆盖层，再注入 Translator —— 后台改的翻译必须能立刻反映到 trans()
        dbLoader = new DbTranslationLoader(ds, paths, "en");
        translator = new Translator(new TranslationFileLoader(), dbLoader, "zh_CN", "en");
        javaScript = new JavaScript(paths, translator, null);

        vertx = Vertx.vertx();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        // 32 字节全零前缀的 AES-256 密钥（APP_KEY 的形态），会话 cookie 加解密用
        EncryptCookies cookies = new EncryptCookies(
                new com.neoskin.service.cipher.PhpEncrypter(key, "aes-256-cbc"));
        Map<String, Handler<RoutingContext>> registry = new HashMap<>();
        MiddlewareChain.connectValueLayer(registry, cookies, dir.resolve("sessions"));

        HandlerResolver resolver = TranslationsController.resolver(
                ds, translator, "Asia/Shanghai", dbLoader::invalidateGroup, javaScript);

        VertxRouteRegistry.BuildResult built = VertxRouteRegistry.build(
                routes, resolver, vertx, List.of(), registry);
        port = listen(built.router());
    }

    @AfterEach
    void tearDown() throws Exception {
        ds.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ list

    @Test
    void listReturnsPaginationShape() throws Exception {
        seed("general", "a.b", Map.of("zh_CN", "甲"));

        HttpResponse<String> res = send("GET", "/admin/i18n/list", null, Map.of());
        assertEquals(200, res.statusCode());

        Object body = PhpJson.decode(res.body());
        assertTrue(body instanceof Map<?, ?> m && m.containsKey("current_page")
                && m.containsKey("data") && m.containsKey("last_page")
                && m.containsKey("per_page") && m.containsKey("total")
                && m.containsKey("from") && m.containsKey("to"),
                "LengthAwarePaginator 字段齐全: " + res.body());

        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) body;
        assertEquals(10, ((Number) m.get("per_page")).intValue());
        assertEquals(1, ((Number) m.get("current_page")).intValue());
        assertEquals(1, ((Number) m.get("last_page")).intValue());
        assertEquals(1, ((Number) m.get("total")).intValue());
        assertEquals(1, ((Number) m.get("from")).intValue());
        assertEquals(1, ((Number) m.get("to")).intValue());

        // data 是 JSON 数组 → PhpJson.Array（连续 0..n-1 下标）
        Object data = m.get("data");
        assertTrue(data instanceof com.neoskin.support.PhpJson.Array, "data 必须是 JSON 数组");
        com.neoskin.support.PhpJson.Array rows = (com.neoskin.support.PhpJson.Array) data;
        assertEquals(1, rows.size());
        // 数组下标经 PHP 键归一化为 Long，不能用 Integer 0 取
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) rows.get(0L);
        assertEquals("general", row.get("group"));
        assertEquals("a.b", row.get("key"));
        assertNotNull(row.get("id"));
        assertNotNull(row.get("created_at"));
    }

    @Test
    void listPagesByTen() throws Exception {
        for (int i = 1; i <= 12; i++) {
            seed("g", "k" + i, Map.of("zh_CN", "v" + i));
        }
        HttpResponse<String> res = send("GET", "/admin/i18n/list?page=2", null, Map.of());
        assertEquals(200, res.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) PhpJson.decode(res.body());
        assertEquals(2, ((Number) m.get("current_page")).intValue());
        assertEquals(2, ((Number) m.get("last_page")).intValue());
        assertEquals(11, ((Number) m.get("from")).intValue());
        assertEquals(12, ((Number) m.get("to")).intValue());
        assertEquals(2, ((com.neoskin.support.PhpJson.Array) m.get("data")).size());
    }

    // ---------------------------------------------------------------- create

    @Test
    void createRedirectsAndFlashesSuccess() throws Exception {
        HttpResponse<String> res = send("POST", "/admin/i18n",
                form(Map.of("group", "auth", "key", "check.banned", "text", "被封了")),
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Sec-Fetch-Site", "same-origin"));

        assertEquals(302, res.statusCode());
        assertEquals("/admin/i18n", res.headers().firstValue("Location").orElse(null));

        // 落库：DB loader 能读到（flush 已生效）
        Map<String, Object> group = dbLoader.loadTranslations("zh_CN", "auth");
        assertEquals("被封了", ((Map<?, ?>) group.get("check")).get("banned"));
    }

    @Test
    void createValidationFailureReturns422ForJson() throws Exception {
        HttpResponse<String> res = send("POST", "/admin/i18n", "group=auth&key=k",
                Map.of("Content-Type", "application/x-www-form-urlencoded",
                        "Accept", "application/json", "Sec-Fetch-Site", "same-origin"));

        assertEquals(422, res.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        assertEquals("The given data was invalid.", body.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) body.get("errors");
        assertTrue(errors.containsKey("text"), "text 缺失 → 报错: " + errors);
        assertFalse(errors.containsKey("group"), "group 有值 → 不报错");
    }

    @Test
    void createValidationFailureRedirectsForForm() throws Exception {
        HttpResponse<String> res = send("POST", "/admin/i18n", "",
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Sec-Fetch-Site", "same-origin"));
        // 表单提交（非 XHR）→ 302
        assertEquals(302, res.statusCode());
    }

    @Test
    void createOnFrontEndGroupResetsJavaScriptTime() throws Exception {
        // 先生成一次，让缓存里记下非零的 sourceModified
        javaScript.generate("zh_CN");
        long before = javaScript.cachedTime("zh_CN");

        send("POST", "/admin/i18n",
                form(Map.of("group", "front-end", "key", "general.confirm", "text", "确定")),
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Sec-Fetch-Site", "same-origin"));

        assertEquals(0L, javaScript.cachedTime("zh_CN"),
                "front-end 组的写入必须 resetTime（缓存从 " + before + " 归零）");
    }

    /** 非 front-end 组不触发 resetTime。 */
    @Test
    void createOnOtherGroupKeepsJavaScriptTime() throws Exception {
        javaScript.generate("zh_CN");
        long before = javaScript.cachedTime("zh_CN");

        send("POST", "/admin/i18n",
                form(Map.of("group", "auth", "key", "check.banned", "text", "x")),
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Sec-Fetch-Site", "same-origin"));

        assertEquals(before, javaScript.cachedTime("zh_CN"),
                "非 front-end 组不应 resetTime");
    }

    // ---------------------------------------------------------------- update

    @Test
    void updateReturnsJsonWithZeroCode() throws Exception {
        int id = seed("auth", "check.banned", Map.of("zh_CN", "旧文案"));

        HttpResponse<String> res = send("PUT", "/admin/i18n/" + id,
                form(Map.of("text", "新文案")),
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Sec-Fetch-Site", "same-origin"));
        assertEquals(200, res.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        assertEquals(0, ((Number) body.get("code")).intValue());
        assertEquals("条目更新成功", body.get("message"), "trans('admin.i18n.updated') 的中文行");

        assertEquals("新文案", ((Map<?, ?>) dbLoader.loadTranslations("zh_CN", "auth")
                .get("check")).get("banned"));
    }

    /**
     * 端到端：admin 改了某条翻译后，页面渲染读到的必须是新文案。
     *
     * <p>这条锁的是「DB 覆盖层接进 Translator」—— 之前只读文件，
     * 后台改完 {@code language_lines} 页面完全不变。</p>
     */
    @Test
    void adminEditIsVisibleToTranslator() throws Exception {
        // 先造一条覆盖 admin.i18n.updated 的行
        int id = seed("admin", "i18n.updated", Map.of("zh_CN", "旧文案"));

        HttpResponse<String> res = send("PUT", "/admin/i18n/" + id,
                form(Map.of("text", "页面上的新文案")),
                Map.of("Content-Type", "application/x-www-form-urlencoded",
                        "Sec-Fetch-Site", "same-origin"));
        assertEquals(200, res.statusCode(), res.body());

        // 控制器在 save 后调用 invalidateGroup → 下一次 trans 必须读到新值
        assertEquals("页面上的新文案",
                translator.trans("admin.i18n.updated", Map.of(), null));
    }

    @Test
    void updateMissingTextIs422() throws Exception {
        int id = seed("auth", "check.banned", Map.of("zh_CN", "x"));
        HttpResponse<String> res = send("PUT", "/admin/i18n/" + id, "",
                Map.of("Content-Type", "application/x-www-form-urlencoded",
                        "Accept", "application/json", "Sec-Fetch-Site", "same-origin"));
        assertEquals(422, res.statusCode());
    }

    // ---------------------------------------------------------------- delete

    @Test
    void deleteReturnsJsonWithZeroCode() throws Exception {
        int id = seed("auth", "check.banned", Map.of("zh_CN", "x"));

        HttpResponse<String> res = send("DELETE", "/admin/i18n/" + id, null, Map.of("Sec-Fetch-Site", "same-origin"));
        assertEquals(200, res.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) PhpJson.decode(res.body());
        assertEquals(0, ((Number) body.get("code")).intValue());
        assertEquals("条目已删除", body.get("message"), "trans('admin.i18n.deleted') 的中文行");

        assertEquals(Map.of(), dbLoader.loadTranslations("zh_CN", "auth"));
    }

    // ------------------------------------------------------------ 路由模型绑定

    @Test
    void unknownLineIs404() throws Exception {
        assertEquals(404, send("PUT", "/admin/i18n/999999",
                form(Map.of("text", "x")),
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Sec-Fetch-Site", "same-origin")).statusCode());
        assertEquals(404, send("DELETE", "/admin/i18n/999999", null, Map.of("Sec-Fetch-Site", "same-origin")).statusCode());
        assertEquals(404, send("PUT", "/admin/i18n/not-a-number",
                form(Map.of("text", "x")),
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Sec-Fetch-Site", "same-origin")).statusCode());
    }

    // ================================================================ 工具

    /** 直接写一行，返回主键。 */
    private int seed(String group, String key, Map<String, String> text) throws Exception {
        LanguageLine line = new LanguageLine();
        line.setGroup(group);
        line.setKey(key);
        line.setText(new java.util.LinkedHashMap<>(text));
        line.save(ds, "Asia/Shanghai", dbLoader::invalidateGroup);
        return line.getId();
    }

    private static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private HttpResponse<String> send(String method, String path, String body,
            Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path));
        headers.forEach(builder::header);
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        builder.method(method, publisher);
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private int listen(io.vertx.ext.web.Router router) {
        java.util.concurrent.CompletableFuture<Integer> future =
                new java.util.concurrent.CompletableFuture<>();
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
