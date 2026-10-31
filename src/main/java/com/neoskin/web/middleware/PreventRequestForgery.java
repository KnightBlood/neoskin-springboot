package com.neoskin.web.middleware;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import com.neoskin.support.Str;
import com.neoskin.web.cookie.CookieValuePrefix;

/**
 * {@code Illuminate\Foundation\Http\Middleware\PreventRequestForgery} 的移植
 * （Laravel 13 起取代 {@code VerifyCsrfToken}，语义未变）。
 *
 * <p>源 {@code handle()} 的五条放行判据，按同样顺序：</p>
 * <ol>
 *   <li>{@code isReading}：{@code HEAD / GET / OPTIONS} 一律放行；</li>
 *   <li>{@code runningUnitTests}：PHPUnit 环境下放行（Java 侧没有这个概念，恒 false）；</li>
 *   <li>{@code inExceptArray}：URI 命中 except 清单（本应用未注册，为空）；</li>
 *   <li>{@code hasValidOrigin}：{@code Sec-Fetch-Site: same-origin} 直接放行
 *       （{@code same-site} 只在 {@code allowSameSite(true)} 时放行，本应用没开）；</li>
 *   <li>{@code tokensMatch}：请求 token 与会话 {@code _token} 用
 *       {@code hash_equals}（定长比较）相等。</li>
 * </ol>
 *
 * <p>取 token 的顺序：{@code input('_token')} → {@code X-CSRF-TOKEN} 头 →
 * {@code X-XSRF-TOKEN} 头（后者要先解密 + 去 {@link CookieValuePrefix}）。
 * 全部取不到就是不匹配 —— 注意源里 {@code ?:} 会把空串当成「没取到」。</p>
 *
 * <p>放行后还要给响应补 {@code XSRF-TOKEN} cookie（{@code addHttpCookie=true}、
 * {@code originOnly=false}），值就是会话 token（<b>不加密</b>，前端 js 要读），
 * 有效期 {@code 60 * lifetime} 秒。</p>
 */
public final class PreventRequestForgery {

    /** {@code XSRF-TOKEN} cookie 名。 */
    public static final String XSRF_COOKIE = "XSRF-TOKEN";

    /** {@code getExcludedPaths()} = instance except ∪ 静态 neverVerify。 */
    private static final List<String> NEVER_VERIFY = new ArrayList<>();

    /** {@code $allowSameSite}。 */
    private static boolean allowSameSite;

    /** {@code $originOnly}。 */
    private static boolean originOnly;

    private PreventRequestForgery() {
    }

    /** {@code except($uris)}：注册永不校验的 URI（支持 {@code *}）。 */
    public static void except(List<String> uris) {
        for (String uri : uris) {
            if (!NEVER_VERIFY.contains(uri)) {
                NEVER_VERIFY.add(uri);
            }
        }
    }

    /** {@code allowSameSite()}/{@code useOriginOnly()}。 */
    public static void allowSameSite(boolean allow) {
        allowSameSite = allow;
    }

    public static void useOriginOnly(boolean only) {
        originOnly = only;
    }

    /** {@code flushState()}（测试用）。 */
    public static void flushState() {
        NEVER_VERIFY.clear();
        allowSameSite = false;
        originOnly = false;
    }

    /** 一次校验需要的请求快照（把 Symfony Request 摊平）。 */
    public record Request(
            String method,
            String path,
            String secFetchSite,
            String inputToken,
            String csrfHeader,
            String xsrfHeader) {
    }

    /** {@code tokensMatch} 用的会话 token；null 表示会话里没有。 */
    public record Verdict(boolean allowed, String reason) {
    }

    /**
     * {@code handle()} 的判定（不含响应 cookie 环节）。
     *
     * @param sessionToken 会话 {@code _token}（可为 null）
     * @param decryptXcsrf {@code X-XSRF-TOKEN} 头的解密器（返回明文 token 或 null）
     */
    public static Verdict verify(Request request, String sessionToken,
            java.util.function.Function<String, String> decryptXcsrf) {
        if (isReading(request.method())) {
            return new Verdict(true, "reading");
        }

        // runningUnitTests() 在 Java 侧恒 false。

        if (inExceptArray(request.path())) {
            return new Verdict(true, "except");
        }

        if ("same-origin".equals(request.secFetchSite())) {
            return new Verdict(true, "origin");
        }

        if ("same-site".equals(request.secFetchSite()) && allowSameSite) {
            return new Verdict(true, "origin-same-site");
        }

        if (originOnly) {
            // OriginMismatchException（本应用不启用 originOnly，保留语义）。
            return new Verdict(false, "origin-mismatch");
        }

        String token = tokenFromRequest(request, decryptXcsrf);

        if (sessionToken != null && token != null && hashEquals(sessionToken, token)) {
            return new Verdict(true, "token");
        }

        return new Verdict(false, "mismatch");
    }

    /** {@code isReading()}：HEAD / GET / OPTIONS。 */
    public static boolean isReading(String method) {
        return "HEAD".equalsIgnoreCase(method)
                || "GET".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    /** {@code inExceptArray()}：{@code trim($except, '/')} 后做 fullUrl/uri 的 is() 匹配。 */
    static boolean inExceptArray(String path) {
        for (String except : NEVER_VERIFY) {
            String pattern = "/".equals(except) ? except : trimSlashes(except);

            if (Str.is(pattern, path)) {
                return true;
            }
        }

        return false;
    }

    private static String trimSlashes(String value) {
        int start = 0;
        int end = value.length();

        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }

        return value.substring(start, end);
    }

    /**
     * {@code getTokenFromRequest()}：{@code input('_token') ?: header('X-CSRF-TOKEN')}
     * ，都没有才看 {@code X-XSRF-TOKEN}（解密 → 去前缀；解密失败得空串）。
     */
    public static String tokenFromRequest(Request request,
            java.util.function.Function<String, String> decryptXcsrf) {
        String token = isTruthy(request.inputToken()) ? request.inputToken() : request.csrfHeader();

        if (!isTruthy(token) && isTruthy(request.xsrfHeader()) && decryptXcsrf != null) {
            String decrypted = decryptXcsrf.apply(request.xsrfHeader());

            token = decrypted == null ? "" : CookieValuePrefix.remove(decrypted);
        }

        return token;
    }

    /** PHP 的 {@code ?:} 真值判定（只关心字符串场景：null 与 "" 为假）。 */
    private static boolean isTruthy(String value) {
        return value != null && !value.isEmpty() && !"0".equals(value);
    }

    /** {@code hash_equals()}：定长比较，避免时序侧信道。 */
    static boolean hashEquals(String known, String user) {
        return MessageDigest.isEqual(known.getBytes(StandardCharsets.UTF_8),
                user.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code newCookie()}：XSRF-TOKEN 的值与会话 token 相同，lifetime*60 秒。 */
    public record XsrfCookie(String name, String value, long maxAgeSeconds, String path,
            String domain, boolean secure, boolean httpOnly, String sameSite) {
    }

    /** {@code shouldAddXsrfTokenCookie()}：originOnly 时不加，否则看 addHttpCookie。 */
    public static XsrfCookie xsrfCookie(String sessionToken, boolean addHttpCookie,
            int lifetimeMinutes, String path, String domain, boolean secure, String sameSite) {
        if (originOnly || !addHttpCookie) {
            return null;
        }

        return new XsrfCookie(XSRF_COOKIE, sessionToken,
                (long) lifetimeMinutes * 60, path, domain, secure, false, sameSite);
    }
}
