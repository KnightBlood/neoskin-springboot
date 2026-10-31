package com.neoskin.web.middleware;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

import com.neoskin.service.session.FileSessionHandler;
import com.neoskin.service.session.SessionHandler;

/**
 * Laravel {@code bootstrap/app.php} 的中间件装配 → Vert.x handler 链。
 *
 * <p>源结构（Laravel 11+ 配置式，与旧 Kernel 一比一）：</p>
 * <ul>
 *   <li><b>全局栈</b>（{@code $middleware->use}）：Maintenance、ValidatePostSize、
 *       TrimStrings、ConvertEmptyStringsToNull、DetectLanguagePrefer；</li>
 *   <li><b>web 组</b>：EncryptCookies、AddQueuedCookiesToResponse、StartSession、
 *       ShareErrorsFromSession、PreventRequestForgery、EnforceEverGreen、
 *       RedirectToSetup、SubstituteBindings；</li>
 *   <li><b>api 组</b>：SubstituteBindings；</li>
 *   <li><b>authorize 组</b>：auth:web、RejectBannedUser、EnsureEmailFilled、
 *       FireUserAuthenticated；</li>
 *   <li><b>别名</b>：auth→Authenticate、guest→RedirectIfAuthenticated、
 *       role→CheckRole、setup→CheckInstallation、verified→CheckUserVerified、
 *       scope/scopes→Passport token 检查、throttle→ThrottleRequests、
 *       cache.headers→SetCacheHeaders（后者的头在 action 内写）。</li>
 * </ul>
 *
 * <p>routes.json 里的 middleware 字段是混着写的（组名 / 带参数别名 /
 * 框架 FQCN / {@code Closure}）。本类产出<b>展开后的执行序</b>；查不到 handler 的
 * token 会穿透，因此 {@code MiddlewareCoverageTest} 强制要求每个 token 要么已在
 * {@link #connectValueLayer} 注册，要么在它的 documented no-op 名单里
 * （防止「中间件静默缺失」，见 PORT-NOTES §36）。</p>
 */
public final class MiddlewareChain {

    /** bootstrap/app.php 的全局栈（顺序敏感）。 */
    private static final List<String> GLOBAL = List.of(
            "Illuminate\\Foundation\\Http\\Middleware\\PreventRequestsDuringMaintenance",
            "Illuminate\\Foundation\\Http\\Middleware\\ValidatePostSize",
            "Illuminate\\Foundation\\Http\\Middleware\\TrimStrings",
            "App\\Http\\Middleware\\ConvertEmptyStringsToNull",
            "App\\Http\\Middleware\\DetectLanguagePrefer");

    /** 组定义：组名 → 展开（组可以嵌组，如 authorize 里的 auth:web）。 */
    private static final Map<String, List<String>> GROUPS = Map.of(
            "web", List.of(
                    "Illuminate\\Cookie\\Middleware\\EncryptCookies",
                    "Illuminate\\Cookie\\Middleware\\AddQueuedCookiesToResponse",
                    "Illuminate\\Session\\Middleware\\StartSession",
                    "Illuminate\\View\\Middleware\\ShareErrorsFromSession",
                    "Illuminate\\Foundation\\Http\\Middleware\\PreventRequestForgery",
                    "App\\Http\\Middleware\\EnforceEverGreen",
                    "App\\Http\\Middleware\\RedirectToSetup",
                    "Illuminate\\Routing\\Middleware\\SubstituteBindings",
                    // Passport 的 PassportServiceProvider 会把它追加到 web 组
                    "Laravel\\Passport\\Http\\Middleware\\CreateFreshApiToken"),
            "api", List.of(
                    "Illuminate\\Routing\\Middleware\\SubstituteBindings"),
            "authorize", List.of(
                    "auth:web",
                    "App\\Http\\Middleware\\RejectBannedUser",
                    "App\\Http\\Middleware\\EnsureEmailFilled",
                    "App\\Http\\Middleware\\FireUserAuthenticated"));

    /** 别名 → FQCN（具名参数留在 token 里，如 {@code role:admin}）。 */
    private static final Map<String, String> ALIASES = Map.of(
            "auth", "App\\Http\\Middleware\\Authenticate",
            "guest", "App\\Http\\Middleware\\RedirectIfAuthenticated",
            "role", "App\\Http\\Middleware\\CheckRole",
            "setup", "App\\Http\\Middleware\\CheckInstallation",
            "verified", "App\\Http\\Middleware\\CheckUserVerified",
            "scope", "Laravel\\Passport\\Http\\Middleware\\CheckTokenForAnyScope",
            "scopes", "Laravel\\Passport\\Http\\Middleware\\CheckToken",
            "throttle", "Illuminate\\Routing\\Middleware\\ThrottleRequests",
            "cache.headers", "Illuminate\\Http\\Middleware\\SetCacheHeaders");

