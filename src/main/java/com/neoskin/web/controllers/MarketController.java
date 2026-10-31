package com.neoskin.web.controllers;

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

import com.neoskin.config.NeoSkinSettings;
import com.neoskin.service.Option;
import com.neoskin.service.Plugin;
import com.neoskin.service.PluginManager;
import com.neoskin.service.Semver;
import com.neoskin.service.Unzip;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.RequestInput;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\MarketController} 的移植：插件市场列表与安装。
 *
 * <p>列表来源 `plugins_registry`（选项优先，其次配置），支持逗号分隔多仓库与
 * `{lang}` 占位符；安装时会做依赖/冲突检查并下载 `dist.url` 解压。</p>
 */
public final class MarketController {

    private final Option option;
    private final Translator translator;
    private final PluginManager plugins;
    private final NeoSkinSettings settings;
    private final Unzip unzip = new Unzip();

    public MarketController(Option option, Translator translator, PluginManager plugins,
            NeoSkinSettings settings) {
        this.option = option;
        this.translator = translator;
        this.plugins = plugins;
        this.settings = settings;
    }

    /** {@code GET admin/plugins/market/list}。 */
    public Handler<RoutingContext> marketData() {
        return ctx -> {
            try {
                List<Object> data = new ArrayList<>();
                for (Map<String, Object> item : fetch()) {
                    Map<String, Object> current = new LinkedHashMap<>(item);
                    String name = String.valueOf(item.get("name"));
                    Plugin installed = plugins.get(name);
                    if (installed != null) {
                        current.put("installed", installed.version());
                        current.put("can_update", Semver.greaterThan(
                                String.valueOf(item.get("version")), installed.version()));
                    } else {
                        current.put("installed", false);
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> requirements = item.get("require") instanceof Map<?, ?> map
                            ? (Map<String, Object>) map : Map.of();
                    current.remove("require");

                    Plugin fake = new Plugin(null, item);
                    Map<String, Object> dependencies = new LinkedHashMap<>();
                    dependencies.put("all", requirements);
                    dependencies.put("unsatisfied", plugins.getUnsatisfied(fake));
                    current.put("dependencies", dependencies);
                    data.add(current);
                }
                json(ctx, 200, data);
            } catch (RuntimeException e) {
                ctx.fail(e);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code POST admin/plugins/market/download}。 */
    public Handler<RoutingContext> download() {
        return ctx -> {
            Path temp = null;
            try {
                String name = RequestInput.inputString(ctx, "name");
                Map<String, Object> metadata = null;
                for (Map<String, Object> item : fetch()) {
                    if (name != null && name.equals(String.valueOf(item.get("name")))) {
                        metadata = item;
                        break;
                    }
                }
                if (metadata == null) {
                    json(ctx, 200, message(1, translator.trans(
                            "admin.plugins.market.non-existent",
                            Map.of("plugin", String.valueOf(name)), translator.getLocale())));
                    return;
                }

                Plugin fake = new Plugin(null, metadata);
                Map<String, Map<String, String>> unsatisfied = plugins.getUnsatisfied(fake);
                Map<String, Map<String, String>> conflicts = plugins.getConflicts(fake);
                if (!unsatisfied.isEmpty() || !conflicts.isEmpty()) {
                    List<String> reason = plugins.formatUnresolved(unsatisfied, conflicts);
                    json(ctx, 200, message(1,
                            trans("admin.plugins.market.unresolved"),
                            Map.of("reason", reason)));
                    return;
                }

                Object dist = metadata.get("dist");
                String url = dist instanceof Map<?, ?> map && map.get("url") != null
                        ? String.valueOf(map.get("url")) : null;
                if (url == null) {
                    json(ctx, 200, message(1, trans("admin.plugins.market.unresolved")));
                    return;
                }

                temp = Files.createTempFile("plugin-" + name, ".zip");
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10)).build();
                HttpResponse<Path> response = client.send(HttpRequest.newBuilder(
                                URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofFile(temp));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    json(ctx, 200, message(1, translator.trans(
                            "admin.download.errors.download",
                            Map.of("error", String.valueOf(response.statusCode())),
                            translator.getLocale())));
                    return;
                }

                Path target = plugins.getPluginsDirs().get(0);
                Files.createDirectories(target);
                unzip.extract(temp, target);
                json(ctx, 200, message(0, trans("admin.plugins.market.install-success")));
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

    // ============================================================ fetch

    /** {@code fetch()}：拉取所有注册表并摊平 packages。 */
    private List<Map<String, Object>> fetch() {
        String raw = option.get("plugins_registry") == null ? ""
                : String.valueOf(option.get("plugins_registry"));
        if (raw.isBlank()) {
            raw = settings.getPlugins().getRegistry();
        }
        String lang = translator.getLocale();
        if (!List.of("en", "zh_CN").contains(lang)) {
            lang = settings.getFallbackLocale();
        }

        List<Map<String, Object>> packages = new ArrayList<>();
        for (String registry : raw.split(",")) {
            String url = registry.trim().replace("{lang}", lang);
            if (url.isEmpty()) {
                continue;
            }
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5)).build();
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new RuntimeException(translator.trans(
                            "admin.plugins.market.connection-error",
                            Map.of("error", String.valueOf(response.statusCode())),
                            translator.getLocale()));
                }
                Object decoded = PhpJson.decode(response.body());
                if (decoded instanceof Map<?, ?> body) {
                    Object rawPackages = body.get("packages");
                    List<?> list = null;
                    if (rawPackages instanceof List<?> items) {
                        list = items;
                    } else if (rawPackages instanceof Map<?, ?> map) {
                        list = new ArrayList<>(map.values());
                    }
                    if (list != null) {
                        for (Object item : list) {
                            if (item instanceof Map<?, ?> map) {
                                Map<String, Object> entry = new LinkedHashMap<>();
                                map.forEach((key, value) ->
                                        entry.put(String.valueOf(key), value));
                                packages.add(entry);
                            }
                        }
                    }
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(translator.trans(
                        "admin.plugins.market.connection-error",
                        Map.of("error", String.valueOf(e.getMessage())),
                        translator.getLocale()));
            }
        }

        return packages;
    }

    // ============================================================ helpers

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

    public static HandlerResolver resolver(Option option, Translator translator,
            PluginManager plugins, NeoSkinSettings settings) {
        MarketController c = new MarketController(option, translator, plugins, settings);

        return action -> switch (action) {
            case "App\\Http\\Controllers\\MarketController@marketData" -> c.marketData();
            case "App\\Http\\Controllers\\MarketController@download" -> c.download();
            default -> null;
        };
    }
}
