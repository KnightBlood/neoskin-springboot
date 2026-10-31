package com.neoskin.web.controllers;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.UserRepository;
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.Option;
import com.neoskin.service.PatchReleases;
import com.neoskin.service.Semver;
import com.neoskin.service.Unzip;
import com.neoskin.service.UpdatePackage;
import com.neoskin.service.UploadLimits;
import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.PhpUrlGenerator;
import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.middleware.StartSession;
import com.neoskin.web.view.PageView;
import com.neoskin.web.view.ViewContext;
import com.neoskin.web.view.ViewEngine;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\UpdateController} 的移植（patch 的 ZIP 在线更新）。
 *
 * <p>完整包流程：获取更新信息 → 下载到临时文件 → 校验 SHA-256 与版本 →
 * {@link UpdatePackage#prepare} 暂存 → 会话保存 pending → 安装；
 * 旧式补丁包（{@code package != full}）直接解压到站点根目录。</p>
 */
public final class UpdateController {

    /** {@code self::SPEC}。 */
    public static final int SPEC = 2;

    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

    private final Option option;
    private final Translator translator;
    private final UserRepository users;
    private final NeoSkinSettings settings;
    private final AppPaths paths;
    private final JavaScript javaScript;
    private final BlessingFilter filter;
    private final boolean debug;
    private final UpdatePackage packages;
    private final Unzip unzip = new Unzip();

    public UpdateController(Option option, Translator translator, UserRepository users,
            NeoSkinSettings settings, AppPaths paths, JavaScript javaScript,
            BlessingFilter filter, boolean debug) {
        this.option = option;
        this.translator = translator;
        this.users = users;
        this.settings = settings;
        this.paths = paths;
        this.javaScript = javaScript;
        this.filter = filter;
        this.debug = debug;
        this.packages = new UpdatePackage(paths, settings);
    }

    // ============================================================ page

    /** {@code GET admin/update}。 */
    public Handler<RoutingContext> showUpdatePage() {
        return ctx -> {
            try {
                String locale = translator.getLocale();
                Map<String, Object> info = getUpdateInfo();
                Map<String, Object> can = canUpdate(info.get("info"));

                Map<String, Object> viewInfo = new LinkedHashMap<>();
                viewInfo.put("latest", nested(info, "info", "latest"));
                viewInfo.put("current", settings.getVersion());

                Object error = info.get("error");
                if (error == null || String.valueOf(error).isEmpty()) {
                    error = can.get("reason");
                }
                // Twig 里空字符串为假；OGNL 里 "" 为真 → 收敛成 null 供 th:if 使用
                if (error != null && String.valueOf(error).isEmpty()) {
                    error = null;
                }

                SessionStore session = ctx.get(StartSession.CTX_KEY);
                Map<String, Object> vars = pageVars(ctx, locale,
                        translator.trans("general.check-update", Map.of(), locale));
                vars.put("info", viewInfo);
                if (error != null) {
                    vars.put("error", error);
                }
                vars.put("can_update", can.get("can"));
                Object pending = session == null ? null
                        : session.get("system_update_package");
                if (pending != null) {
                    vars.put("pending_package", pending);
                }
                List<Map<String, Object>> releases = new java.util.ArrayList<>();
                for (PatchReleases.Release release : PatchReleases.all()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("version", release.version());
                    item.put("date", release.date());
                    item.put("notes", release.notes());
                    releases.add(item);
                }
                vars.put("patch_releases", releases);
                if (error != null) {
                    vars.put("connection_error", translator.trans(
                            "admin.update.errors.connection",
                            Map.of("error", String.valueOf(error)), locale));
                }
                vars.put("upload_limits", packages.uploadLimits().current());
                vars.put("cautions_html", translator
                        .trans("admin.update.cautions.text", Map.of(), locale)
                        .replace("\n", "<br />\n"));
                render(ctx, "admin/update", vars);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ download

    /** {@code POST admin/update/download}。 */
    public Handler<RoutingContext> download() {
        return ctx -> {
            Path temp = null;
            try {
                Map<String, Object> info = getUpdateInfo();
                Object rawInfo = info.get("info");
                if (!Boolean.TRUE.equals(info.get("ok"))
                        || !Boolean.TRUE.equals(canUpdate(rawInfo).get("can"))) {
                    json(ctx, 200, message(1, trans("admin.update.info.up-to-date")));
                    return;
                }
                Map<?, ?> data = (Map<?, ?>) rawInfo;
                temp = Files.createTempFile("bs", ".zip");

                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10)).build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(
                                String.valueOf(data.get("url"))))
                        .timeout(Duration.ofMinutes(5)).GET().build();
                HttpResponse<Path> response = client.send(request,
                        HttpResponse.BodyHandlers.ofFile(temp));

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    json(ctx, 200, message(1, translator.trans(
                            "admin.download.errors.download",
                            Map.of("error", String.valueOf(response.statusCode())),
                            translator.getLocale())));
                    return;
                }

                if ("full".equals(String.valueOf(data.get("package")))) {
                    String checksum = String.valueOf(data.get("sha256") == null ? ""
                            : data.get("sha256"));
                    if (!SHA256.matcher(checksum).matches()
                            || !checksum.equalsIgnoreCase(sha256(temp))) {
                        throw new RuntimeException("运行包校验值与更新信息不一致，请重新检查更新。");
                    }
                    Map<String, Object> pending = packages.prepare(temp);
                    if (!String.valueOf(pending.get("version"))
                            .equals(String.valueOf(data.get("latest")))) {
                        throw new RuntimeException("运行包版本与更新信息不一致。");
                    }
                    SessionStore session = ctx.get(StartSession.CTX_KEY);
                    if (session != null) {
                        session.put("system_update_package", pending);
                    }

                    json(ctx, 200, message(0, "运行包已下载并校验，请确认后安装。",
                            Map.of("review", true)));
                    return;
                }

                unzip.extract(temp, paths.base());
                option.clearCache(); // 允许更新版本号

                json(ctx, 200, message(0, trans("admin.update.complete")));
            } catch (RuntimeException e) {
                json(ctx, 200, message(1, e.getMessage()));
            } catch (Exception e) {
                ctx.fail(e);
            } finally {
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
            }
        };
    }

    // ============================================================ upload

    /** {@code POST admin/update/upload}（multipart）。 */
    public Handler<RoutingContext> upload() {
        return ctx -> {
            try {
                SessionStore session = ctx.get(StartSession.CTX_KEY);
                com.neoskin.web.http.UploadedFile file = RequestInput.file(ctx, "package");
                long maxKb = (long) Math.ceil(
                        packages.uploadLimits().applicationBytes() / 1024.0);
                if (file == null) {
                    redirect(ctx, "update_error", "请选择 ZIP 运行包。");
                    return;
                }
                if (file.bytes().length > maxKb * 1024) {
                    redirect(ctx, "update_error", "运行包超过上传上限。");
                    return;
                }
                String name = file.originalFilename() == null ? "" : file.originalFilename();
                String extension = name.contains(".")
                        ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
                if (!"zip".equals(extension)) {
                    redirect(ctx, "update_error", "请选择 ZIP 运行包。");
                    return;
                }
                Path temp = Files.createTempFile("bs-upload", ".zip");
                try {
                    Files.write(temp, file.bytes());
                    Map<String, Object> info = packages.prepare(temp);
                    if (session != null) {
                        session.put("system_update_package", info);
                    }
                } finally {
                    Files.deleteIfExists(temp);
                }

                redirect(ctx, "update_notice", "运行包校验完成，请确认版本与校验值后安装。");
            } catch (RuntimeException e) {
                redirect(ctx, "update_error", e.getMessage() == null
                        ? "运行包处理失败，请检查文件和目录权限。" : e.getMessage());
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ install

    /** {@code POST admin/update/install}。 */
    public Handler<RoutingContext> install() {
        return ctx -> {
            try {
                SessionStore session = ctx.get(StartSession.CTX_KEY);
                String id = RequestInput.inputString(ctx, "id");
                if (id == null || id.length() != 32) {
                    redirect(ctx, "update_error", "更新任务格式错误。");
                    return;
                }
                Object pendingRaw = session == null ? null
                        : session.get("system_update_package");
                String pendingId = pendingRaw instanceof Map<?, ?> map
                        ? String.valueOf(map.get("id")) : "";
                if (!constantTimeEquals(pendingId, id)) {
                    ctx.fail(403);
                    return;
                }
                packages.install(id);
                option.clearCache();
                if (session != null) {
                    session.forget("system_update_package");
                }

                redirect(ctx, "update_notice", "系统程序已更新，版本检查与数据库升级将在后续请求中完成。");
            } catch (RuntimeException e) {
                redirect(ctx, "update_error", e.getMessage() == null
                        ? "安装失败，请检查站点目录权限与运行日志。" : e.getMessage());
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ 更新信息

    /** {@code getUpdateInfo()}。 */
    private Map<String, Object> getUpdateInfo() {
        String source = option.get("update_source") == null ? ""
                : String.valueOf(option.get("update_source"));
        if (source.isBlank()) {
            source = settings.getUpdateSource();
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Map.of("ok", false, "error",
                        "HTTP status code: " + response.statusCode());
            }
            Object decoded = PhpJson.decode(response.body());
            if (decoded instanceof Map<?, ?> map && map.get("spec") instanceof Number spec
                    && spec.intValue() == SPEC) {
                return Map.of("ok", true, "info", map);
            }

            return Map.of("ok", false,
                    "error", trans("admin.update.errors.spec"));
        } catch (Exception e) {
            return Map.of("ok", false, "error", "更新源连接失败");
        }
    }

    /** {@code canUpdate($info)}。 */
    private Map<String, Object> canUpdate(Object rawInfo) {
        if (!(rawInfo instanceof Map<?, ?> info)
                || !info.containsKey("php") || !info.containsKey("latest")) {
            return Map.of("can", false, "reason", "");
        }
        if (Semver.lessThan(phpVersion(), String.valueOf(info.get("php")))) {
            return Map.of("can", false, "reason", translator.trans(
                    "admin.update.errors.php",
                    Map.of("version", String.valueOf(info.get("php"))),
                    translator.getLocale()));
        }

        return Map.of("can", Semver.greaterThan(String.valueOf(info.get("latest")),
                settings.getVersion()), "reason", "");
    }

    private static String phpVersion() {
        return System.getProperty("neoskin.php.version", "8.5.0");
    }

    // ============================================================ helpers

    private Map<String, Object> pageVars(RoutingContext ctx, String locale, String title)
            throws Exception {
        String host = hostOf(ctx);
        String urlRoot = "http://" + host;
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        UserRepository.UserRow user = com.neoskin.web.middleware.Authenticate.currentUser(ctx);
        ViewContext vc = new ViewContext(translator, option,
                new PhpUrlGenerator(urlRoot, "http"),
                RouteUrlGenerator.forHost(host), locale,
                session == null ? "" : String.valueOf(session.token()), user, session);
        Map<String, Object> vars = PageView.variables(option, translator, javaScript, filter,
                user, locale, urlRoot, ctx.request().path(), debug, "admin", vc);
        vars.put("pageTitle", title);
        vars.put("errors", new AuthController.ErrorsView(
                com.neoskin.web.middleware.ShareErrorsFromSession.extract(
                        session == null ? null : session.get("errors"))));

        return vars;
    }

    private void render(RoutingContext ctx, String template, Map<String, Object> vars) {
        String html = ViewEngine.render(template, java.util.Locale.ROOT, vars);
        ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(Buffer.buffer(html, "UTF-8"));
    }

    private void redirect(RoutingContext ctx, String flashKey, String flashValue) {
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        if (session != null) {
            session.flash(flashKey, flashValue);
        }
        ctx.response().setStatusCode(302)
                .putHeader("Location", "http://" + hostOf(ctx) + "/admin/update")
                .putHeader("Cache-Control", "no-cache, private")
                .end();
    }

    private static Object nested(Map<String, Object> info, String outer, String inner) {
        Object value = info.get(outer);
        if (value instanceof Map<?, ?> map) {
            return map.get(inner);
        }

        return null;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }

        return diff == 0;
    }

    private String trans(String key) {
        return translator.trans(key, Map.of(), translator.getLocale());
    }

    private void json(RoutingContext ctx, int status, Object body) {
        String payload = PhpJson.encode(body, 0);
        ctx.response().setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(Buffer.buffer(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> message(int code, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", text);

        return body;
    }

    private static Map<String, Object> message(int code, String text,
            Map<String, Object> data) {
        Map<String, Object> body = message(code, text);
        body.put("data", data);

        return body;
    }

    private static String hostOf(RoutingContext ctx) {
        String host = ctx.request().getHeader("Host");

        return host == null || host.isEmpty() ? "localhost" : host;
    }

    public static HandlerResolver resolver(Option option, Translator translator,
            UserRepository users, NeoSkinSettings settings, AppPaths paths,
            JavaScript javaScript, BlessingFilter filter, boolean debug) {
        UpdateController c = new UpdateController(option, translator, users, settings, paths,
                javaScript, filter, debug);

        return action -> switch (action) {
            case "App\\Http\\Controllers\\UpdateController@showUpdatePage" ->
                    c.showUpdatePage();
            case "App\\Http\\Controllers\\UpdateController@download" -> c.download();
            case "App\\Http\\Controllers\\UpdateController@upload" -> c.upload();
            case "App\\Http\\Controllers\\UpdateController@install" -> c.install();
            default -> null;
        };
    }
}
