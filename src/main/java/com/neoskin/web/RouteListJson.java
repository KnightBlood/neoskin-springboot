package com.neoskin.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neoskin.support.PhpJson;

/**
 * {@code php artisan route:list --json --columns=uri,name} 的等价输出。
 *
 * <h2>为什么需要它</h2>
 * <p>前端仓库的 {@code tools/generateUrls.ts}（{@code yarn build:urls}）会执行上面
 * 那条 artisan 命令，把具名路由编译成带 TS 类型的
 * {@code resources/assets/src/scripts/urls.ts}。前端是独立可跑的（见 PORT-NOTES
 * §9），所以后端换语言之后必须仍能提供**同样形状**的输入，否则前端改路由后
 * 无法重新生成。</p>
 *
 * <h2>输出形状</h2>
 * <pre>
 * [
 *   {"uri": "admin/users", "name": "admin.users.view"},
 *   {"uri": "/",           "name": "home"},
 *   {"uri": ".well-known/change-password", "name": null}
 * ]
 * </pre>
 * <ul>
 *   <li>顺序 = {@code routes.json} 的注册顺序（Laravel 的 {@code route:list} 也是
 *       注册顺序）；</li>
 *   <li>{@code uri} 不带前导斜杠，根路由是 {@code "/"}；</li>
 *   <li>{@code name} 未命名时为 {@code null}（{@code generateUrls.ts} 里
 *       {@code route.name && ...} 会过滤掉）；</li>
 *   <li>只输出这两列 —— {@code --columns=uri,name} 的语义。</li>
 * </ul>
 */
public final class RouteListJson {

    private RouteListJson() {
    }

    /** 生成 JSON 文本。 */
    public static String render(Routes routes) {
        PhpJson.Array body = new PhpJson.Array();
        int index = 0;

        for (RouteDefinition route : routes.all()) {
            // 用 PhpJson.Array 保序且输出真正的 JSON 数组
            PhpJson.Array row = new PhpJson.Array();
            row.put("uri", route.uri());
            row.put("name", route.name() == null || route.name().isEmpty()
                    ? null : route.name());
            body.put(index++, row);
        }

        return PhpJson.encode(body, PhpJson.JSON_UNESCAPED_UNICODE
                | PhpJson.JSON_UNESCAPED_SLASHES);
    }

    /** 便捷入口：直接从 classpath 读路由表再输出。 */
    public static String render() {
        return render(Routes.load());
    }

    /**
     * 供 {@code generateUrls.ts} 使用的「前缀过滤后的名字 → uri」映射
     * （{@code auth.} / {@code user.} / {@code skinlib.} / {@code texture.} /
     * {@code admin.}），便于测试与诊断。
     */
    public static Map<String, String> supportedNames(Routes routes) {
        List<String> prefixes = List.of("auth.", "user.", "skinlib.", "texture.", "admin.");
        Map<String, String> out = new LinkedHashMap<>();

        for (RouteDefinition route : routes.all()) {
            String name = route.name();
            if (name == null || name.isEmpty() || name.endsWith(".")) {
                continue;
            }
            for (String prefix : prefixes) {
                if (name.startsWith(prefix)) {
                    // 重名时后者覆盖前者（与 Laravel 的 RouteCollection 一致）
                    out.put(name, route.uri());
                    break;
                }
            }
        }
        return out;
    }
}
