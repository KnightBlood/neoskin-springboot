package com.neoskin.web.controllers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.db.UserRepository;
import com.neoskin.service.Option;
import com.neoskin.service.cipher.Cipher;
import com.neoskin.support.PhpDate;
import com.neoskin.support.PhpJson;
import com.neoskin.web.HandlerResolver;
import com.neoskin.web.error.WebException;
import com.neoskin.web.i18n.Translator;
import com.neoskin.web.middleware.RequestInput;
import com.neoskin.web.validation.JdbcPresenceVerifier;
import com.neoskin.web.validation.Validator;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Controllers\SetupController} 的移植（数据库配置 + 完成安装）。
 *
 * <p>与源一致：{@code /setup/database} 先探测当前连接，可用则跳到
 * {@code /setup/info}；提交时用表单参数试连数据库并把结果写回 {@code .env}。
 * {@code /setup/finish} 执行建表、写入站点选项、创建超级管理员并写
 * {@code storage/install.lock}，最后渲染完成页。</p>
 *
 * <p>偏差：{@code passport:keys}（本工程尚无 Passport 密钥）、{@code migrate}
 * 的版本记录表、以及 PHP 的 Whip 代理IP白名单均以简化实现替代（见 PORT-NOTES §33）。</p>
 */
public final class SetupController {

    private final Option option;
    private final Translator translator;
    private final UserRepository users;
    private final Cipher cipher;
    private final String salt;
    private final NeoSkinSettings settings;
    private final AppPaths paths;
    private final DataSource dataSource;
    private final SchemaInstaller schema;
    private final String timezone;

    public SetupController(Option option, Translator translator, UserRepository users,
            Cipher cipher, String salt, NeoSkinSettings settings, AppPaths paths,
            DataSource dataSource, String timezone) {
        this.option = option;
        this.translator = translator;
        this.users = users;
        this.cipher = cipher;
        this.salt = salt;
        this.settings = settings;
        this.paths = paths;
        this.dataSource = dataSource;
        this.schema = new SchemaInstaller(dataSource, settings);
        this.timezone = timezone;
    }

    // ============================================================ database

