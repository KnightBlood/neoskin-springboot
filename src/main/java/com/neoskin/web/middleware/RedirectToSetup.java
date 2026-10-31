package com.neoskin.web.middleware;

import java.nio.file.Files;

import com.neoskin.config.AppPaths;
import com.neoskin.service.Option;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Middleware\RedirectToSetup} 的移植（web 组成员）。
 *
 * <pre>
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
 * <p>三个要点：</p>
 * <ol>
 *   <li>没有 {@code install.lock} = <b>未安装</b>：除 {@code setup*} 之外一律跳
 *       {@code /setup}，否则用户会看到一堆「表不存在」的 500；</li>
 *   <li>未安装时源会<b>关掉 DB 翻译层</b>（数据库此时根本不可用）。本工程的
 *       {@code DbTranslationLoader} 已经在没有 lock 时返回空表，故这里无需额外动作；</li>
 *   <li>{@code $request->is('setup*')} 是<b>通配匹配</b>：{@code setup} 与
 *       {@code setup/database} 都算，{@code setupx} 不算。</li>
 * </ol>
 */
public final class RedirectToSetup implements Handler<RoutingContext> {

    /** 安装向导入口（源 {@code redirect('/setup')}）。 */
    public static final String SETUP_PATH = "/setup";

    private final AppPaths paths;

    /** {@code config('app.version')}。 */
    private final String version;

    private final Option option;

    public RedirectToSetup(AppPaths paths, String version, Option option) {
        this.paths = paths;
        this.version = version;
        this.option = option;
    }

    @Override
    public void handle(RoutingContext ctx) {
        boolean hasLock = Files.exists(paths.installLock());
        String path = ctx.normalizedPath();

        if (!hasLock) {
            // 源：config(['translation-loader.translation_loaders' => []])
            // 本工程的 DbTranslationLoader 自带 install.lock 门控，无需动作。
        } else if (!isSetupPath(path)) {
            // 源：版本升级时 Artisan::call('update') —— 见 PORT-NOTES 偏差 11.8
            String installed = String.valueOf(option.get("version", version));
            if (isNewer(version, installed)) {
                // 不在这里跑迁移：启动期写库风险高，且 update 命令还包含
                // 资源包解压 / 缓存清理等动作。记录在案，由运维显式执行。
                ctx.put("neoskin.outdated", installed);
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
