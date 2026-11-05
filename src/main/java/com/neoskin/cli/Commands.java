package com.neoskin.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.context.ConfigurableApplicationContext;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.db.UserRepository;
import com.neoskin.oauth.OAuthKeys;
import com.neoskin.service.Option;
import com.neoskin.service.Plugin;
import com.neoskin.service.PluginManager;
import com.neoskin.service.cipher.Cipher;
import com.neoskin.support.PhpDate;
import com.neoskin.web.i18n.Translator;

/**
 * {@code php artisan ...} 的等价物（{@code --neoskin.command=<name>}）。
 *
 * <p>覆盖源项目的 6 个命令：</p>
 * <ul>
 *   <li>{@code install --email --password --nickname}（{@code bs:install}）</li>
 *   <li>{@code update}（{@code update}）</li>
 *   <li>{@code options:cache}（{@code options:cache}）</li>
 *   <li>{@code plugin:enable --name} / {@code plugin:disable --name}</li>
 *   <li>{@code salt:random [--show]}</li>
 * </ul>
 *
 * <p>CLI 模式不启动 HTTP 监听（{@link #isActive()} 由 {@code WebConfig} 检查）。
 * 与源一致：成功返回 0，参数/执行失败返回 1。</p>
 *
 * <p>注意：配置来自环境变量（{@code application.yml} 的 {@code ${APP_KEY:}} 等），
 * 因此 {@code APP_KEY}/{@code SALT} 写回 {@code .env} 只是为 PHP 兼容/迁移保留，
 * 当前进程不会重新加载；Java 部署请把同样的值放进环境变量。</p>
 */
public final class Commands {

    /** CLI 模式下 WebConfig 不启动 Vert.x 监听。 */
    private static volatile boolean active;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Commands() {
    }

    public static void markActive() {
        active = true;
    }

    public static boolean isActive() {
        return active;
    }

    public static int run(ConfigurableApplicationContext context, String command, String[] args) {
        try {
            return switch (command) {
                case "install" -> install(context, args);
                case "update" -> update(context);
                case "options:cache" -> optionsCache(context);
                case "plugin:enable" -> plugin(context, args, true);
                case "plugin:disable" -> plugin(context, args, false);
                case "salt:random" -> saltRandom(context, args);
                case "down" -> down(context, args);
                case "up" -> up(context);
                default -> {
                    System.err.println("Unknown command: " + command);
                    yield 1;
                }
            };
        } catch (Exception e) {
            System.err.println("Command failed: " + e.getMessage());
            return 1;
        }
    }

    // ------------------------------------------------------------------ 命令

    /** {@code bs:install}。 */
    private static int install(ConfigurableApplicationContext context, String[] args)
            throws Exception {
        Option option = context.getBean(Option.class);
        if (option.isInstalled()) {
            System.out.println(
                    "You have installed Blessing Skin Server. Nothing to do.");
            return 0;
        }

        String email = required(args, "--email");
        String password = required(args, "--password");
        String nickname = required(args, "--nickname");

        NeoSkinSettings settings = context.getBean(NeoSkinSettings.class);
        AppPaths paths = context.getBean(AppPaths.class);
        SchemaInstaller schema = context.getBean(SchemaInstaller.class);
        UserRepository users = context.getBean(UserRepository.class);
        Cipher cipher = context.getBean(Cipher.class);

        schema.install();

        String key = ensureAppKey(settings, paths);
        new OAuthKeys(paths).ensureGenerated();

        // 源 bs:install 的 url('/')：CLI 下取 APP_URL（默认 http://localhost）
        option.set("site_url", "http://localhost");
        String now = PhpDate.now(settings.getTimezone());
        String yesterday = PhpDate.at(System.currentTimeMillis() - 86_400_000L,
                settings.getTimezone());
        users.insertSuperAdmin(email, nickname, 1000,
                cipher.hash(password, settings.getSecure().getSalt()),
                "127.0.0.1", now, yesterday);

        Path lock = paths.installLock();
        Files.createDirectories(lock.getParent());
        Files.write(lock, new byte[0]);

        System.out.println("Application key [" + key + "] set successfully.");
        System.out.println("Installed. Super administrator: " + email);
        return 0;
    }

