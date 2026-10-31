package com.neoskin.web.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import com.neoskin.db.SchemaInstaller;
import com.neoskin.db.UserRepository;
import com.neoskin.service.Option;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.Routes;
import com.neoskin.web.VertxRouteRegistry;
import com.neoskin.web.i18n.TranslationFileLoader;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.EncryptCookies;
import com.neoskin.web.middleware.LanguageMiddleware;
import com.neoskin.web.middleware.MiddlewareChain;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * 错误页体系的端到端测试：<b>真实 HTTP 请求</b> → 路由未命中 / 方法不对 →
 * {@link ErrorHandler} → 源站同款 HTML 或 JSON。
 *
 * <p>与 {@link ErrorPagesTest} 的分工：那个测「渲染出来的字节对不对」，
 * 这个测「真实请求能不能走到那个渲染」，也就是 404 / 405 / OPTIONS /
 * 双斜杠守卫 / JSON 分流这几条链路。</p>
 *
 * <p>所有断言都对着 PHP 实测值（见 PORT-NOTES §13）：</p>
 * <ul>
 *   <li>404 HTML：{@code <title>404 Not Found - Blessing Skin</title>}；</li>
 *   <li>404 JSON：4 空格缩进 + {@code {"message","exception":true,"trace":[]}}；</li>
 *   <li>405：{@code Allow: GET, HEAD}（逗号+空格），HTML 走 Symfony 页；</li>
 *   <li>OPTIONS 命中 → 200 空体 + {@code Allow: GET,HEAD,POST}（逗号无空格）；</li>
 *   <li>所有响应带 {@code Cache-Control: no-cache, private}。</li>
 * </ul>
 */
class ErrorHandlerTest {

    @TempDir
    Path dir;

    private final Routes routes = Routes.load();

    private Vertx vertx;
    private int port;
    private Option option;
    private Translator translator;
    private com.zaxxer.hikari.HikariDataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        Path file = dir.resolve("test.sqlite");
        Files.createFile(file);

        var hikari = new com.zaxxer.hikari.HikariConfig();
        hikari.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setMaximumPoolSize(1);
        ds = new com.zaxxer.hikari.HikariDataSource(hikari);

        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getDatabase().setConnection("sqlite");
        settings.getDatabase().setName(file.toAbsolutePath().toString());
        settings.getStorage().setRoot(dir.resolve("storage").toString());
        new SchemaInstaller(ds, settings).install();

