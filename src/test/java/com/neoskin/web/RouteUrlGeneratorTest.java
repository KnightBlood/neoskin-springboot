package com.neoskin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link RouteUrlGenerator} 的回归：具名路由反查、路径参数替换、剩余参数转 query。
 *
 * <p>源 {@code UrlGenerator::route($name, $parameters, $absolute)} 的行为：
 * 路由 URI 里的 {@code {param}} 被消费，没被消费的键变成 query string；
 * {@code $absolute = false} 时只返回路径。</p>
 */
class RouteUrlGeneratorTest {

    private static final RouteUrlGenerator GEN = RouteUrlGenerator.forHost("localhost:8080");

    /** 无参数具名路由。 */
    @Test
    void resolvesNamedRoute() {
        assertEquals("/admin/users", GEN.route("admin.users.view", Map.of(), false));
        assertEquals("http://localhost:8080/admin/users", GEN.route("admin.users.view"));
        assertEquals("/", GEN.route("home", Map.of(), false));
    }

    /** 路径参数 + 剩余参数转 query。 */
    @Test
    void substitutesPathParamsAndQuery() {
        // avatar.texture 的 uri 是 avatar/{tid}，size/png 不在路径里 → query
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tid", "abc");
        params.put("size", 36);
        params.put("png", true);

        assertEquals("/avatar/abc?size=36&png=true",
                GEN.route("avatar.texture", params, false));
    }

    /** 只消费路径参数时不留 query。 */
    @Test
    void consumesOnlyPathParams() {
        assertEquals("/skinlib/show/42",
                GEN.route("skinlib.show", Map.of("texture", 42), false));
        assertEquals("/user/inbox/7",
                GEN.route("user.inbox.message", Map.of("id", 7), false));
    }

    /** 未知路由回退（不抛异常，避免一个缺失路由阻塞整页）。 */
    @Test
    void unknownRouteFallsBack() {
        assertTrue(GEN.route("no.such.route", Map.of(), false).contains("no.such.route"));
    }

    /** 具名路由数量与源一致（142 条具名，去重后 ≥ 140）。 */
    @Test
    void namedCountMatchesSource() {
        assertTrue(GEN.names().size() >= 140, "具名路由应 ≥ 140 条，实际 " + GEN.names().size());
        assertEquals("/", GEN.uri("home"));
        assertEquals("admin/users", GEN.uri("admin.users.view"));
    }
}
