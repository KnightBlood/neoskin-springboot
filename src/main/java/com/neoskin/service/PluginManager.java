package com.neoskin.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.support.PhpJson;
import com.neoskin.web.i18n.Translator;

/**
 * {@code App\Services\PluginManager} 的移植（清单层）。
 *
 * <p>保留的语义：多插件目录扫描（逗号分隔）、同名单插件目录重复报错、
 * {@code plugins_enabled} 选项持久化、依赖/冲突检查（{@link Semver}）、
 * 启用时版本变化的回写、enable/disable/delete。</p>
 *
 * <p>未移植（无 PHP 运行时）：{@code vendor/autoload.php}、PSR-4 autoload、
 * ServiceProvider 注册、{@code bootstrap.php} 与 {@code callbacks.php} 执行、
 * 插件视图/翻译命名空间。因此「启用」只改变状态，不会运行插件代码。</p>
 */
public final class PluginManager {

    private final Option option;
    private final Translator translator;
    private final NeoSkinSettings settings;
    private final AppPaths paths;

    /** 进程级 boot 守卫（源 {@code $booted} 是实例字段；这里每次请求都会 new 一个）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean BOOTED =
            new java.util.concurrent.atomic.AtomicBoolean();

    public PluginManager(Option option, Translator translator, NeoSkinSettings settings,
            AppPaths paths) {
        this.option = option;
        this.translator = translator;
        this.settings = settings;
        this.paths = paths;
    }

    /** {@code getPluginsDirs()}。 */
    public List<Path> getPluginsDirs() {
        String config = settings.getPlugins().getDirectory();
        List<Path> dirs = new ArrayList<>();
        if (config != null && !config.isBlank()) {
            for (String part : config.split(",\\s*")) {
                Path path = Path.of(part);
                dirs.add(path.isAbsolute() ? path : paths.base().resolve(part).normalize());
            }
        } else {
            dirs.add(paths.base().resolve("plugins"));
        }

        return dirs;
    }

    /** {@code plugins_enabled}：name → version（忽略历史遗留的字符串项）。 */
    private Map<String, String> loadEnabled() {
        Map<String, String> enabled = new LinkedHashMap<>();
        Object raw = option.get("plugins_enabled", "[]", true);
        if (raw == null) {
            return enabled;
        }
        try {
            Object decoded = PhpJson.decode(String.valueOf(raw));
            List<?> items = null;
            if (decoded instanceof List<?> list) {
                items = list;
            } else if (decoded instanceof Map<?, ?> map) {
                items = new ArrayList<>(map.values());
            }
            if (items != null) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> map && map.get("name") != null) {
                        enabled.put(String.valueOf(map.get("name")),
                                map.get("version") == null ? null
                                        : String.valueOf(map.get("version")));
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 损坏的 JSON 视为空列表（源 json_decode 失败得到 null → collect(null) 为空）
        }

        return enabled;
    }