    /** 全局栈后面的组是路由里写明的：web 默认全站、api 进 api 组。 */
    private static final Set<String> GROUP_TOKENS = Set.of("web", "api", "authorize");

    private MiddlewareChain() {
    }

    /**
     * 展开一条路由中间件序：全局栈 + 路由自带条目（组展开、别名改写、
     * 维持语料里的书写顺序）。组内不递归的顺序直接照 GROUPS；只有一个
     * 嵌套点：authorize 组的 {@code auth:web}。
     */
    public static List<String> expand(List<String> routeMiddleware) {
        List<String> out = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>(routeMiddleware);

        for (String global : GLOBAL) {
            out.add(global);
        }

        while (!queue.isEmpty()) {
            String token = queue.poll();

            if (GROUP_TOKENS.contains(token)) {
                // 组按定义展开（嵌套条目从队首继续展开，等价 Laravel 的深度扁平化）。
                List<String> group = GROUPS.get(token);

                for (int i = group.size() - 1; i >= 0; i--) {
                    queue.addFirst(group.get(i));
                }
                continue;
            }

            out.add(canonical(token));
        }

        return out;
    }

    /** 别名改写 + 参数保留：{@code role:admin} → {@code CheckRole:admin}。 */
    public static String canonical(String token) {
        String name = token;
        String params = null;

        int colon = token.indexOf(':');

        if (colon >= 0) {
            name = token.substring(0, colon);
            params = token.substring(colon + 1);
        }

        String fqcn = ALIASES.getOrDefault(name, name);

        return params == null ? fqcn : fqcn + ":" + params;
    }

    /**
     * 把整条路由的中间件装配成 Vert.x handler 链（不含 controller，controller
     * 由调用方接在 {@code handlers} 之后）。
     *
     * <p>未移植的中间件目前<b>穿透</b>（返回 null 跳过）；已移植的见 {@link #handlers}。
     * 展开序经 {@link #expand} 保留。</p>
     */
    public static List<Handler<RoutingContext>> handlers(List<String> routeMiddleware,
            Map<String, Handler<RoutingContext>> ported) {
        List<Handler<RoutingContext>> out = new ArrayList<>();

        for (String token : expand(routeMiddleware)) {
            Handler<RoutingContext> handler = ported.get(token);

            if (handler == null) {
                // 带参数的别名（如 auth:web）展开后是
                // "App\Http\Middleware\Authenticate:web"，而注册表按类名登记。
                // 参数是中间件自身的配置（guard 名等），不影响「用哪个 handler」，
                // 所以剥掉冒号后的部分再查一次。
                int colon = token.indexOf(':');
                if (colon > 0) {
                    handler = ported.get(token.substring(0, colon));
                }
            }

            if (handler != null) {
                out.add(wrap(handler, token));
            }
        }

        return out;
    }

    /**
     * 带参数的中间件转发：{@code CheckRole:admin} 的 token 参数通过
     * {@link ParameterizedHandler#handle} 传进去（包装的 handler 在链上不可见）。
     */
    private static Handler<RoutingContext> wrap(Handler<RoutingContext> handler, String token) {
        int colon = token.indexOf(':');

        if (colon < 0 || !(handler instanceof ParameterizedHandler parameterized)) {
            return handler;
        }

        String params = token.substring(colon + 1);

        return ctx -> parameterized.handle(ctx, params);
    }

    /** 该工程当前已移植到 Vert.x handler 的中间件注册表（随移植增量）。 */
    public static Map<String, Handler<RoutingContext>> connectValueLayer(
            Map<String, Handler<RoutingContext>> into,
            EncryptCookies cookies,
            java.nio.file.Path sessionDirectory) {
        return connectValueLayer(into, cookies, sessionDirectory, null, null, null, null, null);
    }

    /** 完整装配（含安装门控 + 语言 + 浏览器门槛，无翻译器）。 */
    public static Map<String, Handler<RoutingContext>> connectValueLayer(
            Map<String, Handler<RoutingContext>> into,
            EncryptCookies cookies,
            java.nio.file.Path sessionDirectory,
            com.neoskin.config.AppPaths paths,
            String version,
            com.neoskin.service.Option option) {
        return connectValueLayer(into, cookies, sessionDirectory, paths, version, option, null);
    }

