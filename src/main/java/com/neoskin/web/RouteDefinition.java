package com.neoskin.web;

import java.util.List;
import java.util.Set;

/**
 * 单条路由定义（来自 {@code codegraph/routes.json}，173 条的雏形单元）。
 *
 * <p>{@code method} 在源数据里是 {@code GET|HEAD} 这样的多方法组合，这里拆成
 * 小写集合；闲置的 {@code OPTIONS/QUERY} 同样保留。</p>
 *
 * <p>{@code name} 是 Laravel 的具名路由（{@code route('admin.users.view')} 用），
 * 未命名路由为 {@code null}。173 条里 142 条有名字（去重后 141 —— 源里
 * {@code admin.plugins.view} 重名两次，取后者，与 Laravel 一致）。</p>
 */
public record RouteDefinition(Set<String> methods, String uri, String name,
        String action, List<String> middleware) {

    /** 拆分 {@code method} 字段（{@code "GET|HEAD"} → {@code [get, head]}）。 */
    public static Set<String> splitMethods(String combined) {
        return java.util.Arrays.stream(combined.split("\\|"))
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
