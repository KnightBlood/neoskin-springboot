package com.neoskin.web.middleware;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.neoskin.service.session.FileSessionHandler;
import com.neoskin.service.session.SessionCookieConfig;
import com.neoskin.service.session.SessionHandler;
import com.neoskin.service.session.SessionStore;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.ext.web.RoutingContext;

/**
 * Laravel {@code Illuminate\Session\Middleware\StartSession} 的 Vert.x 接线。
 *
 * <p>请求侧：从（已解密的）cookie 拿 {@code BS_SESSION} id → {@code new
 * SessionStore} → {@code start()} → 存进 {@code ctx}（键
 * {@code "session"}，控制器/后置中间件取用）。</p>
 *
 * <p>响应侧：headers 结束前 {@code save()}，并回写会话 cookie ——
 * {@code config}: path=/、httpOnly、secure=false、sameSite=lax、
 * Max-Age={@code lifetime*60}（120 分钟）、 expireOnClose=false；值按
 * {@link EncryptCookies} 的响应端约定加密（会话 cookie 不在 except 清单里，
 * 和 Laravel 一致）。</p>
 *
 * <p>未接线（偏差）：gc 抽彩 lottery={2,100}、并发文件锁
 * （{@code handleRequestWhileBlocking}）、urlIntended 之外的误差——见
 * PORT-NOTES。</p>
 */
public final class StartSession implements Handler<RoutingContext> {

    /** {@code session.cookie}。 */
    public static final String COOKIE_NAME = "BS_SESSION";

    /** {@code session.lifetime}（分钟）。 */
    public static final int LIFETIME_MINUTES = 120;

    /** {@code session.lottery} 的分子（源 {@code [2, 100]}）。 */
    private static final int GC_LOTTERY = 2;

    /** {@code ctx} 里会话的存取键。 */
    public static final String CTX_KEY = "session";

    /**
     * {@code ctx} 里当前路由名的键（{@code _previous.route} 用）。
     *
     * <p>由 {@code VertxRouteRegistry} 在路由级中间件之前写入；缺失时
     * {@code storeCurrentUrl} 只写 {@code _previous.url}。</p>
     */
    public static final String ROUTE_NAME_KEY = "neoskin.route.name";

    /** 会话存储 handler（file / 缓存型，按 {@code SESSION_DRIVER} 选择）。 */
    private final SessionHandler sessionHandler;

    /** 响应端加密器（null = 明确不加密，仅测试用）。 */
    private final EncryptCookies encryptCookies;

    /** 会话 cookie 配置（源 config/session.php）。 */
    private final SessionCookieConfig config;

    public StartSession(Path directory, EncryptCookies encryptCookies) {
        this(new FileSessionHandler(directory, LIFETIME_MINUTES), encryptCookies,
                SessionCookieConfig.DEFAULT);
    }

    public StartSession(SessionHandler sessionHandler, EncryptCookies encryptCookies) {
        this(sessionHandler, encryptCookies, SessionCookieConfig.DEFAULT);
    }

    public StartSession(SessionHandler sessionHandler, EncryptCookies encryptCookies,
            SessionCookieConfig config) {
        this.sessionHandler = sessionHandler;
        this.encryptCookies = encryptCookies;
        this.config = config == null ? SessionCookieConfig.DEFAULT : config;
    }

    @Override
    public void handle(RoutingContext ctx) {
        // EncryptCookies 在 web 组里先行，解密结果挂在 ctx；没有就退到裸 cookie
        // （api 组路由不经 web 组，cookie 本来就没加密）。
        Object decrypted = ctx.get(EncryptCookies.CTX_KEY);
        String id = null;

        if (decrypted instanceof com.neoskin.support.PhpJson.Array cookies) {
            Object value = cookies.get(config.name());

            if (value instanceof String string) {
                id = string;
            }
        }

        SessionStore store = SessionStore.begin(config.name(), sessionHandler, id);
        store.start();
        ctx.put(CTX_KEY, store);

        // 数据在响应头封口前落盘（Laravel 的 saveSession 全局也走这语义）。
        // 用 RoutingContext 的可叠加钩子——HttpServerResponse.headersEndHandler
        // 是单值的，后注册的会顶掉前面的（CSRF 的 XSRF-TOKEN 就在后面）。
        ctx.addHeadersEndHandler(ignored -> {
            storeCurrentUrl(ctx, store);
            store.save();
            queueSessionCookie(ctx, store);
            // 源 StartSession 的 lottery=[2,100]：随机清理过期会话
            if (java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < GC_LOTTERY) {
                sessionHandler.gc(config.maxAgeSeconds());
            }
        });

        ctx.next();
    }

