package com.neoskin.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code Blessing\Filter} 的移植：插件钩子的「值变换」总线。
 *
 * <p>源实现（{@code vendor/blessing/filter/src/Blessing/Filter.php}）：</p>
 * <pre>
 * apply($hook, $init, $args = []) {
 *     $listeners = $this-&gt;getListeners($hook);
 *     if ($listeners-&gt;isNotEmpty()) {
 *         return $listeners-&gt;sortBy('priority')-&gt;reduce(function ($carry, $item) use ($args) {
 *             $arguments = array_merge([], [$carry], $args);
 *             return call_user_func_array($item['filter'], $arguments);
 *         }, $init);
 *     }
 *     return $init;
 * }
 * </pre>
 *
 * <p>三个必须照抄的细节：</p>
 * <ol>
 *   <li><b>按 priority 升序</b>执行（{@code sortBy} 是升序，源注释写"Higher value
 *       is with higher priority"与实际相反 —— 以代码为准）；</li>
 *   <li>没有监听者时<b>原样返回 init</b>（不复制、不包装）；</li>
 *   <li>每个监听者拿到的是「上一个的返回值 + 固定附加参数」。</li>
 * </ol>
 *
 * <p>PF4J 未接时监听者为空 —— 行为与「没有插件」这一子集完全一致。
 * 插件接好后由 {@code PluginManager} 注册监听者。</p>
 */
public final class BlessingFilter {

    /** 一个过滤器：{@code (carry, args) → newCarry}。 */
    @FunctionalInterface
    public interface Listener {

        Object apply(Object carry, List<Object> args);
    }

    private final Map<String, List<Entry>> listeners = new LinkedHashMap<>();

    /** {@code add($hook, $filter, $priority = 20)}。 */
    public void add(String hook, Listener filter, int priority) {
        listeners.computeIfAbsent(hook, k -> new ArrayList<>())
                .add(new Entry(filter, priority));
    }

    /** {@code add($hook, $filter)}（默认优先级 20）。 */
    public void add(String hook, Listener filter) {
        add(hook, filter, 20);
    }

    /** {@code apply($hook, $init)}。 */
    public Object apply(String hook, Object init) {
        return apply(hook, init, List.of());
    }

    /** {@code apply($hook, $init, $args)}。 */
    public Object apply(String hook, Object init, List<Object> args) {
        List<Entry> entries = listeners.get(hook);
        if (entries == null || entries.isEmpty()) {
            return init;
        }

        List<Entry> sorted = new ArrayList<>(entries);
        // sortBy 是稳定排序：同优先级保持注册顺序
        sorted.sort(Comparator.comparingInt(Entry::priority));

        Object carry = init;
        for (Entry entry : sorted) {
            carry = entry.filter().apply(carry, args == null ? List.of() : args);
        }
        return carry;
    }

    /** {@code remove($hook)}：清空某个钩子的全部监听者。 */
    public void remove(String hook) {
        listeners.remove(hook);
    }

    /** 某个钩子的监听者数量（测试与诊断用）。 */
    public int count(String hook) {
        List<Entry> entries = listeners.get(hook);
        return entries == null ? 0 : entries.size();
    }

    /** 清空全部监听者（测试隔离用）。 */
    public void clear() {
        listeners.clear();
    }

    private record Entry(Listener filter, int priority) {
    }
}
