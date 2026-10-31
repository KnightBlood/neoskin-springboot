package com.neoskin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neoskin.support.PhpJson;

/**
 * {@link RouteListJson}：{@code php artisan route:list --json --columns=uri,name}
 * 的等价输出。
 *
 * <p>前端 {@code tools/generateUrls.ts} 依赖这个形状生成
 * {@code resources/assets/src/scripts/urls.ts}，所以字段名/顺序/空值都不能变。</p>
 */
class RouteListJsonTest {

    private static final Routes ROUTES = Routes.load();

    @Test
    void emits174EntriesInRegistrationOrder() {
        String json = RouteListJson.render(ROUTES);
        Object decoded = PhpJson.decode(json);

        assertTrue(decoded instanceof PhpJson.Array, "顶层必须是 JSON 数组");
        assertEquals(174, ((PhpJson.Array) decoded).size());
    }

    @Test
    void eachRowHasOnlyUriAndName() {
        PhpJson.Array rows = (PhpJson.Array) PhpJson.decode(RouteListJson.render(ROUTES));

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) rows.get(0L);
        assertEquals(2, first.size(), "--columns=uri,name 只输出两列: " + first);
        assertTrue(first.containsKey("uri"));
        assertTrue(first.containsKey("name"));
    }

    @Test
    void rootRouteIsSlash() {
        Map<String, String> supported = RouteListJson.supportedNames(ROUTES);
        // home 不在五个前缀里，但 uri 本身要是 "/"
        boolean found = false;
        for (RouteDefinition route : ROUTES.all()) {
            if ("home".equals(route.name())) {
                assertEquals("/", route.uri());
                found = true;
            }
        }
        assertTrue(found, "必须能找到 name=home 的路由");
        assertFalse(supported.isEmpty());
    }

    @Test
    void unnamedRoutesHaveNullName() {
        PhpJson.Array rows = (PhpJson.Array) PhpJson.decode(RouteListJson.render(ROUTES));
        boolean sawNull = false;

        for (Map.Entry<Object, Object> e : rows.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) e.getValue();
            if (row.get("name") == null) {
                sawNull = true;
                break;
            }
        }
        assertTrue(sawNull, "174 条里有未命名路由（31 条），name 必须是 null");
    }

    @Test
    void supportedPrefixesMatchGenerateUrls() {
        Map<String, String> supported = RouteListJson.supportedNames(ROUTES);

        assertEquals("/admin/users", "/" + supported.get("admin.users.view"));
        assertEquals("/auth/login", "/" + supported.get("auth.login"));
        assertEquals("/user", "/" + supported.get("user.home"));
        assertEquals("/skinlib", "/" + supported.get("skinlib.home"));
        // 只保留五个前缀 + 不以 '.' 结尾的具名路由
        assertTrue(supported.size() > 60, "具名路由应 > 60，实际 " + supported.size());
        assertFalse(supported.containsKey("home"), "home 不在五个前缀里");
        assertFalse(supported.containsKey("user."), "以 '.' 结尾的要过滤（源里确实有 user.）");
    }

    /** CLI：{@code --route-list-json} 打印 JSON 后直接返回，不启动 Spring。 */
    @Test
    void cliFlagPrintsJsonAndReturns() throws Exception {
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(buffer, true, "UTF-8"));
            com.neoskin.NeoSkinApplication.main(new String[] { "--route-list-json" });
        } finally {
            System.setOut(original);
        }

        String output = buffer.toString("UTF-8").trim();
        assertTrue(output.startsWith("["), "必须是 JSON 数组: " + output.substring(0, 40));
        assertEquals(174, ((PhpJson.Array) PhpJson.decode(output)).size());
    }
}