    /** {@code update}：建表 / 补表 + 版本号 + install.lock + 清缓存。 */
    private static int update(ConfigurableApplicationContext context) throws Exception {
        Option option = context.getBean(Option.class);
        NeoSkinSettings settings = context.getBean(NeoSkinSettings.class);
        AppPaths paths = context.getBean(AppPaths.class);
        context.getBean(SchemaInstaller.class).install();
        // 与 PHP `UpdateCommand` 一致：结构迁移后再跑 4 个数据迁移
        // （import_options / max_texture_width / communications 回填 / OAuth secret 哈希）。
        // 最小测试上下文可能不注册 DataMigrator，此时跳过。
        com.neoskin.migration.DataMigrator migrator =
                context.getBeanProvider(com.neoskin.migration.DataMigrator.class)
                        .getIfAvailable();
        if (migrator != null) {
            migrator.migrate();
        }

        option.set("version", settings.getVersion());
        Path lock = paths.installLock();
        Files.createDirectories(lock.getParent());
        Files.write(lock, new byte[0]);
        option.clearCache();
        System.out.println(trans(context, "setup.updates.success.title", Map.of()));
        return 0;
    }

    /** {@code options:cache}。 */
    private static int optionsCache(ConfigurableApplicationContext context) {
        context.getBean(Option.class).writeCache();
        System.out.println("Options cached successfully.");
        return 0;
    }

    /** {@code plugin:enable|disable}。 */
    private static int plugin(ConfigurableApplicationContext context, String[] args,
            boolean enable) {
        String name = required(args, "--name");
        Option option = context.getBean(Option.class);
        Translator translator = context.getBean(Translator.class);
        NeoSkinSettings settings = context.getBean(NeoSkinSettings.class);
        AppPaths paths = context.getBean(AppPaths.class);
        PluginManager plugins = new PluginManager(option, translator, settings, paths);

        Plugin plugin = plugins.get(name);
        if (plugin == null) {
            System.out.println(trans(context, "admin.plugins.operations.not-found", Map.of()));
            return 0;
        }
        String title = translator.trans(plugin.title(), Map.of(), null);

        if (enable) {
            Map<String, Map<String, Map<String, String>>> result = plugins.enable(name);
            if (result == null) {
                System.out.println(trans(context, "admin.plugins.operations.enabled",
                        Map.of("plugin", title)));
                return 0;
            }
            Map<String, Map<String, String>> unsatisfied =
                    result.getOrDefault("unsatisfied", Map.of());
            Map<String, Map<String, String>> conflicts =
                    result.getOrDefault("conflicts", Map.of());
            System.err.println(String.join("\n",
                    plugins.formatUnresolved(unsatisfied, conflicts)));
            return 1;
        }

        plugins.disable(name);
        System.out.println(trans(context, "admin.plugins.operations.disabled",
                Map.of("plugin", title)));
        return 0;
    }

    /**
     * {@code salt:random [--show]}。
     *
     * <p>源命令先 {@code bin2hex(generateKey('AES-128-CBC'))}（32 位十六进制），
     * 再写回 {@code .env}。这里同样写 {@code SALT=} 行（源里
     * {@code str_replace('SALT = '...)} 的写法匹配不到 {@code SALT=}，属于源的笔误）。</p>
     */
    private static int saltRandom(ConfigurableApplicationContext context, String[] args) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder salt = new StringBuilder(32);
        for (byte b : bytes) {
            salt.append(String.format("%02x", b));
        }

        if (hasFlag(args, "--show")) {
            System.out.println(salt);
            return 0;
        }