    /** {@code GET|POST /setup/database}。 */
    public Handler<RoutingContext> database() {
        return ctx -> {
            try {
                if ("GET".equalsIgnoreCase(ctx.request().method().name())) {
                    if (databaseReachable()) {
                        redirect(ctx, "http://" + hostOf(ctx) + "/setup/info");
                        return;
                    }
                    Map<String, Object> vars = SetupPages.variables(ctx, option, translator, settings.getVersion());
                    vars.put("host", settings.getDatabase().getHost());
                    vars.put("port", settings.getDatabase().getPort());
                    vars.put("username", settings.getDatabase().getUsername());
                    vars.put("password", settings.getDatabase().getPassword());
                    vars.put("database", settings.getDatabase().getName());
                    vars.put("prefix", settings.getDatabase().getPrefix());
                    vars.put("title", translator.trans("setup.wizard.database.title",
                            Map.of(), translator.getLocale()));
                    SetupPages.render(ctx, "setup/wizard/database", vars);
                    return;
                }

                Map<String, Object> input = inputMap(ctx);
                String type = string(input.get("type"));
                String host = string(input.get("host"));
                String port = string(input.get("port"));
                String username = string(input.get("username"));
                String password = string(input.get("password"));
                String db = string(input.get("db"));
                String prefix = string(input.get("prefix"));

                try (Connection ignored = openConnection(type, host, port, username, password,
                        db)) {
                    // 打开成功即视为可用（源：->getPdo()）
                } catch (Exception e) {
                    String dbType = switch (type) {
                        case "mysql" -> "MySQL/MariaDB";
                        case "sqlite" -> "SQLite";
                        case "pgsql" -> "PostgreSQL";
                        default -> "";
                    };
                    String message = translator.trans("setup.database.connection-error",
                            Map.of("msg", String.valueOf(e.getMessage()), "type", dbType),
                            translator.getLocale());
                    ctx.fail(WebException.http(500, message));
                    return;
                }

                writeEnv(Map.of(
                        "DB_CONNECTION", type,
                        "DB_HOST", host,
                        "DB_PORT", port,
                        "DB_DATABASE", db,
                        "DB_USERNAME", username,
                        "DB_PASSWORD", password,
                        "DB_PREFIX", prefix));
                redirect(ctx, "http://" + hostOf(ctx) + "/setup/info");
            } catch (WebException e) {
                ctx.fail(e);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    private boolean databaseReachable() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    /** 按类型构造临时 JDBC 连接（{@code database.connections.temp}）。 */
    private static Connection openConnection(String type, String host, String port,
            String username, String password, String db) throws Exception {
        return switch (type) {
            case "sqlite" -> DriverManager.getConnection("jdbc:sqlite:" + db);
            case "pgsql" -> DriverManager.getConnection(
                    "jdbc:postgresql://" + host + ":" + defaultPort(port, "5432") + "/" + db,
                    username, password);
            default -> DriverManager.getConnection(
                    "jdbc:mysql://" + host + ":" + defaultPort(port, "3306") + "/" + db
                            + "?useSSL=false&allowPublicKeyRetrieval=true",
                    username, password);
        };
    }

    private static String defaultPort(String port, String fallback) {
        return port == null || port.isBlank() ? fallback : port;
    }

    /** {@code preg_replace('/KEY.+/', 'KEY=value', $env)}：逐行替换，缺失则追加。 */
    private void writeEnv(Map<String, String> values) throws Exception {
        Path env = environmentPath();
        if (!Files.isRegularFile(env)) {
            throw WebException.http(500, ".env 文件不存在，请先创建后再安装。");
        }
        String content = Files.readString(env, StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String regex = java.util.regex.Pattern.quote(entry.getKey()) + "[^\\r\\n]*";
            String replacement = entry.getKey() + "=" + entry.getValue();
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
            if (pattern.matcher(content).find()) {
                content = pattern.matcher(content).replaceAll(
                        java.util.regex.Matcher.quoteReplacement(replacement));
            } else {
                content = content + (content.endsWith("\n") ? "" : "\n") + replacement + "\n";
            }
        }
        Files.writeString(env, content, StandardCharsets.UTF_8);
    }

    private Path environmentPath() {
        String custom = System.getProperty("neoskin.env.file");
        if (custom != null && !custom.isBlank()) {
            return Path.of(custom);
        }

        return paths.base().resolve(".env");
    }

    // ============================================================ finish

    /** {@code POST /setup/finish}。 */
    public Handler<RoutingContext> finish() {
        return ctx -> {
            try {
                Map<String, Object> input = inputMap(ctx);
                Validator validator = new Validator(input, translator, translator.getLocale(),
                        option, new JdbcPresenceVerifier(dataSource));
                validator.addRule("email", "required");
                validator.addRule("email", "email");
                validator.addRule("nickname", "required");
                validator.addRule("password", "required");
                validator.addRule("password", "min:8");
                validator.addRule("password", "max:32");
                validator.addRule("password", "confirmed");
                validator.addRule("site_name", "required");
                if (!validator.passes()) {
                    redirectWithErrors(ctx, validator.errors());
                    return;
                }

                // Todo: 源里的 passport:keys（本工程尚未移植 Passport）
                schema.install();

                String siteUrl = "http://" + hostOf(ctx) + "/";
                if (siteUrl.endsWith("/index.php")) {
                    siteUrl = siteUrl.substring(0, siteUrl.length() - 10);
                }
                option.set("site_name", string(input.get("site_name")));
                option.set("site_url", siteUrl);

                String now = PhpDate.now(timezone);
                int initialScore = toInt(option.get("user_initial_score", 0));
                users.insertSuperAdmin(string(input.get("email")),
                        string(input.get("nickname")), initialScore,
                        cipher.hash(string(input.get("password")), salt),
                        SetupPages.clientIp(ctx), now,
                        PhpDate.at(System.currentTimeMillis() - 86400_000L, timezone));

                Files.createDirectories(paths.installLock().getParent());
                Files.writeString(paths.installLock(), "");

                SetupPages.render(ctx, "setup/wizard/finish",
                        SetupPages.variables(ctx, option, translator, settings.getVersion()));
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    private void redirectWithErrors(RoutingContext ctx, Map<String, List<String>> errors) {
        com.neoskin.service.session.SessionStore session = ctx.get(
                com.neoskin.web.middleware.StartSession.CTX_KEY);
        if (session != null) {
            session.flash("errors", AuthController.toViewErrorBag(errors));
            session.flash("_old_input", inputMap(ctx));
        }
        redirect(ctx, "http://" + hostOf(ctx) + "/setup/info");
    }

    // ============================================================ helpers

    private void redirect(RoutingContext ctx, String location) {
        ctx.response().setStatusCode(302)
                .putHeader("Location", location)
                .putHeader("Cache-Control", "no-cache, private")
                .end(Buffer.buffer(""));
    }

    private static Map<String, Object> inputMap(RoutingContext ctx) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Object body = ctx.get(RequestInput.INPUT_KEY);
        if (body instanceof Map<?, ?> map) {
            map.forEach((key, value) -> merged.put(String.valueOf(key), value));
        }

        return merged;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String hostOf(RoutingContext ctx) {
        String host = ctx.request().getHeader("Host");

        return host == null || host.isEmpty() ? "localhost" : host;
    }

    public static HandlerResolver resolver(Option option, Translator translator,
            UserRepository users, Cipher cipher, String salt, NeoSkinSettings settings,
            AppPaths paths, DataSource dataSource, String timezone) {
        SetupController c = new SetupController(option, translator, users, cipher, salt,
                settings, paths, dataSource, timezone);

        return action -> switch (action) {
            case "App\\Http\\Controllers\\SetupController@database" -> c.database();
            case "App\\Http\\Controllers\\SetupController@finish" -> c.finish();
            default -> null;
        };
    }
}
