package com.neoskin.web.middleware;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.neoskin.config.AppPaths;
import com.neoskin.service.Option;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Middleware\RedirectToSetup} 的移植（web 组成员）。
 *
 * <pre>
 * $version = config('app.version');
 * $hasLock = file_exists(storage_path('install.lock'));
 * if (!$hasLock) {
 *     config(['translation-loader.translation_loaders' =&gt; []]);   // 关掉 DB 翻译覆盖
 * }
 * if ($hasLock &amp;&amp; !$request-&gt;is('setup*') &amp;&amp; $version &gt; option('version', $version)) {
 *     Artisan::call('update');
 * }
 * if ($hasLock || $request-&gt;is('setup*')) {
 *     return $next($request);
 * }
 * return redirect('/setup');
 * </pre>
 *
 * <p>四个要点：</p>
 * <ol>
 *   <li>没有 {@code install.lock} = <b>未安装</b>：除 {@code setup*} 之外一律跳
 *       {@code /setup}，否则用户会看到一堆「表不存在」的 500；</li>
 *   <li>未安装时源会<b>关掉 DB 翻译层</b>（数据库此时根本不可用）。本工程的
 *       {@code DbTranslationLoader} 已经在没有 lock 时返回空表，故这里无需额外动作；</li>
 *   <li>{@code $request->is('setup*')} 是<b>通配匹配</b>：{@code setup} 与
 *       {@code setup/database} 都算，{@code setupx} 不算；</li>
 *   <li>已安装且版本变新时，源会<b>在请求期同步执行 {@code Artisan::call('update')}</b>。
 *       本工程把它委托给 {@link com.neoskin.service.UpdateRunner}（通过
 *       {@link BooleanSupplier} 注入，避免中间件反向依赖 Spring 上下文）。
 *       升级失败只记日志并放行请求，不对每个请求返回 500。</li>
 * </ol>
 */
public final class RedirectToSetup implements Handler<RoutingContext> {

    private static final Logger log = LoggerFactory.getLogger(RedirectToSetup.class);

    /** 安装向导入口（源 {@code redirect('/setup')}）。 */
    public static final String SETUP_PATH = "/setup";

    /**
     * 升级失败后的退避时间：数据库宕机/权限错误时避免每个请求都重试并刷爆日志。
     * PHP 每个请求都会重试（单线程），Java 侧多线程下必须自我保护。
     */
    static final long FAILURE_BACKOFF_MILLIS = 30_000L;

    private final AppPaths paths;

    /** {@code config('app.version')}。 */
    private final String version;

    private final Option option;

    /** 请求期自动升级；{@code null} 表示不接线（单元测试/无 Spring 上下文）。 */
    private final BooleanSupplier updateTask;

    /** 保证同一时刻只有一个线程执行升级。 */
    private final AtomicBoolean updating = new AtomicBoolean();

    /** 失败退避截止时间（{@link System#currentTimeMillis()}）。 */
    private volatile long nextAttemptAt;

    public RedirectToSetup(AppPaths paths, String version, Option option) {
        this(paths, version, option, null);
    }

    /**
     * @param updateTask 执行 {@code update} 并返回是否成功；失败应抛异常或返回
     *                   {@code false}。传 {@code null} 时只记录 {@code neoskin.outdated}。
     */
    public RedirectToSetup(AppPaths paths, String version, Option option,
            BooleanSupplier updateTask) {
        this.paths = paths;
        this.version = version;
        this.option = option;
        this.updateTask = updateTask;
    }

    @Override
    public void handle(RoutingContext ctx) {
        boolean hasLock = Files.exists(paths.installLock());
        String path = ctx.normalizedPath();

        if (!hasLock) {
            // 源：config(['translation-loader.translation_loaders' => []])
            // 本工程的 DbTranslationLoader 自带 install.lock 门控，无需动作。
        } else if (!isSetupPath(path)) {
            // 源：版本升级时 Artisan::call('update')
            String installed = String.valueOf(option.get("version", version));
            if (isNewer(version, installed)) {
                // 保留给视图/调试用的标记；真正的升级见 runUpdate()。
                ctx.put("neoskin.outdated", installed);
                runUpdate(installed);
            }
        }

        if (hasLock || isSetupPath(path)) {
            ctx.next();
            return;
        }

        // redirect('/setup')：Laravel 会补成绝对 URL
        String host = ctx.request().getHeader("Host");
        String location = (host == null || host.isEmpty() ? "http://localhost" : "http://" + host)
                + SETUP_PATH;
        ctx.response()
                .setStatusCode(302)
                .putHeader("Location", location)
                .end();
    }

    /**
     * 同步执行一次自动升级（与 PHP 一样在当前请求内完成）。
     *
     * <p>网络/数据库异常绝不能冒泡成 500：PHP 在同样情况下会 500，但本工程是
     * 多线程常驻进程，一次故障不应放大成「所有请求都 500」。失败后
     * {@link #FAILURE_BACKOFF_MILLIS} 内不再重试。</p>
     */
    private void runUpdate(String installed) {
        if (updateTask == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextAttemptAt) {
            return;
        }
        if (!updating.compareAndSet(false, true)) {
            // 已有线程在升级：当前请求按旧状态继续，避免重复迁移/阻塞。
            return;
        }

        try {
            boolean ok = updateTask.getAsBoolean();
            if (ok) {
                nextAttemptAt = 0L;
                log.info("auto update from {} to {} finished", installed, version);
            } else {
                nextAttemptAt = System.currentTimeMillis() + FAILURE_BACKOFF_MILLIS;
                log.warn("auto update from {} to {} did not complete; will retry later",
                        installed, version);
            }
        } catch (RuntimeException e) {
            nextAttemptAt = System.currentTimeMillis() + FAILURE_BACKOFF_MILLIS;
            // 数据库不可用时 option/SchemaInstaller 会抛 RuntimeException；
            // 这里降级为日志，请求继续按旧版本提供服务。
            log.warn("auto update from {} to {} failed: {}", installed, version,
                    e.toString());
            log.debug("auto update failure detail", e);
        } finally {
            updating.set(false);
        }
    }

    /** {@code $request->is('setup*')}：按路径段匹配，不是简单前缀。 */
    static boolean isSetupPath(String path) {
        if (path == null) {
            return false;
        }
        String p = path.startsWith("/") ? path.substring(1) : path;
        return p.equals("setup") || p.startsWith("setup/");
    }

    /**
     * {@code Composer\Semver\Comparator::greaterThan($a, $b)} 的简化实现。
     * 只比较数字段（本项目的版本号形如 {@code 6.0.11_patch}）。
     */
    static boolean isNewer(String a, String b) {
        int[] left = parse(a);
        int[] right = parse(b);
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            if (left[i] != right[i]) {
                return left[i] > right[i];
            }
        }
        return left.length > right.length;
    }

    private static int[] parse(String version) {
        if (version == null || version.isEmpty()) {
            return new int[] { 0 };
        }
        // 只取数字段：6.0.11_patch → [6, 0, 11]，后缀（patch/beta）不参与比较
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d+)").matcher(version);
        java.util.List<Integer> parts = new java.util.ArrayList<>();
        while (m.find()) {
            parts.add(Integer.parseInt(m.group(1)));
        }
        if (parts.isEmpty()) {
            return new int[] { 0 };
        }
        int[] out = new int[parts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = parts.get(i);
        }
        return out;
    }
}