        AppPaths paths = context.getBean(AppPaths.class);
        writeEnv(paths, Map.of("SALT", salt.toString()));
        System.out.println("Application salt [" + salt + "] set successfully.");
        return 0;
    }

    // ------------------------------------------------------------------ 工具

    /** {@code down [--retry=60] [--secret=...] [--status=503]}：写维护模式标记。 */
    private static int down(ConfigurableApplicationContext context, String[] args)
            throws Exception {
        AppPaths paths = context.getBean(AppPaths.class);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", intArg(args, "--status", 503));
        String retry = argValue(args, "--retry");
        data.put("retry", retry == null ? null : Integer.parseInt(retry));
        data.put("secret", argValue(args, "--secret"));

        Path file = com.neoskin.web.middleware.MaintenanceMode.downFile(paths);
        Files.createDirectories(file.getParent());
        Files.writeString(file, com.neoskin.support.PhpJson.encode(data, 0),
                StandardCharsets.UTF_8);
        System.out.println("Application is now in maintenance mode.");
        return 0;
    }

    /** {@code up}：删除维护模式标记。 */
    private static int up(ConfigurableApplicationContext context) throws Exception {
        AppPaths paths = context.getBean(AppPaths.class);
        Files.deleteIfExists(com.neoskin.web.middleware.MaintenanceMode.downFile(paths));
        System.out.println("Application is now live.");
        return 0;
    }

    private static int intArg(String[] args, String name, int defaultValue) {
        String value = argValue(args, name);

        return value == null ? defaultValue : Integer.parseInt(value);
    }

    /** {@code key:generate}：APP_KEY 为空时生成并写回 .env。 */
    private static String ensureAppKey(NeoSkinSettings settings, AppPaths paths) {
        if (settings.getKey() != null && !settings.getKey().isBlank()) {
            return settings.getKey();
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String key = "base64:" + Base64.getEncoder().encodeToString(bytes);
        writeEnv(paths, Map.of("APP_KEY", key));

        return key;
    }

    /** 逐行替换 {@code KEY=...}，缺失则追加；`.env` 不存在时只提示。 */
    private static void writeEnv(AppPaths paths, Map<String, String> values) {
        String custom = System.getProperty("neoskin.env.file");
        Path env = custom != null && !custom.isBlank()
                ? Path.of(custom) : paths.base().resolve(".env");
        if (!Files.isRegularFile(env)) {
            System.err.println("警告：未找到 " + env + "，以下值需手动写入环境变量：");
            values.forEach((k, v) -> System.err.println("  " + k + "=" + v));
            return;
        }
        try {
            String content = Files.readString(env, StandardCharsets.UTF_8);
            Map<String, String> remaining = new LinkedHashMap<>(values);
            StringBuilder out = new StringBuilder();
            for (String line : content.split("\n", -1)) {
                String trimmed = line.trim();
                String matchedKey = null;
                for (String key : values.keySet()) {
                    if (trimmed.matches("^" + Pattern.quote(key) + "\\s*=.*")) {
                        matchedKey = key;
                        break;
                    }
                }
                if (matchedKey != null) {
                    out.append(matchedKey).append('=').append(values.get(matchedKey));
                    remaining.remove(matchedKey);
                } else {
                    out.append(line);
                }
                out.append('\n');
            }
            for (Map.Entry<String, String> entry : remaining.entrySet()) {
                out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
            Files.writeString(env, out.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("警告：写入 " + env + " 失败：" + e.getMessage());
        }
    }

    private static String trans(ConfigurableApplicationContext context, String key,
            Map<String, Object> params) {
        Translator translator = context.getBean(Translator.class);
        String value = translator.trans(key, params, null);

        return value == null || value.equals(key) ? key : value;
    }

    private static String required(String[] args, String name) {
        String value = argValue(args, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required option " + name);
        }

        return value;
    }

    private static String argValue(String[] args, String name) {
        if (args == null) {
            return null;
        }
        String prefix = name + "=";
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }

        return null;
    }

    private static boolean hasFlag(String[] args, String flag) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }

        return false;
    }
}