    /**
     * 完整装配（含安装门控 + 语言 + 浏览器门槛）。
     *
     * @param paths      {@code AppPaths}；{@code null} 时不挂 {@code RedirectToSetup}
     * @param version    {@code config('app.version')}
     * @param option     选项服务（版本升级判定用）
     * @param translator 翻译器（{@code EnforceEverGreen} 的提示文案）
     */
    public static Map<String, Handler<RoutingContext>> connectValueLayer(
            Map<String, Handler<RoutingContext>> into,
            EncryptCookies cookies,
            java.nio.file.Path sessionDirectory,
            com.neoskin.config.AppPaths paths,
            String version,
            com.neoskin.service.Option option,
            com.neoskin.web.i18n.Translator translator) {
        // 便捷路径：路径版收 file handler（SESSION_DRIVER=file 的默认形态）。
        return connectValueLayer(into, cookies,
                sessionDirectory == null ? null
                        : new FileSessionHandler(sessionDirectory, StartSession.LIFETIME_MINUTES),
                paths, version, option, translator);
    }

    /**
     * 完整装配（核心，{@code SessionHandler} 版）：与路径版等价，但会话存储
     * 直接给定（{@code SESSION_DRIVER=redis} 时是 {@link CacheBasedSessionHandler}）。
     */
    public static Map<String, Handler<RoutingContext>> connectValueLayer(
            Map<String, Handler<RoutingContext>> into,
            EncryptCookies cookies,
            SessionHandler sessionHandle,
            com.neoskin.config.AppPaths paths,
            String version,
            com.neoskin.service.Option option,
            com.neoskin.web.i18n.Translator translator) {
        // 全局栈里的请求归一化（ValidatePostSize + TrimStrings + EmptyStringsToNull）。
        into.put("Illuminate\\Foundation\\Http\\Middleware\\ValidatePostSize",
                RequestInput.normalizer());
        into.put("App\\Http\\Middleware\\ConvertEmptyStringsToNull",
                passThrough());

        // EncryptCookies（web 组第一个）：请求侧解密并挂 ctx。
        into.put("Illuminate\\Cookie\\Middleware\\EncryptCookies",
                ctx -> intoCookies(ctx, cookies));

        // StartSession（web 组第三个）：id 解密后取自 EncryptCookies 的结果。
        into.put("Illuminate\\Session\\Middleware\\StartSession",
                new StartSession(sessionHandle, cookies));

        // PreventRequestForgery（web 组第五个）：在 StartSession 之后判定。
        into.put("Illuminate\\Foundation\\Http\\Middleware\\PreventRequestForgery",
                new VerifyCsrfToken(cookies == null ? null : cookies.encrypter()));

        // EnforceEverGreen（web 组第六个）：老浏览器 → errors.pretty（状态 200）。
        if (translator != null) {
            into.put("App\\Http\\Middleware\\EnforceEverGreen", new EnforceEverGreen(translator));
        }

        // RedirectToSetup（web 组第七个）：未安装时把非 setup* 请求导向 /setup。
        if (paths != null) {
            into.put("App\\Http\\Middleware\\RedirectToSetup",
                    new RedirectToSetup(paths, version, option));
            // 全局栈第一个：维护模式（storage/framework/down）。
            into.put("Illuminate\\Foundation\\Http\\Middleware\\PreventRequestsDuringMaintenance",
                    new MaintenanceMode(paths));
        }

        // throttle:30,1 / throttle:90,1（CacheStore：file 默认 / redis 可共享）。
        com.neoskin.service.cache.CacheStore cacheStore =
                com.neoskin.service.cache.CacheRuntime.store();
        if (cacheStore == null) {
            cacheStore = paths == null
                    ? new com.neoskin.service.cache.ArrayCache()
                    : new com.neoskin.service.cache.FileCache(
                            paths.storage("framework/cache/data"));
        }
        into.put("Illuminate\\Routing\\Middleware\\ThrottleRequests",
                new ThrottleRequests(cacheStore));

        return into;
    }

    /**
     * 完整装配（含安装门控 + 语言 + 浏览器门槛 + 认证中间件）。
     *
     * @param paths      {@code AppPaths}；{@code null} 时不挂 {@code RedirectToSetup}
     * @param version    {@code config('app.version')}
     * @param option     选项服务（版本升级判定用）
     * @param translator 翻译器（{@code EnforceEverGreen} 的提示文案）
     * @param users      用户仓库（{@code Authenticate} 取登录用户）；{@code null}
     *                   时不挂认证中间件（该路由随后会 501，见类注释）
     * @param sessionDir 会话目录；{@code null} 时也跳过认证中间件
     */
    public static Map<String, Handler<RoutingContext>> connectValueLayer(
            Map<String, Handler<RoutingContext>> into,
            EncryptCookies cookies,
            java.nio.file.Path sessionDirectory,
            com.neoskin.config.AppPaths paths,
            String version,
            com.neoskin.service.Option option,
            com.neoskin.web.i18n.Translator translator,
            com.neoskin.db.UserRepository users) {
        return connectValueLayer(into, cookies,
                sessionDirectory == null ? null
                        : new FileSessionHandler(sessionDirectory, StartSession.LIFETIME_MINUTES),
                paths, version, option, translator, users);
    }

