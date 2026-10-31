package com.neoskin.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 路由表的加载器：权威数据是 {@code codegraph/routes.json}（173 条）。
 *
 * <p>源项目用 Laravel 的 RouteRegister 生成；本工程按 AGENTS.md §2.1
 * 「HTTP 接口零变更」把这份清单原封不动内嵌到
 * {@code classpath:codegraph/routes.json}，由 {@link #load()} 一比一还原。
 * 不得增删改路径、方法或参数。</p>
 */
public final class Routes {

    /** JSON 反序列化器；Jackson 3（tools.jackson）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 源 routes.json 的条目结构（只取四个字段）。 */
    private record Entry(String method, String uri, String name, String action, List<String> middleware) {
    }

    /** 全部 173 条路由（保持源文件顺序——Laravel 的匹配顺序语义）。 */
    private final List<RouteDefinition> all;

    /** action → 路由 的索引（控制器移植进度核对用）。 */
    private final Map<String, List<RouteDefinition>> byAction = new ConcurrentHashMap<>();

    private Routes(List<RouteDefinition> all) {
        this.all = List.copyOf(all);

        for (RouteDefinition definition : all) {
            byAction.computeIfAbsent(definition.action(), k -> new ArrayList<>()).add(definition);
        }
    }

    /** {@code classpath:codegraph/routes.json} → {@link Routes}。 */
    public static Routes load() {
        try (InputStream in = Routes.class.getResourceAsStream("/codegraph/routes.json")) {
            if (in == null) {
                throw new IllegalStateException("classpath:codegraph/routes.json 未随 jar 打包");
            }

            List<Entry> entries = JSON.readValue(in, new TypeReference<>() {
            });
            List<RouteDefinition> definitions = new ArrayList<>(entries.size());

            for (Entry entry : entries) {
                definitions.add(new RouteDefinition(
                        RouteDefinition.splitMethods(entry.method()),
                        entry.uri(),
                        entry.name(),
                        entry.action(),
                        List.copyOf(entry.middleware())));
            }

            return new Routes(definitions);
        } catch (IOException e) {
            throw new IllegalStateException("路由表读取失败", e);
        }
    }

    /** 173 条的完整清单。 */
    public List<RouteDefinition> all() {
        return all;
    }

    /** 路由条数（恒为 173；测试会断言）。 */
    public int count() {
        return all.size();
    }

    /** action → 路由 的索引。 */
    public List<RouteDefinition> byAction(String action) {
        return byAction.getOrDefault(action, List.of());
    }

    /** 有哪些控制器 action（移植进度对账用）。 */
    public Set<String> actions() {
        return Set.copyOf(byAction.keySet());
    }
}
