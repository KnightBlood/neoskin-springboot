package com.neoskin.web.controllers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.ignition.Ignition;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.validation.Validator;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * Spatie Ignition 的三个管理端点（{@code /_ignition/*}）。
 *
 * <p>路由上由 {@code RunnableSolutionsEnabled} 中间件做门控；进入这里的请求
 * 已确认 {@code APP_DEBUG} 打开且运行环境为 local/development。Java 端没有
 * 可执行的「解决方案」注册表，因此 {@code execute-solution} 恒返回 404
 * （与源中「找不到 solution」的行为一致），{@code health-check} 的
 * {@code can_execute_commands} 恒为 false（见 PORT-NOTES §35）。</p>
 */
public final class IgnitionController {

    /** {@code FileConfigManager::SETTINGS_FILE_NAME}。 */
    public static final String SETTINGS_FILE_NAME = ".ignition.json";

    private final Path configFile;
    private final Translator translator;

    public IgnitionController(Path configFile, Translator translator) {
        this.configFile = configFile;
        this.translator = translator;
    }

    /**
     * {@code FileConfigManager} 的默认位置：{@code HOMEDRIVE+HOMEPATH}（Windows）→
     * {@code HOME} → Java 的 {@code user.home}，文件名 {@code .ignition.json}。
     */
    public static Path defaultConfigFile() {
        return Path.of(homeDirectory(), SETTINGS_FILE_NAME);
    }

    static String homeDirectory() {
        String drive = System.getenv("HOMEDRIVE");
        String path = System.getenv("HOMEPATH");
        if (drive != null && !drive.isEmpty() && path != null && !path.isEmpty()) {
            return drive + path;
        }
        String home = System.getenv("HOME");
        if (home != null && !home.isEmpty()) {
            return home;
        }

        return System.getProperty("user.home", ".");
    }

    /** {@code GET /_ignition/health-check}。 */
    public Handler<RoutingContext> healthCheck() {
        return ctx -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("can_execute_commands", Ignition.CAN_EXECUTE_COMMANDS);
            json(ctx, 200, body);
        };
    }

    /** {@code POST /_ignition/execute-solution}。 */
    public Handler<RoutingContext> executeSolution() {
        return ctx -> {
            Map<String, Object> input = inputMap(ctx);
            Validator validator = new Validator(input, translator, translator.getLocale(),
                    null, null);
            validator.addRule("solution", "required");
            if (!validator.passes()) {
                validationError(ctx, validator.errors());
                return;
            }
            // 没有 PHP 的解决方案注册表：任何 solution 类都不存在。
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "Solution could not be found");
            json(ctx, 404, body);
        };
    }

    /** {@code POST /_ignition/update-config}：写 {@code ~/.ignition.json}。 */
    public Handler<RoutingContext> updateConfig() {
        return ctx -> {
            try {
                Map<String, Object> input = inputMap(ctx);
                Validator validator = new Validator(input, translator, translator.getLocale(),
                        null, null);
                validator.addRule("theme", "required");
                validator.addRule("theme", "in:light,dark,auto");
                validator.addRule("editor", "required");
                validator.addRule("hide_solutions", "required");
                validator.addRule("hide_solutions", "boolean");
                if (!validator.passes()) {
                    validationError(ctx, validator.errors());
                    return;
                }
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("theme", input.get("theme"));
                values.put("editor", input.get("editor"));
                values.put("hide_solutions", input.get("hide_solutions"));
                boolean saved = save(values);
                json(ctx, 200, saved);
            } catch (RuntimeException e) {
                ctx.fail(e);
            }
        };
    }

    /** {@code FileConfigManager::save()}：把整份配置写成 JSON。 */
    private boolean save(Map<String, Object> values) {
        try {
            Path parent = configFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(configFile, PhpJson.encode(values, 0),
                    StandardCharsets.UTF_8);

            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void validationError(RoutingContext ctx, Map<String, List<String>> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", Validator.summarize(errors));
        body.put("errors", errors);
        json(ctx, 422, body);
    }

    private static Map<String, Object> inputMap(RoutingContext ctx) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Object body = ctx.get(RequestInput.INPUT_KEY);
        if (body instanceof Map<?, ?> map) {
            map.forEach((key, value) -> merged.put(String.valueOf(key), value));
        }

        return merged;
    }

    private void json(RoutingContext ctx, int status, Object body) {
        String payload = PhpJson.encode(body, 0);
        ctx.response().setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(Buffer.buffer(payload.getBytes(StandardCharsets.UTF_8)));
    }

    public static HandlerResolver resolver(Path configFile, Translator translator) {
        IgnitionController c = new IgnitionController(configFile, translator);

        return action -> switch (action) {
            case "Spatie\\LaravelIgnition\\Http\\Controllers\\HealthCheckController" ->
                    c.healthCheck();
            case "Spatie\\LaravelIgnition\\Http\\Controllers\\ExecuteSolutionController" ->
                    c.executeSolution();
            case "Spatie\\LaravelIgnition\\Http\\Controllers\\UpdateConfigController" ->
                    c.updateConfig();
            default -> null;
        };
    }
}
