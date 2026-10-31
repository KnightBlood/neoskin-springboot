package com.neoskin.web.controllers;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.config.AppPaths;
import com.neoskin.db.UserRepository;
import com.neoskin.service.BlessingFilter;
import com.neoskin.service.MarkdownLite;
import com.neoskin.service.Option;
import com.neoskin.service.Plugin;
import com.neoskin.service.PluginManager;
import com.neoskin.service.Unzip;
import com.neoskin.service.session.SessionStore;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.PhpUrlGenerator;
import com.neoskin.web.RouteUrlGenerator;
import com.neoskin.web.error.WebException;
import com.neoskin.web.i18n.JavaScript;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.middleware.StartSession;
import com.neoskin.web.validation.Validator;
import com.neoskin.web.view.PageView;
import com.neoskin.web.view.ViewContext;
import com.neoskin.web.view.ViewEngine;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\PluginController} 的移植（清单层）。
 *
 * <p>插件管理接口本身可完整工作：列表、启用/禁用/删除、readme、上传 ZIP、按 URL
 * 安装。**插件代码不会被执行**（本工程没有 PHP 运行时）：{@code config} 端点与
 * 源一致地在没有配置时 404；即使声明了 PHP 配置类/视图，也只能 404（偏差记录）。</p>
 */
public final class PluginController {

    private final Option option;
    private final Translator translator;
    private final PluginManager plugins;
    private final AppPaths paths;
    private final JavaScript javaScript;
    private final BlessingFilter filter;
    private final UserRepository users;
    private final boolean debug;
    private final Unzip unzip = new Unzip();

    public PluginController(Option option, Translator translator, PluginManager plugins,
            AppPaths paths, JavaScript javaScript, BlessingFilter filter, UserRepository users,
            boolean debug) {
        this.option = option;
        this.translator = translator;
        this.plugins = plugins;
        this.paths = paths;
        this.javaScript = javaScript;
        this.filter = filter;
        this.users = users;
        this.debug = debug;
    }

    // ============================================================ config

    /** {@code config(PluginManager, $name)}：插件配置页（PHP 代码无法执行）。 */
    public Handler<RoutingContext> config() {
        return ctx -> {
            String name = ctx.pathParam("name");
            Plugin plugin = plugins.get(name);
            boolean available = plugin != null && plugin.isEnabled() && plugin.hasConfig();
            if (!available) {
                ctx.fail(WebException.http(404, trans("admin.plugins.operations.no-config-notice")));
                return;
            }
            // 源会执行插件 PHP 配置类或渲染 Blade 视图；Java 侧无法执行插件代码。
            ctx.fail(WebException.http(404, trans("admin.plugins.operations.no-config-notice")));
        };
    }

    // ============================================================ readme

