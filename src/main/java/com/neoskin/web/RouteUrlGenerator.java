package com.neoskin.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Laravel {@code UrlGenerator::route()} 的移植：按路由名生成 URL。
 *
 * <p>源项目里 {@code route('admin.users.view')} / {@code route('avatar.texture', [...], false)}
 * 都走这里。数据源是 {@link Routes}（{@code codegraph/routes.json}），避免与
 * 路由注册各解析一份。</p>
 *
 * <p>算法（对照 {@code Illuminate\Routing\UrlGenerator::toRoute} +
 * {@code routeUrl()}）：</p>
 * <ol>
 *   <li>按 name 查路由（重名取最后一条，Laravel 也这么干）；</li>
 *   <li>把 {@code {param}} / {@code {param?}} 占位符用 params 里的值替换；</li>
 *   <li>params 里没被占位符消费掉的键 → 追加为 query string
 *       （{@code http_build_query} 语义，按插入顺序）；</li>
 *   <li>{@code absolute = true} → 拼上 {@code scheme://host} 根；
 *       {@code false} → 只返回路径部分。</li>
 * </ol>
 */
public final class RouteUrlGenerator {

    private final String root;

    private final String scheme;

    private final Map<String, RouteEntry> byName = new ConcurrentHashMap<>();

    public RouteUrlGenerator(String root, String scheme) {
        this.root = root == null ? "" : root;
        this.scheme = (scheme == null ? "http" : scheme) + "://";
        load(Routes.load());
    }

    /** 复用已有路由表的构造（调用方已加载过 {@link Routes} 时省一次解析）。 */
    public RouteUrlGenerator(String root, String scheme, Routes routes) {
        this.root = root == null ? "" : root;
        this.scheme = (scheme == null ? "http" : scheme) + "://";
        load(routes);
    }

    /** {@code http://localhost} 为根的便捷构造。 */
    public static RouteUrlGenerator forHost(String host) {
        String h = host == null || host.isEmpty() ? "localhost" : host;
        return new RouteUrlGenerator("http://" + h, "http");
    }

    /** 从 {@link Routes}（唯一权威数据源）建 name → uri 索引。 */
    private void load(Routes routes) {
        for (RouteDefinition route : routes.all()) {
            if (route.name() == null || route.name().isEmpty()) {
                continue;
            }
            // 重名：后一条覆盖前一条（Laravel 的 RouteCollection::add 也是这么干）
            byName.put(route.name(), new RouteEntry(route.name(), route.uri()));
        }
    }

    /** {@code route($name)}：绝对 URL。 */
    public String route(String name) {
        return route(name, Map.of(), true);
    }

    /** {@code route($name, $params)}：绝对 URL。 */
    public String route(String name, Map<String, Object> params) {
        return route(name, params, true);
    }

    /** {@code route($name, $params, $absolute)}。 */
    public String route(String name, Map<String, Object> params, boolean absolute) {
        RouteEntry entry = byName.get(name);
        if (entry == null) {
            // 源里 route() 对不存在的路由会抛 RouteNotFoundException；
            // 视图层容错：回退成 name 本身，以免一个缺失路由拖垮整页渲染。
            return (absolute ? root : "") + "/" + (name == null ? "" : name);
        }

        Map<String, Object> remaining = new LinkedHashMap<>(
                params == null ? Map.of() : params);
        String path = entry.uri();

        // 替换 {param} / {param?}
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '{') {
                int end = path.indexOf('}', i);
                if (end > i) {
                    String token = path.substring(i + 1, end);
                    String key = token.endsWith("?")
                            ? token.substring(0, token.length() - 1) : token;
                    Object value = remaining.remove(key);
                    if (value == null) {
                        // 可选参数缺失：整段跳过
                        i = end + 1;
                        continue;
                    }
                    out.append(urlEncode(String.valueOf(value)));
                    i = end + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }

        String built = "/" + out.toString().replaceAll("/+", "/").replaceAll("^/", "");
        while (built.length() > 1 && built.endsWith("/")) {
            built = built.substring(0, built.length() - 1);
        }

        // 剩余参数 → query string
        if (!remaining.isEmpty()) {
            StringBuilder q = new StringBuilder("?");
            boolean first = true;
            for (Map.Entry<String, Object> e : remaining.entrySet()) {
                if (!first) {
                    q.append('&');
                }
                first = false;
                q.append(URLEncoder.encode(String.valueOf(e.getKey()), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(String.valueOf(e.getValue()),
                                StandardCharsets.UTF_8));
            }
            built = built + q;
        }

        if (absolute) {
            return root + (built.startsWith("/") ? built : "/" + built);
        }
        return built;
    }

    /** 构造时用的 scheme 前缀（`http://`）；诊断用。 */
    public String scheme() {
        return scheme;
    }

    /** 暴露给测试：已注册的路由名集合。 */
    public Set<String> names() {
        return Set.copyOf(byName.keySet());
    }

    /** 暴露给测试：某路由的 URI 模板。 */
    public String uri(String name) {
        RouteEntry e = byName.get(name);
        return e == null ? null : e.uri();
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record RouteEntry(String name, String uri) {
    }
}