        AppPaths paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());
        Files.createFile(paths.installLock());

        option = new Option(ds, new SchemaInstaller(ds, settings), paths);
        option.set("site_name", "Blessing Skin");

        translator = new Translator(new TranslationFileLoader(), "en", "en");
        option.set("cdn_address", "");

        // 中间件注册表：只挂会话 + 语言（够渲染错误页）
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        EncryptCookies cookies = new EncryptCookies(
                new com.neoskin.service.cipher.PhpEncrypter(key, "aes-256-cbc"));
        Map<String, Handler<RoutingContext>> registry = new HashMap<>();
        MiddlewareChain.connectValueLayer(registry, cookies, dir.resolve("sessions"),
                paths, settings.getVersion(), option, translator);

        UserRepository users = new UserRepository(ds);
        ErrorHandler handler = new ErrorHandler(
                new ErrorPages(option, translator, false), users);

        // 所有 action 都未移植 —— 只要路径没命中就走错误页
        HandlerResolver resolver = action -> null;

        VertxRouteRegistry.BuildResult built = VertxRouteRegistry.build(
                routes, resolver, vertx = Vertx.vertx(),
                List.of(new LanguageMiddleware()), registry, null, handler);
        port = listen(built.router());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (ds != null) {
            ds.close();
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        Option.clearCurrentLocale();
    }

    // ================================================================ 404

    /** 404 HTML：项目模板 {@code errors.404}，标题带站点名。 */
    @Test
    void notFoundRendersProjectTemplate() throws Exception {
        HttpResponse<String> res = get("/nope", "text/html");

        assertEquals(404, res.statusCode());
        assertTrue(ct(res).startsWith("text/html"), ct(res));
        assertEquals("no-cache, private",
                res.headers().firstValue("Cache-Control").orElse(""));

        String body = res.body();
        assertTrue(body.contains("<title>404 Not Found - Blessing Skin</title>"), body);
        assertTrue(body.contains("<h3 style=\"margin-top: 15vh;\">404 Not Found</h3>"), body);
        assertTrue(body.contains("Details: The route nope could not be found."), body);
        assertTrue(body.contains("/app/spectre.e885dd0.css"), "spectre 样式");
        assertTrue(body.contains("label-secondary"), "语言项全是 secondary（源里 lang_name 未定义）");
        // 未登录时没有 FAQ 链接
        assertFalse(body.contains("blessing.netlify.com"), "匿名不应看到 FAQ");
    }

    /** 404 JSON：pretty-print 4 空格，键序 message/exception/trace。 */
    @Test
    void notFoundRendersJson() throws Exception {
        HttpResponse<String> res = get("/nope", "application/json");

        assertEquals(404, res.statusCode());
        assertTrue(ct(res).startsWith("application/json"), ct(res));
        assertEquals("""
                {
                    "message": "The route nope could not be found.",
                    "exception": true,
                    "trace": []
                }""", res.body());
    }

    /**
     * {@code Accept: text/html,application/json} → HTML。
     *
     * <p>{@code wantsJson()} 只看<b>排序后第一项</b>，第一项是 {@code text/html}
     * 就不是 JSON —— 简单 {@code contains("json")} 会判错。</p>
     */
    @Test
    void acceptHtmlFirstWinsOverJson() throws Exception {
        HttpResponse<String> res = get("/nope", "text/html,application/json");

        assertEquals(404, res.statusCode());
        assertTrue(ct(res).startsWith("text/html"), ct(res));
    }

    /** XHR + {@code Accept: * / *} → JSON（{@code ajax() && acceptsAnyContentType()}）。 */
    @Test
    void xhrWithAnyContentTypeReturnsJson() throws Exception {
        HttpResponse<String> res = send("/nope", "GET", "*/*", Map.of(
                "X-Requested-With", "XMLHttpRequest"));

        assertEquals(404, res.statusCode());
        assertTrue(ct(res).startsWith("application/json"), ct(res));
        assertTrue(res.body().contains("\"exception\": true"), res.body());
    }

    /** XHR + {@code Accept: text/html} → HTML（不是 JSON）。 */
    @Test
    void xhrWithHtmlAcceptReturnsHtml() throws Exception {
        HttpResponse<String> res = send("/nope", "GET", "text/html", Map.of(
                "X-Requested-With", "XMLHttpRequest"));

        assertEquals(404, res.statusCode());
        assertTrue(ct(res).startsWith("text/html"), ct(res));
    }

    // ================================================================ 405

    /** 405 HTML：项目里没有 405 模板 → Symfony 通用页。 */
    @Test
    void methodNotAllowedRendersSymfonyPage() throws Exception {
        HttpResponse<String> res = send("/", "PUT", "text/html", Map.of());

        assertEquals(405, res.statusCode());
        assertEquals("GET, HEAD", res.headers().firstValue("Allow").orElse(""));
        assertEquals("no-cache, private",
                res.headers().firstValue("Cache-Control").orElse(""));

        String body = res.body();
        assertTrue(body.contains("<title>An Error Occurred: Method Not Allowed</title>"), body);
        assertTrue(body.contains(
                "<h2>The server returned a \"405 Method Not Allowed\".</h2>"), body);
    }

    /** 405 JSON：消息里带支持的动词列表。 */
    @Test
    void methodNotAllowedRendersJson() throws Exception {
        HttpResponse<String> res = send("/", "PUT", "application/json", Map.of());

        assertEquals(405, res.statusCode());
        assertEquals("GET, HEAD", res.headers().firstValue("Allow").orElse(""));
        assertTrue(res.body().contains(
                "\"message\": \"The PUT method is not supported for route /. "
                        + "Supported methods: GET, HEAD.\""), res.body());
    }

    // ================================================================ OPTIONS

    /** OPTIONS 命中已注册路径 → 200 空体 + {@code Allow}（逗号<b>不带</b>空格）。 */
    @Test
    void optionsOnExistingPathReturns200() throws Exception {
        HttpResponse<String> res = send("/auth/login", "OPTIONS", "text/html", Map.of());

        assertEquals(200, res.statusCode());
        assertEquals("GET,HEAD,POST", res.headers().firstValue("Allow").orElse(""));
        assertEquals("", res.body());
        // Symfony 的默认值也会挂在这个响应上（实测）
        assertEquals("no-cache, private",
                res.headers().firstValue("Cache-Control").orElse(""));
    }

    /** OPTIONS 打不存在的路径 → 404（与「路径存在」区分开）。 */
    @Test
    void optionsOnMissingPathReturns404() throws Exception {
        assertEquals(404, send("/nope", "OPTIONS", "text/html", Map.of()).statusCode());
    }

    // ================================================================ 斜杠

    /** 中间双斜杠：Vert.x 会折叠，源站不折叠 → 必须 404。 */
    @Test
    void innerDoubleSlashIsNotFound() throws Exception {
        HttpResponse<String> res = get("/auth//login", "application/json");

        assertEquals(404, res.statusCode());
        assertTrue(res.body().contains("The route auth//login could not be found."), res.body());
    }

    /** 前导双斜杠：源站也 404，文案里前导斜杠被 trim 掉。 */
    @Test
    void leadingDoubleSlashIsNotFound() throws Exception {
        HttpResponse<String> res = get("//auth/login", "application/json");

        assertEquals(404, res.statusCode());
        assertTrue(res.body().contains("The route auth/login could not be found."), res.body());
    }

    /** 尾斜杠：源站容忍任意数量 → 命中路由（未移植 → 501）。 */
    @Test
    void trailingSlashesAreTolerated() throws Exception {
        assertEquals(501, get("/auth/login/", "application/json").statusCode());
        assertEquals(501, get("/auth/login//", "application/json").statusCode());
    }

    // ================================================================ 419

    /** 419 走 Laravel 自带 {@code errors::minimal}（不是项目模板）。 */
    @Test
    void pageExpiredUsesLaravelMinimal() throws Exception {
        ErrorPages pages = new ErrorPages(option, translator, false);
        ErrorPages.Rendered rendered = pages.render(
                WebException.http(419, "CSRF token mismatch."),
                "http://localhost", "auth/login", Map.of(), null, null);

        assertEquals(419, rendered.status());
        assertTrue(rendered.body().contains("<title>Page Expired</title>"), rendered.body());
        assertTrue(rendered.body().contains("419"), "正文里的状态码");
    }

    /** PrettyPageException → 状态 200 + {@code errors.pretty}（IE 拦截页）。 */
    @Test
    void prettyPageReturnsStatus200() throws Exception {
        ErrorPages pages = new ErrorPages(option, translator, false);
        ErrorPages.Rendered rendered = pages.render(
                WebException.pretty("Your browser isn't supported.", 0),
                "http://localhost", "auth/login", Map.of(), null, null);

        assertEquals(200, rendered.status(), "PrettyPageException 必须是 200");
        assertTrue(rendered.body().contains("<title>Error occurred - Blessing Skin</title>"),
                rendered.body());
        assertTrue(rendered.body().contains("<p>Error code: 0</p>"), rendered.body());
        assertTrue(rendered.body().contains("browser isn&#039;t supported"),
                "单引号必须转成 &#039;");
    }

    /** 500 走项目模板 {@code errors.500}。 */
    @Test
    void serverErrorRendersProjectTemplate() throws Exception {
        ErrorPages pages = new ErrorPages(option, translator, false);
        ErrorPages.Rendered rendered = pages.render(
                WebException.http(500, "boom"), "http://localhost", "/", Map.of(), null, null);

        assertEquals(500, rendered.status());
        assertTrue(rendered.body().contains(
                "<title>500 Internal Server Error - Blessing Skin</title>"), rendered.body());
        assertTrue(rendered.body().contains("Details: boom"), rendered.body());
    }

    /** 403 走项目模板 {@code errors.403}。 */
    @Test
    void forbiddenRendersProjectTemplate() throws Exception {
        ErrorPages pages = new ErrorPages(option, translator, false);
        ErrorPages.Rendered rendered = pages.render(
                WebException.http(403, "You have no permission."),
                "http://localhost", "/admin", Map.of(), null, null);

        assertEquals(403, rendered.status());
        assertTrue(rendered.body().contains(
                "<title>403 Forbidden - Blessing Skin</title>"), rendered.body());
        assertTrue(rendered.body().contains("Details: You have no permission."), rendered.body());
    }

    // ================================================================ 工具

    private static String ct(HttpResponse<String> res) {
        return res.headers().firstValue("Content-Type").orElse("");
    }

    private HttpResponse<String> get(String path, String accept) throws Exception {
        return send(path, "GET", accept, Map.of());
    }

    private HttpResponse<String> send(String path, String method, String accept,
            Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("Accept", accept);
        headers.forEach(builder::header);

        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(builder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private int listen(Router router) throws Exception {
        CompletableFuture<Integer> bound = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(router).listen(0)
                .onSuccess(s -> bound.complete(s.actualPort()))
                .onFailure(bound::completeExceptionally);
        return bound.get(10, TimeUnit.SECONDS);
    }
}