    /** {@code readme(PluginManager, $name)}。 */
    public Handler<RoutingContext> readme() {
        return ctx -> {
            try {
                Plugin plugin = plugins.get(ctx.pathParam("name"));
                String readme = plugin == null ? null : plugin.getReadme();
                if (plugin == null || readme == null) {
                    throw WebException.http(404,
                            trans("admin.plugins.operations.no-readme-notice"));
                }
                String markdown = Files.readString(plugin.path().resolve(readme));
                String locale = translator.getLocale();
                Map<String, Object> vars = pageVars(ctx, locale,
                        translator.trans(plugin.title(), Map.of(), locale));
                vars.put("readme_html", MarkdownLite.render(markdown));
                render(ctx, "admin/plugin/readme", vars);
            } catch (WebException e) {
                ctx.fail(e);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ manage

    /** {@code POST admin/plugins/manage}。 */
    public Handler<RoutingContext> manage() {
        return ctx -> {
            try {
                String name = RequestInput.inputString(ctx, "name");
                String action = RequestInput.inputString(ctx, "action");
                Plugin plugin = plugins.get(name);
                if (plugin == null) {
                    json(ctx, 200, message(1, trans("admin.plugins.operations.not-found")));
                    return;
                }
                String title = translator.trans(plugin.title(), Map.of(),
                        translator.getLocale());

                switch (action == null ? "" : action) {
                    case "enable" -> {
                        Map<String, Map<String, Map<String, String>>> failure =
                                plugins.enable(name);
                        if (failure == null) {
                            json(ctx, 200, message(0, translator.trans(
                                    "admin.plugins.operations.enabled",
                                    Map.of("plugin", title), translator.getLocale())));
                        } else {
                            List<String> reason = plugins.formatUnresolved(
                                    failure.get("unsatisfied"), failure.get("conflicts"));
                            json(ctx, 200, message(1, trans(
                                    "admin.plugins.operations.unsatisfied.notice"),
                                    Map.of("reason", reason)));
                        }
                    }
                    case "disable" -> {
                        plugins.disable(name);
                        json(ctx, 200, message(0, translator.trans(
                                "admin.plugins.operations.disabled",
                                Map.of("plugin", title), translator.getLocale())));
                    }
                    case "delete" -> {
                        plugins.delete(name);
                        json(ctx, 200, message(0,
                                trans("admin.plugins.operations.deleted")));
                    }
                    default -> json(ctx, 200, message(1, trans("admin.invalid-action")));
                }
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ data

    /** {@code GET admin/plugins/data}。 */
    public Handler<RoutingContext> getPluginData() {
        return ctx -> {
            try {
                List<Object> data = new ArrayList<>();
                for (Plugin plugin : plugins.all().values()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", plugin.name());
                    item.put("title", translator.trans(plugin.title(), Map.of(),
                            translator.getLocale()));
                    item.put("description", plugin.description() == null ? ""
                            : translator.trans(plugin.description(), Map.of(),
                                    translator.getLocale()));
                    item.put("version", plugin.version());
                    item.put("enabled", plugin.isEnabled());
                    item.put("readme", plugin.getReadme() != null);
                    item.put("config", plugin.hasConfig());
                    item.put("icon", plugin.icon());
                    data.add(item);
                }
                json(ctx, 200, data);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ upload

    /** {@code POST admin/plugins/upload}（multipart）。 */
    public Handler<RoutingContext> upload() {
        return ctx -> {
            try {
                com.neoskin.web.http.UploadedFile file = RequestInput.file(ctx, "file");
                if (file == null) {
                    validationFailed(ctx, "file", "The file field is required.");
                    return;
                }
                String mime = file.clientMime() == null ? "" : file.clientMime().toLowerCase();
                if (!mime.contains("zip")) {
                    validationFailed(ctx, "file",
                            "The file must be a file of type: application/zip.");
                    return;
                }
                Path temp = Files.createTempFile("bs-plugin", ".zip");
                try {
                    Files.write(temp, file.bytes());
                    installZip(temp);
                } finally {
                    Files.deleteIfExists(temp);
                }

                json(ctx, 200, message(0,
                        trans("admin.plugins.market.install-success")));
            } catch (RuntimeException e) {
                json(ctx, 200, message(1, e.getMessage()));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    // ============================================================ wget

    /** {@code POST admin/plugins/wget}：按 URL 下载插件包。 */
    public Handler<RoutingContext> wget() {
        return ctx -> {
            Path temp = null;
            try {
                Map<String, Object> input = inputMap(ctx);
                Validator validator = new Validator(input, translator, translator.getLocale(),
                        null, null);
                validator.addRule("url", "required");
                validator.addRule("url", "url");
                if (!validator.passes()) {
                    validationError(ctx, validator.errors());
                    return;
                }
                String url = String.valueOf(input.get("url"));
                temp = Files.createTempFile("wget-plugin", ".zip");
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10)).build();
                HttpResponse<Path> response = client.send(HttpRequest.newBuilder(
                                URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofFile(temp));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    installZip(temp);
                    json(ctx, 200, message(0,
                            trans("admin.plugins.market.install-success")));
                } else {
                    json(ctx, 200, message(1, translator.trans(
                            "admin.download.errors.download",
                            Map.of("error", String.valueOf(response.statusCode())),
                            translator.getLocale())));
                }
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

    // ============================================================ helpers

    /** 解压到第一个插件目录（不存在则创建）。 */
    void installZip(Path file) throws Exception {
        Path target = plugins.getPluginsDirs().get(0);
        Files.createDirectories(target);
        unzip.extract(file, target);
    }

    private Map<String, Object> pageVars(RoutingContext ctx, String locale, String title)
            throws Exception {
        String host = hostOf(ctx);
        String urlRoot = "http://" + host;
        SessionStore session = ctx.get(StartSession.CTX_KEY);
        UserRepository.UserRow user = com.neoskin.web.middleware.Authenticate.currentUser(ctx);
        ViewContext vc = new ViewContext(translator, option,
                new PhpUrlGenerator(urlRoot, "http"), RouteUrlGenerator.forHost(host), locale,
                session == null ? "" : String.valueOf(session.token()), user, session);
        Map<String, Object> vars = PageView.variables(option, translator,
                javaScript, filter, user, locale, urlRoot, ctx.request().path(), debug, "admin",
                vc);
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

    private void validationFailed(RoutingContext ctx, String field, String message) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        errors.put(field, List.of(message));
        validationError(ctx, errors);
    }

    private void validationError(RoutingContext ctx, Map<String, List<String>> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", Validator.summarize(errors));
        body.put("errors", errors);
        json(ctx, 422, body);
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

    private static Map<String, Object> message(int code, String text, Map<String, Object> data) {
        Map<String, Object> body = message(code, text);
        body.put("data", data);

        return body;
    }

    private static Map<String, Object> inputMap(RoutingContext ctx) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Object body = ctx.get(RequestInput.INPUT_KEY);
        if (body instanceof Map<?, ?> map) {
            map.forEach((key, value) -> merged.put(String.valueOf(key), value));
        }

        return merged;
    }

    private static String hostOf(RoutingContext ctx) {
        String host = ctx.request().getHeader("Host");

        return host == null || host.isEmpty() ? "localhost" : host;
    }

    public static HandlerResolver resolver(Option option, Translator translator,
            PluginManager plugins, AppPaths paths, JavaScript javaScript, BlessingFilter filter,
            UserRepository users, boolean debug) {
        PluginController c = new PluginController(option, translator, plugins, paths,
                javaScript, filter, users, debug);

        return action -> switch (action) {
            case "App\\Http\\Controllers\\PluginController@config" -> c.config();
            case "App\\Http\\Controllers\\PluginController@readme" -> c.readme();
            case "App\\Http\\Controllers\\PluginController@manage" -> c.manage();
            case "App\\Http\\Controllers\\PluginController@getPluginData" ->
                    c.getPluginData();
            case "App\\Http\\Controllers\\PluginController@upload" -> c.upload();
            case "App\\Http\\Controllers\\PluginController@wget" -> c.wget();
            default -> null;
        };
    }
}