    /**
     * {@code StartSession::storeCurrentUrl()}：GET + 非 ajax + 非 prefetch 时
     * 记下 {@code _previous.url}（{@code url()->previous()} 的回落源）。
     *
     * <p>源（Laravel 13）：</p>
     * <pre>
     * if ($request->isMethod('GET') && $request->route() instanceof Route
     *     && ! $request->ajax() && ! $request->prefetch() && ! $request->isPrecognitive()) {
     *     $session->setPreviousUrl($request->fullUrl());
     *     if (method_exists($session, 'setPreviousRoute')) {
     *         $session->setPreviousRoute($request->route()->getName());
     *     }
     * }
     * </pre>
     *
     * <p>{@code route() instanceof Route} 恒为真（只有 404 才没有路由，那时也不到
     * 这里）；{@code prefetch} 看 {@code X-Purpose: prefetch} /
     * {@code Purpose: prefetch}；{@code isPrecognitive} 看
     * {@code X-Precognition}。</p>
     */
    static void storeCurrentUrl(RoutingContext ctx, SessionStore store) {
        if (!"GET".equals(ctx.request().method().name())) {
            return;
        }
        if (isAjax(ctx) || isPrefetch(ctx) || isPrecognitive(ctx)) {
            return;
        }
        store.setPreviousUrl(fullUrl(ctx));
        String routeName = ctx.get(ROUTE_NAME_KEY);
        if (routeName != null) {
            store.put("_previous.route", routeName);
        }
    }

    /** {@code $request->ajax()}：{@code X-Requested-With: XMLHttpRequest}。 */
    private static boolean isAjax(RoutingContext ctx) {
        return "XMLHttpRequest".equalsIgnoreCase(ctx.request().getHeader("X-Requested-With"));
    }

    /** {@code $request->prefetch()}：{@code X-Purpose/Purpose: prefetch}。 */
    private static boolean isPrefetch(RoutingContext ctx) {
        String purpose = ctx.request().getHeader("X-Purpose");
        if (purpose == null) {
            purpose = ctx.request().getHeader("Purpose");
        }
        return purpose != null && purpose.toLowerCase().contains("prefetch");
    }

    /** {@code $request->isPrecognitive()}：带 {@code X-Precognition} 头。 */
    private static boolean isPrecognitive(RoutingContext ctx) {
        return ctx.request().getHeader("X-Precognition") != null;
    }

    /** {@code $request->fullUrl()}：scheme://host + 原始 uri（含 query）。 */
    private static String fullUrl(RoutingContext ctx) {
        String uri = ctx.request().uri();
        String host = ctx.request().getHeader("Host");
        String scheme = ctx.request().scheme() == null ? "http" : ctx.request().scheme();
        return scheme + "://" + (host == null || host.isEmpty() ? "localhost" : host)
                + (uri == null || uri.isEmpty() ? "/" : uri);
    }

    /** {@code addCookieToResponse} + EncryptCookies 响应端。 */
    private void queueSessionCookie(RoutingContext ctx, SessionStore store) {
        String value = store.getId();

        if (encryptCookies != null && !encryptCookies.isDisabled(config.name())) {
            value = encryptCookies.encrypt(config.name(), value);
        }

        Cookie cookie = Cookie.cookie(config.name(), value);

        cookie.setPath(config.path())
                .setHttpOnly(true)
                .setSecure(config.secure())
                .setSameSite(sameSite(config.sameSite()))
                .setMaxAge(config.maxAgeSeconds());

        ctx.response().addCookie(cookie);
    }

    /** {@code same_site} 字符串 → Vert.x 枚举（未知值按 lax，与 Laravel 默认一致）。 */
    private static CookieSameSite sameSite(String value) {
        return switch (value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)) {
            case "strict" -> CookieSameSite.STRICT;
            case "none" -> CookieSameSite.NONE;
            default -> CookieSameSite.LAX;
        };
    }

    /** 端到端测试时排空响应头（避免 headersEndHandler 因无输出而跳过）。 */
    static void drain(RoutingContext ctx) {
        ctx.response().end().toCompletionStage().toCompletableFuture()
                .orTimeout(5, TimeUnit.SECONDS);
    }
}
