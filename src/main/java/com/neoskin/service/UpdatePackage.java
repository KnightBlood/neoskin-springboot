package com.neoskin.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.support.PhpJson;

/**
 * {@code App\Services\UpdatePackage} 的移植：ZIP 运行包的校验、暂存与安装。
 *
 * <p>流程与源一致：识别 {@code bs-community/blessing-skin-server} 包前缀 →
 * 校验必需文件/编译资源/PHP 约束/版本号 → 只解压白名单路径到
 * {@code storage/app/system-updates/<id>/payload} → 安装时逐个文件备份并替换，
 * 失败按逆序回滚。</p>
 *
 * <p>偏差：符号链接检测（PHP 的 external attributes）Java 标准库无法读取，
 * 未做该检查；其余校验逐条保留。</p>
 */
public final class UpdatePackage {

    private static final Logger log = LoggerFactory.getLogger(UpdatePackage.class);

    private static final int MAX_FILES = 30000;
    private static final long MAX_BYTES = 1073741824L; // 1 GiB
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-f0-9]{32}$");
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "['\"]version['\"]\\s*=>\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern COMPOSER_NAME = Pattern.compile("^[^/]+/composer\\.json$");

    private final AppPaths paths;
    private final NeoSkinSettings settings;
    private final UploadLimits uploadLimits;

    public UpdatePackage(AppPaths paths, NeoSkinSettings settings) {
        this.paths = paths;
        this.settings = settings;
        this.uploadLimits = new UploadLimits(settings);
    }

    public UploadLimits uploadLimits() {
        return uploadLimits;
    }

    /**
     * Java 部署只认运行包里的「资产」部分。
     *
     * <p>PHP 运行包（含 {@code artisan}）里的程序文件（{@code artisan}、{@code app/}、
     * {@code vendor/}、{@code config/}…）在 jar 部署下没有意义，安装时跳过并告警；
     * 只部署 {@code public/}（前端产物，{@code public/index.php} 除外）与
     * {@code resources/lang/}（翻译）。非 PHP 运行包（自定义包）保持整包覆盖。</p>
     */
    private static List<String> deployableFiles(List<String> fileNames) {
        if (!fileNames.contains("artisan")) {
            return fileNames;
        }

        List<String> deploy = new ArrayList<>();
        int skipped = 0;
        for (String name : fileNames) {
            boolean asset = (name.startsWith("public/") && !"public/index.php".equals(name))
                    || name.startsWith("resources/lang/");
            if (asset) {
                deploy.add(name);
            } else {
                skipped++;
            }
        }
        if (skipped > 0) {
            log.warn("更新包是 PHP 运行包：跳过 {} 个程序文件（Java 版请替换 jar），"
                    + "只部署 public/ 与 resources/lang/", skipped);
        }

        return deploy;
    }

    private Path root() {
        return paths.base().toAbsolutePath().normalize();
    }

    private Path storageApp() {
        return paths.storage("app");
    }

    private Path directory(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new RuntimeException("更新任务格式错误。");
        }

        return storageApp().resolve("system-updates").resolve(id);
    }

    /** {@code allowed($name)}。 */
    private static boolean allowed(String name) {
        String rootSegment = name.split("/")[0];
        if (Set.of("app", "config", "database", "resources", "routes", "vendor")
                .contains(rootSegment)) {
            return true;
        }
        if ("bootstrap".equals(rootSegment)) {
            return !name.matches("^bootstrap/cache(/|$).*");
        }
        if ("public".equals(rootSegment)) {
            return !name.matches("^public/(storage|uploads|textures|plugins)(/|$).*");
        }

        return Set.of("artisan", "composer.json", "composer.lock", "package.json",
                "index.html", "LICENSE", "README.md", "README-zh.md", "BUILD-PHP85.md",
                "CHANGELOG-PATCH.md").contains(name);
    }

    /** {@code prepare($file)}：返回会话里保存的 pending 信息。 */
    public Map<String, Object> prepare(Path file) {
        String id = randomHex(16);
        Path dir = directory(id);
        try {
            Files.createDirectories(dir.resolve("payload"));
            Map<String, Long> names = new LinkedHashMap<>();
            Map<String, String> composerPrefix = new LinkedHashMap<>();

            try (ZipFile zip = new ZipFile(file.toFile())) {
                List<? extends ZipEntry> entries = list(zip);
                if (entries.size() > MAX_FILES) {
                    throw new RuntimeException("更新包文件数超过 30000。");
                }
                long bytes = 0;
                Set<String> seen = new java.util.HashSet<>();
                for (ZipEntry entry : entries) {
                    String name = entry.getName();
                    if (name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0
                            || name.startsWith("/")
                            || name.matches("(^|/)(\\.\\.?)(/|$).*")
                            || name.matches("^[A-Za-z]:.*")) {
                        throw new RuntimeException("ZIP 文件路径格式错误。");
                    }
                    String key = name.replaceAll("/+$", "").toLowerCase();
                    if (!seen.add(key)) {
                        throw new RuntimeException("ZIP 包含重复文件路径。");
                    }
                    bytes += entry.getSize() > 0 ? entry.getSize() : 0;
                    if (bytes > MAX_BYTES) {
                        throw new RuntimeException("解压大小超过 1 GiB。");
                    }
                    names.put(name, Math.max(0, entry.getSize()));
                }

                String prefix = null;
                Map<?, ?> composer = null;
                for (String name : names.keySet()) {
                    if ("composer.json".equals(name) || COMPOSER_NAME.matcher(name).matches()) {
                        Map<?, ?> candidate = PhpJson.decodeArray(readText(zip, name));
                        if (candidate != null
                                && "bs-community/blessing-skin-server"
                                        .equals(candidate.get("name"))) {
                            if (prefix != null) {
                                throw new RuntimeException("请上传单个系统运行包。");
                            }
                            prefix = name.substring(0, name.length() - "composer.json".length());
                            composer = candidate;
                        }
                    }
                }
                if (prefix == null || composer == null) {
                    throw new RuntimeException("未识别到 Blessing Skin 系统运行包。");
                }
                for (String required : List.of("artisan", "bootstrap/app.php", "config/app.php",
                        "public/index.php", "vendor/autoload.php",
                        "resources/views/assets/app.twig")) {
                    if (!names.containsKey(prefix + required)) {
                        throw new RuntimeException("运行包缺少 " + required + "。");
                    }
                }
                final String packagePrefix = prefix;
                boolean compiled = names.keySet().stream()
                        .anyMatch(name -> name.startsWith(packagePrefix + "public/app/")
                                && name.endsWith(".js"));
                if (!compiled) {
                    throw new RuntimeException("运行包缺少已编译的前端资源。");
                }

                Object require = composer.get("require");
                if (require instanceof Map<?, ?> requireMap) {
                    Object php = requireMap.get("php");
                    if (php != null && !Semver.satisfies(phpVersion(), String.valueOf(php))) {
                        throw new RuntimeException("当前 PHP 版本与运行包要求不匹配。");
                    }
                }

                String config = readText(zip, prefix + "config/app.php");
                Matcher matcher = VERSION_PATTERN.matcher(config == null ? "" : config);
                if (!matcher.find()) {
                    throw new RuntimeException("无法读取运行包版本号。");
                }
                String version = matcher.group(1);
                if (Semver.lessThan(version, settings.getVersion())) {
                    throw new RuntimeException("请选择当前版本或更高版本的运行包。");
                }

                List<String> files = new ArrayList<>();
                for (Map.Entry<String, Long> entry : names.entrySet()) {
                    String name = entry.getKey();
                    if (!name.startsWith(prefix) || name.endsWith("/")) {
                        continue;
                    }
                    String relative = name.substring(prefix.length());
                    if (!allowed(relative)) {
                        continue;
                    }
                    Path target = dir.resolve("payload").resolve(relative).normalize();
                    if (!target.startsWith(dir.resolve("payload"))) {
                        throw new RuntimeException("ZIP 文件路径格式错误。");
                    }
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    files.add(relative);
                }

                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", id);
                info.put("version", version);
                info.put("sha256", sha256(file));
                info.put("files", files);
                info.put("count", files.size());
                info.put("created_at", System.currentTimeMillis() / 1000);

                Files.writeString(dir.resolve("package.json"), PhpJson.encode(info, 0));
                uploadLimits(); // 保持依赖可达（上传上限由控制器读取）

                return info;
            }
        } catch (IOException | RuntimeException e) {
            deleteRecursively(dir);
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException("运行包处理失败，请检查文件和目录权限。", e);
        }
    }

    /** {@code install($id)}。 */
    public Map<String, Object> install(String id) {
        Path dir = directory(id);
        Map<String, Object> info = readPackageInfo(dir);
        if (info == null
                || toLong(info.get("created_at")) < System.currentTimeMillis() / 1000 - 3600
                || Files.isRegularFile(dir.resolve("installed.json"))) {
            throw new RuntimeException("更新任务已过期或已安装，请重新上传运行包。");
        }
        if (Semver.lessThan(String.valueOf(info.get("version")), settings.getVersion())) {
            throw new RuntimeException("请选择当前版本或更高版本的运行包。");
        }

        Path lockPath = storageApp().resolve("system-updates").resolve("install.lock");
        try {
            Files.createDirectories(lockPath.getParent());
        } catch (IOException e) {
            throw new RuntimeException("系统更新正在执行，请稍后重试。", e);
        }

        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new RuntimeException("系统更新正在执行，请稍后重试。");
            }
            try {
                Object files = info.get("files");
                List<String> fileNames = new ArrayList<>();
                if (files instanceof List<?> list) {
                    for (Object raw : list) {
                        fileNames.add(String.valueOf(raw));
                    }
                } else if (files instanceof Map<?, ?> map) {
                    // 会话/JSON 往返后 PHP 数组可能退化为「数字键 → 值」
                    for (Object raw : map.values()) {
                        fileNames.add(String.valueOf(raw));
                    }
                } else {
                    throw new RuntimeException("更新任务格式错误。");
                }
                List<?> list = deployableFiles(fileNames);
                Map<String, Boolean> changed = new LinkedHashMap<>();
                try {
                    if (Files.isRegularFile(dir.resolve("installed.json"))) {
                        throw new RuntimeException("更新任务已安装，请重新上传运行包。");
                    }
                    for (Object raw : list) {
                        String name = String.valueOf(raw);
                        Path target = root().resolve(name).normalize();
                        if (!target.startsWith(root())) {
                            throw new RuntimeException("更新目标路径不可写：" + name);
                        }
                        Path ancestor = Files.exists(target) ? target : target.getParent();
                        while (ancestor != null && !Files.exists(ancestor)) {
                            ancestor = ancestor.getParent();
                        }
                        if (ancestor == null || !Files.isWritable(ancestor)
                                || (Files.exists(target) && !Files.isRegularFile(target))) {
                            throw new RuntimeException("更新目标目录或文件不可写：" + name);
                        }
                    }
                    for (Object raw : list) {
                        String name = String.valueOf(raw);
                        Path target = root().resolve(name).normalize();
                        Path backup = dir.resolve("backup").resolve(name);
                        boolean exists = Files.isRegularFile(target);
                        if (exists) {
                            Files.createDirectories(backup.getParent());
                            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                        }
                        Files.createDirectories(target.getParent());
                        publish(dir.resolve("payload").resolve(name), target);
                        changed.put(name, exists);
                    }
                    Files.writeString(dir.resolve("installed.json"),
                            PhpJson.encode(Map.of("version", info.get("version"),
                                    "installed_at", System.currentTimeMillis() / 1000,
                                    "deployed", list.size()), 0));

                    return info;
                } catch (IOException | RuntimeException e) {
                    List<String> names = new ArrayList<>(changed.keySet());
                    for (int i = names.size() - 1; i >= 0; i--) {
                        String name = names.get(i);
                        Path target = root().resolve(name).normalize();
                        try {
                            if (changed.get(name)) {
                                Files.copy(dir.resolve("backup").resolve(name), target,
                                        StandardCopyOption.REPLACE_EXISTING);
                            } else {
                                Files.deleteIfExists(target);
                            }
                        } catch (IOException ignored) {
                            // 回滚尽力而为
                        }
                    }
                    throw e instanceof RuntimeException runtime ? runtime
                            : new RuntimeException("安装失败，请检查站点目录权限与运行日志。", e);
                }
            } finally {
                lock.release();
            }
        } catch (IOException e) {
            throw new RuntimeException("安装失败，请检查站点目录权限与运行日志。", e);
        }
    }

    /** {@code publish($source, $target)}：先写临时文件再替换。 */
    private static void publish(Path source, Path target) {
        try {
            Path tmp = Files.createTempFile(target.getParent(), ".bs-update-", ".tmp");
            try {
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new RuntimeException("无法替换更新文件。", e);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static List<? extends ZipEntry> list(ZipFile zip) {
        List<ZipEntry> entries = new ArrayList<>();
        Enumeration<? extends ZipEntry> enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            entries.add(enumeration.nextElement());
        }

        return entries;
    }

    private static String readText(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);

            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String phpVersion() {
        return System.getProperty("neoskin.php.version", "8.5.0");
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        new SecureRandom().nextBytes(buffer);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buffer) {
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }

    private static String sha256(Path file) throws IOException {
        try {
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
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static Map<String, Object> readPackageInfo(Path dir) {
        Path file = dir.resolve("package.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Object decoded = PhpJson.decode(Files.readString(file));
            if (decoded instanceof Map<?, ?> map) {
                Map<String, Object> info = new LinkedHashMap<>();
                map.forEach((key, value) -> info.put(String.valueOf(key), value));

                return info;
            }
        } catch (IOException | RuntimeException ignored) {
            // 与源一致：损坏的 package.json 视为任务不存在
        }

        return null;
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 尽力删除
                }
            });
        } catch (IOException ignored) {
            // 尽力删除
        }
    }
}
