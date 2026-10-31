package com.neoskin.config;

import java.io.File;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

/**
 * 应用目录解析。
 *
 * <p>源项目用 Laravel 的 {@code base_path()} / {@code storage_path()} 等辅助函数定位文件，
 * Java 版需要一个等价物。这里把所有「相对路径 → 绝对路径」的换算集中到一处，
 * 好处是：
 * <ul>
 *   <li>单元测试可以传入临时目录，不会污染工作区；</li>
 *   <li>{@code storage/install.lock}、{@code storage/options.php}、纹理目录等
 *       关键路径只有一处定义，不会出现两处不一致。</li>
 * </ul>
 */
@Component
public class AppPaths {

    private final Path baseDir;
    private final Path storageRoot;
    private final Path textures;
    private final Path plugins;

    @org.springframework.beans.factory.annotation.Autowired
    public AppPaths(NeoSkinSettings settings) {
        this(Path.of(System.getProperty("user.dir")), settings);
    }

    /** 测试用：显式指定应用根目录。 */
    public AppPaths(Path baseDir, NeoSkinSettings settings) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.storageRoot = resolve(settings.getStorage().getRoot());
        this.textures = resolve(settings.getStorage().getTextures());
        this.plugins = resolve(settings.getPlugins().getDirectory());
    }

    /** 对应 Laravel 的 {@code base_path()}。 */
    public Path base() {
        return baseDir;
    }

    /** 对应 {@code storage_path()}。 */
    public Path storage() {
        return storageRoot;
    }

    /** 对应 {@code storage_path($path)}。 */
    public Path storage(String child) {
        return storageRoot.resolve(child);
    }

    /** 对应 {@code Storage::disk('textures')} 的根目录。 */
    public Path textures() {
        return textures;
    }

    /** 对应 {@code base_path('plugins')}。 */
    public Path plugins() {
        return plugins;
    }

    /**
     * 对应 Laravel 的 {@code public_path()}。
     *
     * <p>Web 服务器（Nginx / Apache / Laravel 内置服务器）在 PHP 侧直接把这个
     * 目录暴露成文档根；本工程没有 Servlet 容器，改由 Vert.x 的
     * {@code StaticHandler} 提供（见 {@code web/StaticAssets}）。</p>
     *
     * <p>与迁移决策一致（PORT-NOTES §8）：{@code public/app/} 是源项目的
     * <b>预编译前端产物</b>，原样拷贝进来即可，不重新 webpack 打包。</p>
     */
    public Path publicDir() {
        return baseDir.resolve("public");
    }

    /** 对应 {@code public_path($path)}。 */
    public Path publicDir(String child) {
        return publicDir().resolve(child);
    }

    /** 对应 {@code storage_path('install.lock')}。 */
    public Path installLock() {
        return storageRoot.resolve("install.lock");
    }

    /** 对应 {@code storage_path('options.php')}。 */
    public Path optionsCache() {
        return storageRoot.resolve("options.php");
    }

    /** 相对路径按应用根目录解析；绝对路径原样返回。 */
    public Path resolve(String path) {
        if (path == null || path.isBlank()) {
            return baseDir;
        }
        Path p = Path.of(path);
        return p.isAbsolute() ? p.normalize() : baseDir.resolve(p).normalize();
    }

    /** 对应 {@code config/database.php} 里 SQLite 的相对路径解析基准。 */
    public File baseFile() {
        return baseDir.toFile();
    }
}