    private void saveEnabled(Map<String, String> enabled) {
        List<Object> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : enabled.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.getKey());
            item.put("version", entry.getValue());
            list.add(item);
        }
        option.set("plugins_enabled", PhpJson.encode(list, 0));
    }

    /** {@code all()}：扫描全部插件目录（不缓存，避免状态过期）。 */
    public Map<String, Plugin> all() {
        Map<String, String> enabled = loadEnabled();
        Map<String, Plugin> plugins = new LinkedHashMap<>();
        Map<String, String> changed = new LinkedHashMap<>();

        for (Path dir : getPluginsDirs()) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<Path> candidates = new ArrayList<>();
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isDirectory).forEach(candidates::add);
            } catch (IOException e) {
                continue;
            }
            candidates.sort(Comparator.comparing(Path::toString));
            for (Path candidate : candidates) {
                Path manifestFile = candidate.resolve("package.json");
                if (!Files.isRegularFile(manifestFile)) {
                    continue;
                }
                PhpJson.Array decoded;
                try {
                    decoded = PhpJson.decodeArray(Files.readString(manifestFile));
                } catch (IOException e) {
                    continue;
                }
                if (decoded == null || decoded.get("name") == null) {
                    continue;
                }
                Map<String, Object> manifest = new LinkedHashMap<>();
                decoded.forEach((key, value) -> manifest.put(String.valueOf(key), value));
                String name = String.valueOf(manifest.get("name"));
                if (plugins.containsKey(name)) {
                    throw new RuntimeException(translator.trans("errors.plugins.duplicate",
                            Map.of("dir1", String.valueOf(plugins.get(name).path()),
                                    "dir2", candidate.toString()),
                            translator.getLocale()));
                }

                Plugin plugin = new Plugin(candidate, manifest);
                if (!getUnsatisfied(plugin, enabled).isEmpty()
                        || !getConflicts(plugin, enabled).isEmpty()) {
                    disable(plugin, enabled);
                }
                if (enabled.containsKey(name)) {
                    plugin.setEnabled(true);
                    String recorded = enabled.get(name);
                    if (!String.valueOf(plugin.version()).equals(String.valueOf(recorded))) {
                        enabled.put(name, plugin.version());
                        changed.put(name, plugin.version());
                    }
                }
                plugins.put(name, plugin);
            }
        }

        if (!changed.isEmpty()) {
            saveEnabled(enabled);
            for (String name : changed.keySet()) {
                Plugin plugin = plugins.get(name);
                if (plugin != null) {
                    // 源是字符串事件 'plugin.versionChanged'（带插件实例）
                    com.neoskin.event.EventRuntime.fire("plugin.versionChanged", plugin);
                }
            }
        }

        return plugins;
    }

    public Plugin get(String name) {
        return name == null ? null : all().get(name);
    }

    public List<Plugin> getEnabledPlugins() {
        List<Plugin> enabled = new ArrayList<>();
        for (Plugin plugin : all().values()) {
            if (plugin.isEnabled()) {
                enabled.add(plugin);
            }
        }

        return enabled;
    }

    /**
     * {@code enable($plugin)}：成功返回 {@code null}；失败返回
     * {@code Map{unsatisfied, conflicts}}（与源 {@code true|array} 对应）。
     */
    public Map<String, Map<String, Map<String, String>>> enable(String name) {
        Map<String, String> enabled = loadEnabled();
        Plugin plugin = all().get(name);
        if (plugin == null) {
            return null;
        }
        if (plugin.isEnabled()) {
            return null;
        }
        Map<String, Map<String, String>> unsatisfied = getUnsatisfied(plugin, enabled);
        Map<String, Map<String, String>> conflicts = getConflicts(plugin, enabled);
        if (!unsatisfied.isEmpty() || !conflicts.isEmpty()) {
            Map<String, Map<String, Map<String, String>>> result = new LinkedHashMap<>();
            result.put("unsatisfied", unsatisfied);
            result.put("conflicts", conflicts);

            return result;
        }

        enabled.put(plugin.name(), plugin.version());
        saveEnabled(enabled);
        plugin.setEnabled(true);
        com.neoskin.event.EventRuntime.fire(
                new com.neoskin.event.Events.PluginWasEnabled(plugin));

        return null;
    }

    public void disable(String name) {
        disable(all().get(name), loadEnabled());
    }

    private void disable(Plugin plugin, Map<String, String> enabled) {
        if (plugin != null && (plugin.isEnabled() || enabled.containsKey(plugin.name()))) {
            enabled.remove(plugin.name());
            saveEnabled(enabled);
            plugin.setEnabled(false);
            com.neoskin.event.EventRuntime.fire(
                    new com.neoskin.event.Events.PluginWasDisabled(plugin));
        }
    }

    public void delete(String name) {
        Plugin plugin = all().get(name);
        if (plugin == null) {
            return;
        }
        disable(plugin, loadEnabled());
        if (plugin.path() != null) {
            deleteRecursively(plugin.path());
        }
        com.neoskin.event.EventRuntime.fire(
                new com.neoskin.event.Events.PluginWasDeleted(plugin));
    }

    /**
     * {@code boot()}：启动所有已启用插件。
     *
     * <p>本工程不执行 PHP 插件代码，因此 {@link #registerPlugin} / {@link #bootPlugin}
     * 是空钩子；保留方法是为了与源 {@code PluginManager} 的 13 个公开方法一致，
     * 也给未来的 PF4J Java 插件留出装配点。启动失败的插件会派发
     * {@code PluginBootFailed}（源会在页面弹提示）。</p>
     */
    public void boot() {
        if (BOOTED.compareAndSet(false, true)) {
            for (Plugin plugin : getEnabledPlugins()) {
                try {
                    registerPlugin(plugin);
                    bootPlugin(plugin);
                } catch (RuntimeException e) {
                    com.neoskin.event.EventRuntime.fire(
                            new com.neoskin.event.Events.PluginBootFailed(plugin));
                }
            }
        }
    }

    /** {@code registerPlugin($plugin)}：注册自动加载/依赖（Java 侧为 PF4J 预留）。 */
    public void registerPlugin(Plugin plugin) {
        // 无 PHP 运行时：插件代码不执行（见 PORT-NOTES §32.2）。
    }

    /** {@code bootPlugin($plugin)}：执行插件引导（Java 侧为 PF4J 预留）。 */
    public void bootPlugin(Plugin plugin) {
        // 同上。
    }

    /** {@code getUnsatisfied($plugin)}。 */
    public Map<String, Map<String, String>> getUnsatisfied(Plugin plugin) {
        return getUnsatisfied(plugin, loadEnabled());
    }

    private Map<String, Map<String, String>> getUnsatisfied(Plugin plugin,
            Map<String, String> enabled) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        Object require = plugin.manifest().get("require");
        if (!(require instanceof Map<?, ?> requirements)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : requirements.entrySet()) {
            String name = String.valueOf(entry.getKey());
            String constraint = String.valueOf(entry.getValue());
            String version;
            if ("blessing-skin-server".equals(name)) {
                version = settings.getVersion();
                if (!Semver.satisfies(version, constraint)) {
                    result.put(name, detail(version, constraint));
                }
            } else if ("php".equals(name)) {
                version = System.getProperty("neoskin.php.version", "8.5.0");
                if (!Semver.satisfies(version, constraint)) {
                    result.put(name, detail(version, constraint));
                }
            } else if (!enabled.containsKey(name)) {
                result.put(name, detail(null, constraint));
            } else if (!Semver.satisfies(enabled.get(name), constraint)) {
                result.put(name, detail(enabled.get(name), constraint));
            }
        }

        return result;
    }

    /** {@code getConflicts($plugin)}。 */
    public Map<String, Map<String, String>> getConflicts(Plugin plugin) {
        return getConflicts(plugin, loadEnabled());
    }

    private Map<String, Map<String, String>> getConflicts(Plugin plugin,
            Map<String, String> enabled) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        Object conflicts = plugin.attr("enchants.conflicts", null);
        if (!(conflicts instanceof Map<?, ?> map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            String constraint = String.valueOf(entry.getValue());
            String version = enabled.get(name);
            if (version != null && Semver.satisfies(version, constraint)) {
                result.put(name, detail(version, constraint));
            }
        }

        return result;
    }

    private static Map<String, String> detail(String version, String constraint) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("version", version);
        detail.put("constraint", constraint);

        return detail;
    }

    /** {@code formatUnresolved($unsatisfied, $conflicts)}。 */
    public List<String> formatUnresolved(Map<String, Map<String, String>> unsatisfied,
            Map<String, Map<String, String>> conflicts) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : unsatisfied.entrySet()) {
            String name = entry.getKey();
            String title;
            if ("blessing-skin-server".equals(name)) {
                title = "Blessing Skin Server";
            } else if ("php".equals(name)) {
                title = "PHP";
            } else {
                Plugin plugin = get(name);
                title = plugin == null ? name
                        : translator.trans(plugin.title(), Map.of(), translator.getLocale());
            }
            Map<String, String> detail = entry.getValue();
            if (detail.get("version") != null) {
                lines.add(translator.trans("admin.plugins.operations.unsatisfied.version",
                        Map.of("title", title, "constraint",
                                String.valueOf(detail.get("constraint"))),
                        translator.getLocale()));
            } else {
                lines.add(translator.trans("admin.plugins.operations.unsatisfied.disabled",
                        Map.of("name", title), translator.getLocale()));
            }
        }
        for (Map.Entry<String, Map<String, String>> entry : conflicts.entrySet()) {
            Plugin plugin = get(entry.getKey());
            String title = plugin == null ? entry.getKey()
                    : translator.trans(plugin.title(), Map.of(), translator.getLocale());
            lines.add(translator.trans("admin.plugins.operations.unsatisfied.conflict",
                    Map.of("title", title), translator.getLocale()));
        }

        return lines;
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 尽力删除
                }
            });
        } catch (IOException ignored) {
            // ignore
        }
    }
}
