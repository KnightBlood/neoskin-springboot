package com.neoskin.web.middleware;

import java.nio.file.Files;

import com.neoskin.config.AppPaths;
import com.neoskin.service.Option;
import com.neoskin.web.controllers.SetupPages;
import com.neoskin.web.i18n.Translator;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Middleware\CheckInstallation} 的移植（{@code setup} 别名）。
 *
 * <p>已安装（{@code storage/install.lock} 存在）时，访问安装向导一律渲染
 * {@code setup.locked} 页面而不是继续执行。</p>
 */
public final class CheckInstallation implements Handler<RoutingContext> {

    private final AppPaths paths;
    private final Option option;
    private final Translator translator;
    private final String version;

    public CheckInstallation(AppPaths paths, Option option, Translator translator,
            String version) {
        this.paths = paths;
        this.option = option;
        this.translator = translator;
        this.version = version;
    }

    @Override
    public void handle(RoutingContext ctx) {
        if (paths != null && Files.exists(paths.installLock())) {
            SetupPages.render(ctx, "setup/locked",
                    SetupPages.variables(ctx, option, translator, version));
            return;
        }
        ctx.next();
    }
}
