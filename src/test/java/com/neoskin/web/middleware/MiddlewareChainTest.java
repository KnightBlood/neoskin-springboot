package com.neoskin.web.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 中间件装配的纯逻辑测试：expand / canonical 与 bootstrap/app.php 的
 * 栈序一比一（全局栈 → 组展开保持语料书写序）。
 */
class MiddlewareChainTest {

    /** 全局栈的顺序就是 $middleware->use() 的书写顺序。 */
    @Test
    void globalStackOrderMatchesBootstrap() {
        List<String> expanded = MiddlewareChain.expand(List.of());
        assertEquals(List.of(
                "Illuminate\\Foundation\\Http\\Middleware\\PreventRequestsDuringMaintenance",
                "Illuminate\\Foundation\\Http\\Middleware\\ValidatePostSize",
                "Illuminate\\Foundation\\Http\\Middleware\\TrimStrings",
                "App\\Http\\Middleware\\ConvertEmptyStringsToNull",
                "App\\Http\\Middleware\\DetectLanguagePrefer"), expanded);
    }

    /** web 组展开保持组内顺序（EncryptCookies 领头）。 */
    @Test
    void webGroupExpandsInOrder() {
        List<String> expanded = MiddlewareChain.expand(List.of("web"));
        assertEquals(5 + 8, expanded.size());
        assertEquals("Illuminate\\Cookie\\Middleware\\EncryptCookies", expanded.get(5));
        assertEquals("Illuminate\\View\\Middleware\\ShareErrorsFromSession", expanded.get(8));
        assertEquals("Illuminate\\Routing\\Middleware\\SubstituteBindings", expanded.getLast());
    }

    /** authorize 组：auth:web 是嵌组（别名展开），其余三个按序。 */
    @Test
    void authorizeGroupNestsAuthWeb() {
        List<String> expanded = MiddlewareChain.expand(List.of("authorize"));
        assertEquals(5 + 4, expanded.size());
        assertEquals("App\\Http\\Middleware\\RejectBannedUser", expanded.get(6));
        assertEquals("App\\Http\\Middleware\\FireUserAuthenticated", expanded.getLast());
    }

    /** api 组只带 SubstituteBindings；多组混合按书写顺序扁平化。 */
    @Test
    void mixedGroupsStayWrittenOrder() {
        List<String> expanded = MiddlewareChain.expand(List.of("web", "api"));
        assertEquals(5 + 8 + 1, expanded.size());
        assertEquals("Illuminate\\Routing\\Middleware\\SubstituteBindings", expanded.getLast());
    }

    /** 别名改写：具名参数保留。 */
    @Test
    void aliasesKeepParameters() {
        assertEquals("App\\Http\\Middleware\\CheckRole:admin",
                MiddlewareChain.canonical("role:admin"));
        assertEquals("App\\Http\\Middleware\\RedirectIfAuthenticated",
                MiddlewareChain.canonical("guest"));
        assertEquals("Laravel\\Passport\\Http\\Middleware\\CheckTokenForAnyScope:User.Read",
                MiddlewareChain.canonical("scope:User.Read"));
        // cache.headers 也是 Laravel 内置别名（SetCacheHeaders），具名参数保留。
        assertEquals("Illuminate\\Http\\Middleware\\SetCacheHeaders:etag;public;max_age=31536000",
                MiddlewareChain.canonical("cache.headers:etag;public;max_age=31536000"));
        // throttle 是 Laravel 的默认别名（ThrottleRequests），参数原样保留。
        assertEquals("Illuminate\\Routing\\Middleware\\ThrottleRequests:60,1",
                MiddlewareChain.canonical("throttle:60,1"));
    }

    /** routes.json 里 web+authorize 组合的一个真实切片（home/setups 走的路）。 */
    @Test
    void typicalRouteMiddlewareFlattens() {
        List<String> expanded = MiddlewareChain.expand(List.of("web", "setup", "guest"));
        assertTrue(expanded.contains("App\\Http\\Middleware\\CheckInstallation"));
        assertTrue(expanded.contains("App\\Http\\Middleware\\RedirectIfAuthenticated"));
        // 书写顺序探测：setup 在 guest 前。
        assertTrue(expanded.indexOf("App\\Http\\Middleware\\CheckInstallation")
                < expanded.indexOf("App\\Http\\Middleware\\RedirectIfAuthenticated"));
    }
}
