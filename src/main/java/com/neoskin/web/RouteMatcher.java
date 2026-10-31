package com.neoskin.web;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 路由表的「第二条通道」：只用来判断<b>路径本身</b>命中与否，与 HTTP 方法无关。
 *
 * <p>为什么需要它：{@link VertxRouteRegistry} 把 173 条路由交给 Vert.x 匹配，
 * 而 Vert.x 只会告诉你「没命中」。但 Laravel 的 404 与 405 是两种不同的结果：</p>
 * <ul>
 *   <li>路径不命中任何路由 → {@code NotFoundHttpException} → 404；</li>
 *   <li>路径命中、方法不对 → {@code MethodNotAllowedHttpException} → 405 +
 *       {@code Allow} 头；</li>
 *   <li>路径命中、方法是 {@code OPTIONS} → <b>200 空体</b> + {@code Allow}。</li>
 * </ul>
 *
 * <p>源码这两条分支在 {@code AbstractRouteCollection::handleMatchedRoute} 里：先按
 * 方法找，找不到就 {@code checkForAlternateVerbs()}（遍历
 * {@code Router::$verbs}），有别的动词能命中就抛 405（或给 OPTIONS 回 200）。</p>
 */
final class RouteMatcher {

    /**
     * Laravel {@code Router::$verbs} 的顺序 —— {@code Allow} 头就按这个顺序排，
     * <b>不是</b> routes.json 里的书写顺序。
     *
     * <p>{@code checkForAlternateVerbs()} 是
     * {@code array_diff(Router::$verbs, [$request->getMethod()])} 再逐个试，
     * 所以输出恒为 {@code GET, HEAD, POST} 这种固定次序。实测：
     * {@code PUT /} → {@code Allow: GET, HEAD}；{@code DELETE /auth/login} →
     * {@code Allow: GET, HEAD, POST}。</p>
     */
    static final List<String> VERBS =
            List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "QUERY");

    /** 一条已编译的路径模式。 */
    private record Compiled(String uri, Pattern pattern, List<String> methods) {
    }

    private final List<Compiled> compiled = new ArrayList<>();

    RouteMatcher(List<RouteDefinition> routes) {
        for (RouteDefinition route : routes) {
            compiled.add(new Compiled(route.uri(), toPattern(route.uri()),
                    route.methods().stream().map(String::toUpperCase).toList()));
        }
    }

    /**
     * 路径（Laravel 语义的 {@code Request::path()}，无前导斜杠、根为 {@code "/"}）
     * 命中的<b>全部</b>方法，按 {@link #VERBS} 排序。
     *
     * <p>必须是<b>并集</b>而不是「第一条命中的路由」：源里同一路径常按方法拆成多条
     * 路由（{@code /auth/login} 是 GET|HEAD 一条 + POST 一条），
     * {@code checkForAlternateVerbs()} 是<b>逐动词</b>去试，所以 405 的
     * {@code Allow} 是 {@code GET, HEAD, POST}，只取首条会漏掉 POST。</p>
     *
     * @return 空表表示该路径不存在（→ 404）
     */
    List<String> allowedMethods(String path) {
        String target = path == null || path.isEmpty() ? "/" : path;
        java.util.Set<String> found = new java.util.HashSet<>();

        for (Compiled route : compiled) {
            if (route.pattern().matcher(target).matches()) {
                found.addAll(route.methods());
            }
        }

        List<String> allowed = new ArrayList<>();
        for (String verb : VERBS) {
            if (found.contains(verb)) {
                allowed.add(verb);
            }
        }
        return allowed;
    }

    /**
     * Laravel uri → 正则。
     *
     * <p>{@code {name}} 与 {@code {name?}} 都翻成 {@code [^/]+}（Laravel 的参数默认
     * 匹配「除斜杠外任意串」）；其余字符按字面转义。根路由 {@code "/"} 是特例：
     * {@code Request::path()} 对根返回 {@code "/"}。</p>
     */
    static Pattern toPattern(String uri) {
        if (uri == null || uri.isEmpty() || "/".equals(uri)) {
            return Pattern.compile("^/$");
        }
        String raw = uri.startsWith("/") ? uri.substring(1) : uri;
        StringBuilder regex = new StringBuilder("^");
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '{') {
                int close = raw.indexOf('}', i);
                if (close < 0) {
                    regex.append(Pattern.quote(raw.substring(i)));
                    break;
                }
                regex.append("[^/]+");
                i = close + 1;
                continue;
            }
            regex.append(Pattern.quote(String.valueOf(c)));
            i++;
        }
        return Pattern.compile(regex.append('$').toString());
    }
}
