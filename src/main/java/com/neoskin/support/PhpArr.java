package com.neoskin.support;

import java.util.List;
import java.util.Map;

/**
 * Laravel {@code Arr::get()} / {@code Arr::has()} / {@code Arr::exists()} 的移植。
 *
 * <p>之所以不写成 {@code Map.get(key)}：这三个方法的语义与 Java 直觉差得比较远，
 * 而 {@code config/locales.php} 的查找、请求参数的 {@code input()}、
 * {@code json()} 助手的入参解析都依赖它们，差一点就会改变对外行为。
 *
 * <p>照抄的关键点（都已由 {@code conformance/data/locale.json} 的
 * {@code lookup.*} 用例锁定）：
 * <ol>
 *   <li>{@code get} 在 {@code $key === null} 时<b>直接返回整个数组</b>，不看默认值；</li>
 *   <li>{@code get} 先做一次「整体键」命中（{@code exists}），如果数组里真有一个
 *       名叫 {@code "a.b"} 的键，它优先于点号下钻；</li>
 *   <li>{@code get} 在没有 {@code '.'} 时返回默认值，不会把键当路径；</li>
 *   <li>{@code has} 在 {@code $array} 为空或 {@code $keys} 为空时返回 false
 *       ——所以 {@code Arr::has($locales, null)} 是 false；</li>
 *   <li>{@code has} 的每一段都要求 {@code accessible}，中途遇到标量就失败，
 *       且失败即整体失败（不像 {@code get} 那样返回默认值）。</li>
 * </ol>
 *
 * <p>PHP 的数组下标会把 {@code "0"} 归一成 int {@code 0}，而 {@code "01"}、
 * {@code "1.0"} 保持字符串。这里用 {@link PhpJson#normalizeKey} 表达同一规则。
 */
public final class PhpArr {

    private PhpArr() {
    }

    /** {@code Arr::accessible()}：PHP 的 array 即可；这里对应 {@link Map} 与 {@link List}。 */
    public static boolean accessible(Object value) {
        return value instanceof Map<?, ?> || value instanceof List<?>;
    }

    /**
     * {@code Arr::exists()}。
     *
     * <p>PHP 对 {@code float} 与 {@code null} 的键先转字符串再查，
     * 其余走 {@code array_key_exists}（含整数键归一）。
     */
    public static boolean exists(Object array, Object key) {
        if (!accessible(array)) {
            return false;
        }
        Object normalized = key;
        if (key instanceof Double || key instanceof Float || key == null) {
            normalized = PhpString.cast(key);
        }
        normalized = PhpJson.normalizeKey(normalized);
        if (array instanceof Map<?, ?> map) {
            return map.containsKey(normalized);
        }
        List<?> list = (List<?>) array;
        if (normalized instanceof Long l) {
            return l >= 0 && l < list.size();
        }
        return false;
    }

    /**
     * {@code Arr::get($array, $key, $default)}。
     *
     * @param fallback 键不存在时的返回值（PHP 侧支持闭包，这里用已求值的值代替）
     */
    public static Object get(Object array, Object key, Object fallback) {
        if (!accessible(array)) {
            return fallback;
        }
        if (key == null) {
            return array;
        }
        if (exists(array, key)) {
            return value(array, key);
        }
        String path = PhpString.cast(key);
        if (!path.contains(".")) {
            return fallback;
        }
        Object cursor = array;
        for (String segment : path.split("\\.", -1)) {
            if (accessible(cursor) && exists(cursor, segment)) {
                cursor = value(cursor, segment);
            } else {
                return fallback;
            }
        }
        return cursor;
    }

    /** 便捷重载：无默认值。 */
    public static Object get(Object array, Object key) {
        return get(array, key, null);
    }

    /**
     * {@code Arr::has($array, $keys)}：支持数组形式的多个键（全部命中才为 true）。
     */
    public static boolean has(Object array, Object keys) {
        List<Object> keyList;
        if (keys instanceof List<?> list) {
            keyList = new java.util.ArrayList<>(list);
        } else if (keys == null) {
            keyList = List.of();
        } else {
            keyList = List.of(keys);
        }

        if (!truthy(array) || keyList.isEmpty()) {
            return false;
        }

        for (Object key : keyList) {
            Object cursor = array;
            if (exists(array, key)) {
                continue;
            }
            boolean ok = true;
            for (String segment : PhpString.cast(key).split("\\.", -1)) {
                if (accessible(cursor) && exists(cursor, segment)) {
                    cursor = value(cursor, segment);
                } else {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** PHP 的 {@code (bool)}：空数组、空串、{@code "0"}、{@code 0}、{@code null} 都是 false。 */
    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        String s = String.valueOf(value);
        return !s.isEmpty() && !"0".equals(s);
    }

    private static Object value(Object array, Object key) {
        Object normalized = PhpJson.normalizeKey(
                key instanceof Double || key instanceof Float || key == null ? PhpString.cast(key) : key);
        if (array instanceof Map<?, ?> map) {
            return map.get(normalized);
        }
        List<?> list = (List<?>) array;
        return list.get(((Long) normalized).intValue());
    }
}
