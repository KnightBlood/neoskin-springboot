package com.neoskin.web;

import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.HEAD;
import static io.vertx.core.http.HttpMethod.PATCH;
import static io.vertx.core.http.HttpMethod.POST;
import static io.vertx.core.http.HttpMethod.PUT;
import static io.vertx.core.http.HttpMethod.QUERY;

import java.util.List;
import java.util.Map;

import com.neoskin.web.error.ErrorHandler;
import com.neoskin.web.error.WebException;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * routes.json → Vert.x {@link Router} 的装配器。
 *
 * <p>装配规则（对齐 Laravel 的匹配语义）：</p>
 * <ul>
 *   <li>{@code GET|HEAD|...|QUERY} 多方法组合 → 同一条 Vert.x route 上
 *       {@code method()} 多次；</li>
 *   <li>源 uri 不带前导斜杠（根是 {@code "/"}），Vert.x 统一补成
 *       {@code "/" + uri}；</li>
 *   <li>顺序按 routes.json —— Vert.x 的 route 按注册顺序匹配，和 Laravel 一致；</li>
 *   <li>未移植的 action → HTTP 501（{@code 该路由尚未移植}），移植一个挂一个。</li>
 * </ul>
 *
 * <h2>三条「Vert.x 与 Laravel 不一致、必须自己挡」的语义</h2>
 *
 * <h3>1. 中间的双斜杠</h3>
 * <p>Vert.x 匹配的是 {@code normalizedPath}：{@code /auth//login} 会被折成
 * {@code /auth/login} 从而命中，而源站是 404（实测：{@code path=auth//login}）。
 * {@link #slashGuard()} 用<b>原始</b> {@code request().path()} 拦下来。</p>
 *
 * <p>注意 {@code //auth/login}（<b>前导</b>双斜杠）在源站也是 404 —— Symfony 的
 * {@code getPathInfo()} 保留前导 {@code //}，路由正则 {@code #^/auth/login$#}
 * 匹配不上；只是错误消息里 {@code path()} 会把前导斜杠 trim 掉，所以文案是
 * {@code The route auth/login could not be found.}。同一个守卫覆盖这种情况。</p>
 *
 * <h3>2. 尾斜杠</h3>
 * <p>源站容忍任意数量尾斜杠（{@code /auth/login//} → 200），Vert.x 同样容忍，
 * 所以<b>只需</b>在守卫里把尾斜杠排除掉，不额外处理。</p>
 *
 * <h3>3. 405 / OPTIONS</h3>
 * <p>路径存在但方法不对时，Laravel 抛 {@code MethodNotAllowedHttpException}（405 +
 * {@code Allow}，逗号后带空格），{@code OPTIONS} 则直接回 200 空体 +
 * {@code Allow}（逗号<b>不带</b>空格）。Vert.x 只会说「没命中」，因此
 * {@link #fallback(RouteMatcher)} 用 {@link RouteMatcher} 反查路径，
 * 再 {@code ctx.fail(...)} 交给 {@link ErrorHandler}。</p>
 */
public final class VertxRouteRegistry {

    /** 未移植 action 的兜底响应体。 */
    private static final String NOT_IMPLEMENTED =
            "{\"code\":501,\"message\":\"该路由尚未移植\"}";

    private VertxRouteRegistry() {
    }

    /**
     * 装出完整的 {@link Router}：173 条路由 + 兜底（无中间件链、无错误页）。
     *
     * <p>没有 {@code failureHandler} 时，兜底会直接把 404/405 写成 JSON ——
     * 这条路径只给「不关心 HTML 错误页」的单测用。</p>
     */
    public static BuildResult build(Routes routes, HandlerResolver resolver, Vertx vertx) {
        return build(routes, resolver, vertx, List.of(), null, null, null);
    }

    /** 同上，并给每条路由的前端接入全局中间件栈。 */
    public static BuildResult build(Routes routes, HandlerResolver resolver, Vertx vertx,
            List<Handler<RoutingContext>> globalMiddleware) {
        return build(routes, resolver, vertx, globalMiddleware, null, null, null);
    }

    /** 完整装配：全局栈 → 路由级中间件链 → controller；无错误页。 */
    public static BuildResult build(Routes routes, HandlerResolver resolver, Vertx vertx,
            List<Handler<RoutingContext>> globalMiddleware,
            Map<String, Handler<RoutingContext>> middlewareRegistry) {
        return build(routes, resolver, vertx, globalMiddleware, middlewareRegistry, null, null);
    }

    /** 完整装配（含静态资源）；无错误页。 */
    public static BuildResult build(Routes routes, HandlerResolver resolver, Vertx vertx,
            List<Handler<RoutingContext>> globalMiddleware,
            Map<String, Handler<RoutingContext>> middlewareRegistry,
            java.nio.file.Path staticRoot) {
        return build(routes, resolver, vertx, globalMiddleware, middlewareRegistry, staticRoot, null);
    }

    /**
     * 完整装配。
     *
     * @param staticRoot     {@code public_path()}；非空且存在时，在 173 条路由<b>之前</b>
     *                       挂静态 handler（未命中 fallthrough 继续走动态路由）。
     *                       对齐 PHP 侧「Web 服务器把 public/ 当文档根」的行为；
     * @param failureHandler 异常出口（{@link ErrorHandler}）。{@code null} 时
     *                       404/405 走纯 JSON，供不需要 HTML 错误页的单测使用。
     */
    public static BuildResult build(Routes routes, HandlerResolver resolver, Vertx vertx,
            List<Handler<RoutingContext>> globalMiddleware,
            Map<String, Handler<RoutingContext>> middlewareRegistry,
            java.nio.file.Path staticRoot,
            Handler<RoutingContext> failureHandler) {
        return build(routes, resolver, vertx, globalMiddleware, middlewareRegistry, staticRoot,
                failureHandler, null);
    }

    /** 完整装配（含 SPA 壳与 /api/bootstrap）。 */
    public static BuildResult build(Routes routes, HandlerResolver resolver, Vertx vertx,
            List<Handler<RoutingContext>> globalMiddleware,
            Map<String, Handler<RoutingContext>> middlewareRegistry,
            java.nio.file.Path staticRoot,
            Handler<RoutingContext> failureHandler,
            Handler<RoutingContext> spaHandler) {
        Router router = Router.router(vertx);
        int registered = 0;

        // 静态资源先行：/app/*、/lang/*、favicon 等；未命中自动 fallthrough。
        // /app-next/* 必须交给 SPA handler（壳需要注入 bootstrap），不能被静态 handler 抢走。
        Handler<RoutingContext> staticHandler = staticRoot != null && StaticAssets.available(staticRoot)
                ? StaticAssets.handler(staticRoot)
                : StaticAssets.classpathHandler(staticRoot);
        router.route().handler(ctx -> {
            if (ctx.normalizedPath().startsWith("/app-next/")) {
                ctx.next();
                return;
            }
            staticHandler.handle(ctx);
        });

        for (Handler<RoutingContext> handler : globalMiddleware) {
            router.route().handler(handler);
        }

        // FRONTEND_MODE=next：首页与管理端入口切到共享前端。
        router.route().handler(ctx -> {
            if (SpaFrontend.nextMode()) {
                String path = ctx.normalizedPath();
                if (path.equals("/")) {
                    ctx.response().setStatusCode(302).putHeader("Location", "/app-next/site/").end();
                    return;
                }
                if (path.equals("/admin")) {
                    ctx.response().setStatusCode(302).putHeader("Location", "/app-next/admin/").end();
                    return;
                }
            }
            ctx.next();
        });

        // 双斜杠守卫：必须在路由<b>之前</b>，因为 Vert.x 会先把路径折叠掉。
        router.route().handler(slashGuard());

        // SPA 壳与契约 v1 端点：复用 web 组（EncryptCookies + StartSession）。
        if (spaHandler != null) {
            registerSpaRoute(router, "/api/bootstrap", spaHandler, middlewareRegistry);
            registerSpaRoute(router, "/app-next/:mode", spaHandler, middlewareRegistry);
            registerSpaRoute(router, "/app-next/:mode/*", spaHandler, middlewareRegistry);
            // 契约 v1 增量读取端点。只注册 GET；PUT/POST 仍落到 174 条路由表。
            registerSpaRoute(router, "/api/user/inbox", spaHandler, middlewareRegistry);
            registerSpaRoute(router, "/api/user/inbox/:id", spaHandler, middlewareRegistry);
            registerSpaRoute(router, "/api/user/reports", spaHandler, middlewareRegistry);
            registerSpaRoute(router, "/api/admin/dashboard", spaHandler, middlewareRegistry);
            registerSpaRoute(router, "/api/admin/update/info", spaHandler, middlewareRegistry);
            registerSpaRoute(router, "/api/admin/options/:group", spaHandler, middlewareRegistry);
            registerSpaRoute(router, "/api/admin/communications", spaHandler, middlewareRegistry);
            registerSpaRoute(router, "/api/admin/communications/:id", spaHandler,
                    middlewareRegistry);
        }

        // 源 RouteServiceProvider：event(new ConfigureRoutes($router)) ——
        // 插件（Hook.addRoute）在此注册路由，排在内置 173 条之前、slashGuard 之后。
        com.neoskin.event.EventRuntime.fire(
                new com.neoskin.event.Events.ConfigureRoutes(router));

        for (RouteDefinition route : routes.all()) {
            // Vert.x 的 path 要求前导斜杠；源 uri 是 Laravel 风格无斜杠。
            io.vertx.ext.web.Route vertxRoute = router.route(toVertxPath(route.uri()));

            for (String method : route.methods()) {
                vertxRoute = vertxRoute.method(httpMethod(method));
            }

            // 全局栈里的 LanguageMiddleware 要判断「本路由是否属于 api 组」
            // （源：in_array('api', $request->route()->middleware())）。
            // 全局 handler 已经跑过了，所以这里补一条只写 ctx 的 handler ——
            // 它必须在真正的路由级中间件之前。
            vertxRoute = vertxRoute.handler(routeMiddlewareHint(route.middleware()));

            // StartSession::storeCurrentUrl 需要 route 名（_previous.route）。
            if (route.name() != null && !route.name().isEmpty()) {
                String routeName = route.name();
                vertxRoute = vertxRoute.handler(ctx -> {
                    ctx.put(com.neoskin.web.middleware.StartSession.ROUTE_NAME_KEY, routeName);
                    ctx.next();
                });
            }

            if (middlewareRegistry != null && !middlewareRegistry.isEmpty()) {
                for (Handler<RoutingContext> layering : com.neoskin.web.middleware.MiddlewareChain
                        .handlers(route.middleware(), middlewareRegistry)) {
                    vertxRoute = vertxRoute.handler(layering);
                }
            }

            Handler<RoutingContext> handler = resolver.resolve(route.action(), route.uri());
            vertxRoute.handler(handler != null ? handler : unimplemented());
            registered++;
        }

        // 未命中兜底：404 / 405 / OPTIONS 分流（Laravel 的 handleMatchedRoute）。
        router.route().last().handler(fallback(new RouteMatcher(routes.all())));

        // failure 通道：控制器/中间件 ctx.fail(...) 与兜底 ctx.fail(...) 的出口。
        // 没给 ErrorHandler 时退到纯 JSON（单测只关心状态码/文案）。
        router.route().last().failureHandler(
                failureHandler != null ? failureHandler : plainJsonFailure());

        return new BuildResult(router, registered);
    }

    /** 装配结果：router + 注册的路由条数（应为 173）。 */
    public record BuildResult(Router router, int registeredCount) {
    }

    // ================================================================== 内部

    /**
     * SPA 附加路由：使用 web 组的 cookie/session 中间件，但不计入 173 条 PHP 路由。
     */
    private static void registerSpaRoute(Router router, String path,
            Handler<RoutingContext> spaHandler,
            Map<String, Handler<RoutingContext>> middlewareRegistry) {
        io.vertx.ext.web.Route route = router.route(path).method(GET);
        route = route.handler(ctx -> {
            ctx.put(com.neoskin.web.middleware.LanguageMiddleware.ROUTE_MIDDLEWARE_KEY,
                    List.of("web"));
            ctx.next();
        });

        if (middlewareRegistry != null && !middlewareRegistry.isEmpty()) {
            for (Handler<RoutingContext> layering : com.neoskin.web.middleware.MiddlewareChain
                    .handlers(List.of("web"), middlewareRegistry)) {
                route = route.handler(layering);
            }
        }

        route.handler(spaHandler);
    }

    private static String trim(String uri) {
        return uri.equals("/") ? "" : uri;
    }

    /**
     * Laravel uri → Vert.x path：补前导斜杠，并把路径参数 {@code {name}} 写成
     * {@code :name}（Vert.x 的占位语法）；{@code {name?}} 这类可选参数源表里没有，
     * 出现时按必填处理并记 PORT-NOTES。
     *
     * <p>Laravel 的参数默认匹配「除 {@code /} 外的任意串」（{@code [^/]+}），
     * 与 Vert.x 的 {@code :param} 一致。</p>
     */
    static String toVertxPath(String uri) {
        String raw = "/" + trim(uri);

        return raw.replaceAll("\\{([A-Za-z_][A-Za-z0-9_]*)\\?", ":$1")
                .replaceAll("\\{([A-Za-z_][A-Za-z0-9_]*)\\}", ":$1");
    }

    /** routes.json 的小写 method token → Vert.x 方法。 */
    static HttpMethod httpMethod(String token) {
        return switch (token) {
            case "get" -> GET;
            case "head" -> HEAD;
            case "post" -> POST;
            case "put" -> PUT;
            case "patch" -> PATCH;
            case "delete" -> DELETE;
            case "options" -> HttpMethod.OPTIONS;
            // Laravel 的 RESOURCE query 方法按 GET 语义处理。
            case "query" -> GET;
            default -> throw new IllegalArgumentException("未知 HTTP 方法: " + token);
        };
    }

    /**
     * 原始路径里的双斜杠守卫。
     *
     * <p>先剥掉<b>尾</b>斜杠（源站容忍任意数量），再看剩下的是否还含 {@code //}。
     * 含则按 Laravel 的 404 处理 —— 注意此时<b>不能</b>直接回 404 响应，
     * 而是 {@code ctx.fail}，这样错误页才和「真正没命中」走同一条渲染路。</p>
     *
     * <p>用 {@code request().path()} 而非 {@code normalizedPath()}：前者保留
     * {@code /a//b} 的原样（实测 Vert.x 5：{@code raw=[/a//] norm=[/a/]}）。</p>
     */
    static Handler<RoutingContext> slashGuard() {
        return ctx -> {
            String raw = ctx.request().path();

            if (hasInnerDoubleSlash(raw)) {
                ctx.fail(WebException.notFound(laravelPathOf(raw)));
                return;
            }
            ctx.next();
        };
    }

    /** 剥尾斜杠后是否仍含 {@code //}。 */
    static boolean hasInnerDoubleSlash(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return false;
        }
        int end = rawPath.length();

        while (end > 0 && rawPath.charAt(end - 1) == '/') {
            end--;
        }
        return rawPath.substring(0, end).contains("//");
    }

    /**
     * Laravel {@code Request::path()}：剥尾斜杠、剥前导斜杠，根为 {@code "/"}。
     *
     * <p>错误消息与语言切换链接都用它，所以 {@code //auth/login} 的文案是
     * {@code The route auth/login could not be found.}（前导斜杠被 trim 掉）。</p>
     */
    static String laravelPathOf(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath)) {
            return "/";
        }
        String trimmed = rawPath.replaceAll("/+$", "");
        trimmed = trimmed.replaceFirst("^/+", "");

        return trimmed.isEmpty() ? "/" : trimmed;
    }

    /**
     * 把本路由的中间件清单写进 {@code ctx}，供全局栈里的
     * {@link com.neoskin.web.middleware.LanguageMiddleware} 在响应阶段判断
     * 「是否 api 路由」（源 {@code in_array('api', $request->route()->middleware())}）。
     *
     * <p>必须挂在路由级中间件<b>之前</b>：全局栈已经执行完了，但
     * {@code headersEndHandler} 是后置的，只要在响应发出前写进去就来得及。</p>
     */
    static Handler<RoutingContext> routeMiddlewareHint(List<String> middleware) {
        List<String> copy = List.copyOf(middleware);

        return ctx -> {
            ctx.put(com.neoskin.web.middleware.LanguageMiddleware.ROUTE_MIDDLEWARE_KEY, copy);
            ctx.next();
        };
    }

    /** 501 兜底 handler：控制器移植进度之外的占位响应。 */
    private static Handler<RoutingContext> unimplemented() {        return ctx -> ctx.response().setStatusCode(501)
                .putHeader("Content-Type", "application/json; charset=utf-8")
                .end(io.vertx.core.buffer.Buffer.buffer(NOT_IMPLEMENTED, "UTF-8"));
    }

    /**
     * 没接 {@link ErrorHandler} 时的极简 failure 出口（只给单测用）。
     *
     * <p>输出 {@code {"code":<status>,"message":"<理由>"}} 而不是 Laravel 的
     * {@code {message, exception, trace}} —— 生产路径必须挂 ErrorHandler，
     * 这里的形状只为让路由层单测能断言状态码与命中的路径。</p>
     */
    static Handler<RoutingContext> plainJsonFailure() {
        return ctx -> {
            int status = 500;
            String message = "Internal Server Error";
            Throwable failure = ctx.failure();

            if (failure instanceof WebException web) {
                status = web.status();
                message = web.getMessage() == null ? "" : web.getMessage();
                web.headers().forEach((k, v) -> ctx.response().putHeader(k, v));
            } else if (failure == null && ctx.statusCode() >= 400) {
                // ctx.fail(statusCode)：Vert.x 只写状态码，failure 为 null
                status = ctx.statusCode();
                message = "";
            } else if (failure != null && failure.getMessage() != null) {
                message = failure.getMessage();
            }

            status = status == 404 ? 404 : status;

            String body = "{\"code\":" + status + ",\"message\":"
                    + com.neoskin.support.PhpJson.encode(
                            status == 404 ? "Not Found" : message)
                    + "}";
            ctx.response().setStatusCode(status)
                    .putHeader("Content-Type", "application/json; charset=utf-8")
                    .end(io.vertx.core.buffer.Buffer.buffer(body, "UTF-8"));
        };
    }

    /**
     * 未命中任何路由时的分流（Laravel {@code AbstractRouteCollection::handleMatchedRoute}）。
     *
     * <pre>
     * $others = array_diff(Router::$verbs, [$request->getMethod()]);  // 逐个试
     * if (count($others) > 0) {
     *     if ($request->isMethod('OPTIONS')) → 200 + Allow: implode(',', $others)
     *     else                              → 405 + Allow: implode(', ', $others)
     * }
     * throw new NotFoundHttpException("The route {$request->path()} could not be found.");
     * </pre>
     *
     * <p>{@code Allow} 在 405 里是 {@code "GET, HEAD"}（带空格，来自
     * {@code MethodNotAllowedHttpException::getHeaders()}），在 OPTIONS 的 200 里是
     * {@code "GET,HEAD"}（不带空格，来自 {@code implode(',', $methods)}）——
     * 两者确实不一样，实测如此。</p>
     */
    static Handler<RoutingContext> fallback(RouteMatcher matcher) {
        return ctx -> {
            String raw = ctx.request().path();
            String path = laravelPathOf(raw);

            // 守卫已经处理过的双斜杠不会走到这里（它会 ctx.fail）。
            List<String> allowed = matcher.allowedMethods(path);

            if (allowed.isEmpty()) {
                ctx.fail(WebException.notFound(path));
                return;
            }

            String method = ctx.request().method().name();

            if ("OPTIONS".equals(method)) {
                ctx.response().setStatusCode(200)
                        .putHeader("Allow", String.join(",", allowed))
                        .putHeader("Cache-Control", com.neoskin.web.error.ErrorHandler.DEFAULT_CACHE_CONTROL)
                        .putHeader("Content-Type", "text/html; charset=utf-8")
                        .end();
                return;
            }

            ctx.fail(WebException.methodNotAllowed(method, path, allowed));
        };
    }
}
