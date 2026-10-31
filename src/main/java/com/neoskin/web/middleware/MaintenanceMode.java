package com.neoskin.web.middleware;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.neoskin.config.AppPaths;
import com.neoskin.support.PhpJson;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code Illuminate\Foundation\Http\Middleware\PreventRequestsDuringMaintenance}。
 *
 * <p>源：{@code storage/framework/down} 存在时，除白名单外一律返回
 * {@code status}（默认 503），并按 JSON 里的 {@code retry} 设置
 * {@code Retry-After}。{@code php artisan down} 的 {@code secret} 免维护
 * cookie 未实现（见 PORT-NOTES §38）。</p>
 */
public final class MaintenanceMode implements Handler<RoutingContext> {

    private static final String DEFAULT_BODY = "<!DOCTYPE html><html><head>"
            + "<meta charset=\"UTF-8\"><title>503 Service Unavailable</title></head>"
            + "<body><h1>Service Unavailable</h1></body></html>";

    /** 免维护 cookie 名（源 {@code laravel_maintenance}）。 */
    public static final String MAINTENANCE_COOKIE = "laravel_maintenance";

    /** cookie 有效期（秒）：与源一致，用 1 小时。 */
    private static final long MAINTENANCE_COOKIE_SECONDS = 3600;

    private final AppPaths paths;

    public MaintenanceMode(AppPaths paths) {
        this.paths = paths;
    }

    public static Path downFile(AppPaths paths) {
        return paths.storage("framework/down");
    }

    @Override
    public void handle(RoutingContext ctx) {
        Path down = downFile(paths);
        if (!Files.isRegularFile(down)) {
            ctx.next();
            return;
        }

        int status = 503;
        String retry = null;
        String render = null;
        String secret = null;
        try {
            Object decoded = PhpJson.decode(Files.readString(down, StandardCharsets.UTF_8));
            if (decoded instanceof java.util.Map<?, ?> map) {
                Object rawStatus = map.get("status");
                if (rawStatus instanceof Number number) {
                    status = number.intValue();
                }
                Object rawRetry = map.get("retry");
                if (rawRetry instanceof Number number) {
                    retry = String.valueOf(number.longValue());
                } else if (rawRetry != null && !String.valueOf(rawRetry).isEmpty()) {
                    retry = String.valueOf(rawRetry);
                }
                Object rawRender = map.get("render");
                if (rawRender != null && !String.valueOf(rawRender).isBlank()) {
                    render = String.valueOf(rawRender);
                }
                Object rawSecret = map.get("secret");
                if (rawSecret != null && !String.valueOf(rawSecret).isBlank()) {
                    secret = String.valueOf(rawSecret);
                }
            }
        } catch (Exception ignored) {
            // 与源一致：down 文件损坏时仍进入维护模式，用默认 503 页
        }

        // 源：secret 免维护 —— ?secret=xxx 放行并种 cookie；带 cookie 也放行
        if (secret != null) {
            String querySecret = ctx.request().getParam("secret");
            if (secret.equals(querySecret)) {
                ctx.response().addCookie(io.vertx.core.http.Cookie
                        .cookie(MAINTENANCE_COOKIE, secret)
                        .setPath("/")
                        .setHttpOnly(true)
                        .setMaxAge(MAINTENANCE_COOKIE_SECONDS));
                ctx.next();
                return;
            }
            io.vertx.core.http.Cookie cookie = ctx.request().getCookie(MAINTENANCE_COOKIE);
            if (cookie != null && secret.equals(cookie.getValue())) {
                ctx.next();
                return;
            }
        }

        ctx.response().setStatusCode(status)
                .putHeader("Content-Type", "text/html; charset=UTF-8");
        if (retry != null) {
            ctx.response().putHeader("Retry-After", retry);
        }
        ctx.response().end(render == null ? DEFAULT_BODY : render);
    }
}
