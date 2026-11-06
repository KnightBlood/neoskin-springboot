package com.neoskin.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.event.EventRuntime;
import com.neoskin.migration.DataMigrator;
import com.neoskin.service.cache.CacheStore;

/**
 * {@code php artisan update}（{@code App\Console\Commands\UpdateCommand}）的唯一实现。
 *
 * <p>这里把「结构迁移 + 4 个数据迁移 + 版本号 + install.lock + 缓存清理」收敛到一处，
 * 供两处复用：</p>
 * <ol>
 *   <li>CLI：{@code --neoskin.command=update}（{@link com.neoskin.cli.Commands}）；</li>
 *   <li>请求期自动升级：{@code App\Http\Middleware\RedirectToSetup} 发现
 *       {@code config('app.version')} 比 {@code options.version} 新时调用本类。</li>
 * </ol>
 *
 * <h2>与 PHP 的逐条对应</h2>
 * <pre>{@code
 * $this->procedures()->each(function ($procedure, $version) {
 *     if (Comparator::lessThan(option('version'), $version)) {
 *         $procedure();
 *     }
 * });
 * option(['version' => config('app.version')]);
 * $artisan->call('migrate', ['--force' => true]);
 * $artisan->call('view:clear');
 * $filesystem->put(storage_path('install.lock'), '');
 * Cache::flush();
 * }</pre>
 *
 * <ul>
 *   <li>{@code procedures()}：
 *     <ul>
 *       <li>{@code 0.0.1} → {@code event('__0.0.1')}（仅为测试/插件保留）；</li>
 *       <li>{@code 5.0.0} → {@code home_pic_url} 从 {@code ./app/bg.jpg}
 *           改为 {@code ./app/bg.webp}。</li>
 *     </ul>
 *   </li>
 *   <li>{@code migrate --force} → {@link SchemaInstaller#install()}（23 条结构迁移）
 *       + {@link DataMigrator#migrate()}（4 条数据迁移）；</li>
 *   <li>{@code view:clear} → 本工程 Thymeleaf 解析器固定 {@code cacheable=false}
 *       （{@code ViewEngine}），没有编译视图缓存，等价于 no-op；</li>
 *   <li>{@code Cache::flush()} → {@link CacheStore#flush()}。</li>
 * </ul>
 *
 * <h2>幂等与并发</h2>
 * <p>结构/数据迁移自身由 {@code migrations} 表保证幂等；版本号与 lock 文件重复写
 * 也是安全的。方法整体 {@code synchronized}，因此即使多个请求同时判定「需要升级」，
 * 也只会有一个线程真正执行。</p>
 */
@Component
public class UpdateRunner {

    private static final Logger log = LoggerFactory.getLogger(UpdateRunner.class);

    /** 5.0.0 数据迁移的旧值。 */
    public static final String HOME_PIC_OLD = "./app/bg.jpg";

    /** 5.0.0 数据迁移的新值。 */
    public static final String HOME_PIC_NEW = "./app/bg.webp";

    /** {@code 0.0.1} 事件的字面量（源里是 {@code event('__0.0.1')}）。 */
    public static final String EVENT_0_0_1 = "__0.0.1";

    private final Option option;
    private final NeoSkinSettings settings;
    private final AppPaths paths;
    private final SchemaInstaller schema;
    private final Optional<DataMigrator> migrator;
    private final Optional<CacheStore> cacheStore;

    public UpdateRunner(Option option, NeoSkinSettings settings, AppPaths paths,
            SchemaInstaller schema, Optional<DataMigrator> migrator,
            Optional<CacheStore> cacheStore) {
        this.option = option;
        this.settings = settings;
        this.paths = paths;
        this.schema = schema;
        this.migrator = migrator == null ? Optional.empty() : migrator;
        this.cacheStore = cacheStore == null ? Optional.empty() : cacheStore;
    }

    /**
     * 执行一次完整更新（与 {@code php artisan update} 等价）。
     *
     * <p>失败会抛出运行时异常：CLI 需要据此返回非 0；请求期自动升级在
     * {@code RedirectToSetup} 里捕获并降级为日志，不影响响应。</p>
     */
    public synchronized void update() {
        runProcedures();

        schema.install();
        migrator.ifPresent(DataMigrator::migrate);

        option.set("version", settings.getVersion());

        Path lock = paths.installLock();
        try {
            Files.createDirectories(lock.getParent());
            Files.write(lock, new byte[0]);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + lock, e);
        }

        // PHP 的 view:clear 在本工程是 no-op（ViewEngine 没有编译缓存）。
        // 但 options.php 缓存必须失效，否则 version 会读到旧值、每次请求都重跑升级。
        option.clearCache();

        cacheStore.ifPresent(CacheStore::flush);

        log.info("update finished: version={}", settings.getVersion());
    }

    /** 按版本顺序执行 {@code procedures()}。 */
    private void runProcedures() {
        // PHP: option('version') 缺失时由 config/options.php 补 ''，不会抛错。
        String installed = option.getString("version", "");

        // 0.0.1：保留的事件挂钩（源里仅用于测试）。
        if (Semver.lessThan(installed, "0.0.1")) {
            EventRuntime.fire(EVENT_0_0_1);
        }

        // 5.0.0：把默认首页背景从 jpg 换成 webp。
        if (Semver.lessThan(installed, "5.0.0")
                && HOME_PIC_OLD.equals(option.getString("home_pic_url", null))) {
            option.set("home_pic_url", HOME_PIC_NEW);
        }
    }
}