    /**
     * 完整装配（核心，{@code SessionHandler} 版 + 认证中间件）。
     *
     * @param users 用户仓库（{@code Authenticate} 取登录用户）；{@code null}
     *              时不挂认证中间件（该路由随后会 501，见类注释）
     */
    public static Map<String, Handler<RoutingContext>> connectValueLayer(
            Map<String, Handler<RoutingContext>> into,
            EncryptCookies cookies,
            SessionHandler sessionHandle,
            com.neoskin.config.AppPaths paths,
            String version,
            com.neoskin.service.Option option,
            com.neoskin.web.i18n.Translator translator,
            com.neoskin.db.UserRepository users) {
        connectValueLayer(into, cookies, sessionHandle, paths, version, option, translator);

        if (users != null && translator != null && sessionHandle != null) {
            // ShareErrorsFromSession（web 组第四个）：把会话里的 errors 解出来挂 ctx。
            into.put("Illuminate\\View\\Middleware\\ShareErrorsFromSession",
                    new ShareErrorsFromSession());

            // authorize 组：auth:web → RejectBannedUser → EnsureEmailFilled。
            // FireUserAuthenticated 只发事件（无插件时无副作用），穿透即可。
            into.put("App\\Http\\Middleware\\Authenticate",
                    new Authenticate(users, translator));
            into.put("App\\Http\\Middleware\\RejectBannedUser",
                    new RejectBannedUser(translator));
            into.put("App\\Http\\Middleware\\EnsureEmailFilled",
                    new EnsureEmailFilled());

            // FireUserAuthenticated（authorize 组）：认证通过后派发 UserAuthenticated
            // （源 Middleware\FireUserAuthenticated → Events\UserAuthenticated）
            into.put("App\\Http\\Middleware\\FireUserAuthenticated", ctx -> {
                com.neoskin.db.UserRepository.UserRow user = Authenticate.currentUser(ctx);
                if (user != null) {
                    com.neoskin.event.EventRuntime.fire(
                            new com.neoskin.event.Events.UserAuthenticated(user));
                }
                ctx.next();
            });

            // guest 别名（web,guest 的 login/register/forgot/reset 路由）。
            into.put("App\\Http\\Middleware\\RedirectIfAuthenticated",
                    new RedirectIfAuthenticated());

            // role:* 别名（admin 组的路由，如 admin/communications）。
            into.put("App\\Http\\Middleware\\CheckRole", new CheckRole());

            // verified 别名：require_verification 打开时未验证用户 403。
            if (option != null && translator != null) {
                into.put("App\\Http\\Middleware\\CheckUserVerified",
                        new CheckUserVerified(option, translator));
            }

            // Passport：web 响应种 laravel_token（前端 fetch /api/* 免 Bearer token）
            into.put("Laravel\\Passport\\Http\\Middleware\\CreateFreshApiToken",
                    new CreateFreshApiToken(users));

            // Passport：scope 校验中间件（auth:oauth 之后执行）。
            into.put("Laravel\\Passport\\Http\\Middleware\\CheckTokenForAnyScope",
                    new PassportScopeMiddleware(true));
            into.put("Laravel\\Passport\\Http\\Middleware\\CheckToken",
                    new PassportScopeMiddleware(false));

            // Ignition：可运行解决方案门控（未开启时 /_ignition/* 一律 404）。
            into.put("Spatie\\LaravelIgnition\\Http\\Middleware\\RunnableSolutionsEnabled",
                    new RunnableSolutionsEnabled());

            // setup 别名：安装向导的「已安装」门控。
            if (paths != null && option != null && translator != null) {
                into.put("App\\Http\\Middleware\\CheckInstallation",
                        new CheckInstallation(paths, option, translator, version));
            }
        }

        return into;
    }

    /** 归一化已在 {@link RequestInput#normalizer()} 里一次做完，此占位只放行。 */
    private static Handler<RoutingContext> passThrough() {
        return RoutingContext::next;
    }

    /** {@code EncryptCookies} 请求侧：解密进 ctx 后继续链路。 */
    static void intoCookies(RoutingContext ctx, EncryptCookies cookies) {
        var raw = new com.neoskin.support.PhpJson.Array();

        for (io.vertx.core.http.Cookie cookie : ctx.request().cookies()) {
            raw.put(cookie.getName(), cookie.getValue());
        }

        ctx.put(EncryptCookies.CTX_KEY, cookies == null ? raw : cookies.decrypt(raw));
        ctx.next();
    }
}
