package com.neoskin.service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;

/**
 * {@code App\Services\UpdateSources} 的移植：把主站更新源 / 插件仓库地址写回
 * {@code .env}（{@code UPDATE_SOURCE} / {@code PLUGINS_REGISTRY}），并同步内存配置。
 *
 * <p>.env 路径优先取系统属性 {@code neoskin.env.file}，否则为站点根目录下的
 * {@code .env}（与 PHP 版 {@code app()->environmentFilePath()} 对应）。
 * Spring Boot 没有「配置缓存文件」，源里删除 {@code bootstrap/cache/config.php}
 * 的步骤在本工程无对应物（记录在 PORT-NOTES）。</p>
 */
public final class UpdateSources {

    public static final String OFFICIAL_UPDATE_SOURCE =
            "https://dev.azure.com/blessing-skin/51010f6d-9f99-40f1-a262-0a67f788df32/_apis/"
                    + "git/repositories/a9ff8df7-6dc3-4ff8-bb22-4871d3a43936/Items"
                    + "?path=%2Fupdate.json";
    public static final String OFFICIAL_PLUGINS_REGISTRY =
            "https://bs-plugins.littleservice.cn/registry_{lang}.json";
    public static final String PATCH_UPDATE_SOURCE =
            "https://skin.blessing.dreamcraft.eu.org/update.json";
    public static final String PATCH_PLUGINS_REGISTRY =
            "https://plugin.blessing.dreamcraft.eu.org/registry_{lang}.json";

    /** 预设源（{@code config/updates.php} 的 {@code sources}）。 */
    public static Map<String, String> preset(String name) {
        return switch (name) {
            case "official" -> Map.of(
                    "update_source", OFFICIAL_UPDATE_SOURCE,
                    "plugins_registry", OFFICIAL_PLUGINS_REGISTRY);
            case "patch" -> Map.of(
                    "update_source", PATCH_UPDATE_SOURCE,
                    "plugins_registry", PATCH_PLUGINS_REGISTRY);
            default -> Map.of();
        };
    }

    private final AppPaths paths;
    private final NeoSkinSettings settings;

    public UpdateSources(AppPaths paths, NeoSkinSettings settings) {
        this.paths = paths;
        this.settings = settings;
    }

    private Path environmentPath() {
        String custom = System.getProperty("neoskin.env.file");
        if (custom != null && !custom.isBlank()) {
            return Path.of(custom);
        }

        return paths.base().resolve(".env");
    }

    /** {@code save(array $sources)}。 */
    public void save(Map<String, String> sources) {
        Path path = environmentPath();
        if (!Files.isRegularFile(path) || !Files.isReadable(path) || !Files.isWritable(path)
                || !Files.isWritable(path.toAbsolutePath().getParent())) {
            throw new RuntimeException("请确认站点 .env 文件及所在目录可写，再保存更新源。");
        }

        Path appDir = paths.storage("app");
        try {
            Files.createDirectories(appDir);
        } catch (IOException e) {
            throw new RuntimeException("更新源正在保存，请稍后重试。", e);
        }

        Path lockPath = appDir.resolve("update-sources.lock");
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("UPDATE_SOURCE", sources.getOrDefault("update_source", ""));
            values.put("PLUGINS_REGISTRY", sources.getOrDefault("plugins_registry", ""));
            for (String value : values.values()) {
                if (value.matches("(?s).*[\\r\\n\\x00].*")) {
                    throw new RuntimeException("更新源地址应为单行 URL。");
                }
            }

            String contents = Files.readString(path, StandardCharsets.UTF_8);
            String newline = contents.contains("\r\n") ? "\r\n" : "\n";
            String[] lines = contents.split("\\r\\n|\\n|\\r", -1);
            Pattern keyPattern = Pattern.compile(
                    "^\\s*(?:export\\s+)?(UPDATE_SOURCE|PLUGINS_REGISTRY)\\s*=");

            List<String> result = new ArrayList<>();
            Map<String, Boolean> seen = new LinkedHashMap<>();
            for (String line : lines) {
                Matcher matcher = keyPattern.matcher(line);
                if (matcher.find()) {
                    String key = matcher.group(1);
                    if (!seen.containsKey(key) && !values.get(key).isEmpty()) {
                        result.add(key + "=\"" + addcslashes(values.get(key)) + "\"");
                    }
                    seen.put(key, true);
                } else {
                    result.add(line);
                }
            }
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (!seen.containsKey(entry.getKey()) && !entry.getValue().isEmpty()) {
                    result.add(entry.getKey() + "=\"" + addcslashes(entry.getValue()) + "\"");
                }
            }

            Files.writeString(path, String.join(newline, result), StandardCharsets.UTF_8);

            settings.setUpdateSource(values.get("UPDATE_SOURCE").isEmpty()
                    ? PATCH_UPDATE_SOURCE : values.get("UPDATE_SOURCE"));
            settings.getPlugins().setRegistry(values.get("PLUGINS_REGISTRY").isEmpty()
                    ? PATCH_PLUGINS_REGISTRY : values.get("PLUGINS_REGISTRY"));
        } catch (IOException e) {
            throw new RuntimeException("请确认配置缓存目录可写，再保存更新源。", e);
        }
    }

    /** PHP {@code addcslashes($value, '\\"$')}。 */
    private static String addcslashes(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '"' || c == '$') {
                sb.append('\\');
            }
            sb.append(c);
        }

        return sb.toString();
    }
}
