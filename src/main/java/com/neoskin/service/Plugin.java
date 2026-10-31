package com.neoskin.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code App\Services\Plugin} 的移植：插件目录 + package.json 清单。
 *
 * <p>本工程不能执行 PHP 插件代码（没有 PHP 运行时），这里只负责清单解析与
 * 文件系统相关的元信息；{@code enchants.providers}/{@code bootstrap.php} 等
 * 运行时加载点为 no-op（见 PORT-NOTES §32）。</p>
 */
public final class Plugin {

    public static final List<String> README_FILES =
            List.of("README.md", "readme.md", "README.MD");

    private final Path path;
    private final Map<String, Object> manifest;
    private boolean enabled;

    public Plugin(Path path, Map<String, Object> manifest) {
        this.path = path;
        this.manifest = manifest;
    }

    @SuppressWarnings("unchecked")
    public static Plugin fromJson(String path, Map<String, Object> manifest) {
        return new Plugin(path == null || path.isEmpty() ? null : Path.of(path), manifest);
    }

    public Map<String, Object> manifest() {
        return manifest;
    }

    /** {@code $plugin->name}。 */
    public String name() {
        return string("name");
    }

    public String title() {
        String title = string("title");
        return title == null || title.isEmpty() ? name() : title;
    }

    public String description() {
        Object value = manifest.get("description");
        return value == null ? null : String.valueOf(value);
    }

    public String version() {
        return string("version");
    }

    public String namespace() {
        return string("namespace");
    }

    /** {@code Arr::get($manifest, $name, $default)}（支持点路径）。 */
    public Object attr(String name, Object defaultValue) {
        Object current = manifest;
        for (String segment : name.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return defaultValue;
            }
            if (!map.containsKey(segment)) {
                return defaultValue;
            }
            current = map.get(segment);
        }

        return current;
    }

    public String getReadme() {
        if (path == null) {
            return null;
        }
        for (String filename : README_FILES) {
            if (Files.isRegularFile(path.resolve(filename))) {
                return filename;
            }
        }

        return null;
    }

    public boolean hasConfigClass() {
        return manifest.containsKey("enchants")
                && ((Map<?, ?>) manifest.get("enchants")).containsKey("config");
    }

    /** {@code hasConfigView()}：{@code views/<config 或 config.blade.php>} 存在。 */
    public boolean hasConfigView() {
        if (path == null) {
            return false;
        }
        Object filename = manifest.getOrDefault("config", "config.blade.php");

        return filename != null && !String.valueOf(filename).isEmpty()
                && Files.isRegularFile(path.resolve("views").resolve(String.valueOf(filename)));
    }

    public boolean hasConfig() {
        return hasConfigClass() || hasConfigView();
    }

    public Path path() {
        return path;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Plugin setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /** {@code enchants.icon}：默认 {@code {fa:plug, faType:fas, bg:navy}}。 */
    public Map<String, Object> icon() {
        Map<String, Object> icon = new LinkedHashMap<>();
        icon.put("fa", "plug");
        icon.put("faType", "fas");
        icon.put("bg", "navy");
        Object custom = attr("enchants.icon", null);
        if (custom instanceof Map<?, ?> map) {
            map.forEach((key, value) -> icon.put(String.valueOf(key), value));
        }

        return icon;
    }

    private String string(String key) {
        Object value = manifest.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
