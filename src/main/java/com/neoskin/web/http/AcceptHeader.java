package com.neoskin.web.http;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Symfony {@code AcceptHeader} 的移植。
 *
 * <p>Web 层只需要 {@code fromString()} / {@code all()} / {@code first()} /
 * {@code get()} / {@code has()}，但 {@code matches()} 与 {@code getSpecificity()}
 * 一整套特异性匹配也照抄了：{@code get()} 在直接命中失败后会走候选排序，
 * 少实现一步就会在 {@code text/*} 这类头上有行为差异。
 *
 * <p>三条与 PHP 容器语义绑定的规则：
 * <ol>
 *   <li>{@code items} 是<b>关联数组</b>，键为 {@link #canonicalKey}。重复键
 *       （例如 {@code en;q=0.9, en;q=0.8}）保留<b>首次出现的位置</b>、取<b>最后的值</b>
 *       ——这正是 {@code LinkedHashMap} 的 {@code put} 行为；</li>
 *   <li>{@code add()} 把 {@code sorted} 置 false，{@code all()/first()} 才真正排序；</li>
 *   <li>{@code uasort} 是稳定排序：quality 降序、index 升序。用
 *       {@link Comparator} 显式比较两者即可（index 由 fromString 赋值，通常唯一）。</li>
 * </ol>
 */
public final class AcceptHeader {

    /** 键是 canonical key，值是条目；顺序即「首次插入顺序」，排序后变成 quality 降序。 */
    private final LinkedHashMap<String, AcceptHeaderItem> items = new LinkedHashMap<>();

    private boolean sorted = true;

    public AcceptHeader() {
    }

    public AcceptHeader(List<AcceptHeaderItem> initial) {
        for (AcceptHeaderItem item : initial) {
            add(item);
        }
    }

    /**
     * {@code AcceptHeader::fromString($headerValue)}。
     *
     * <p>{@code null} 与空串都得到空表（PHP 侧 {@code HeaderUtils::split('')} 返回
     * {@code []}）。注意「请求没有 Accept-Language 头」并不等于这里收到空串：
     * Symfony 的 {@code Request::create()} 会塞入默认值 {@code en-us,en;q=0.5}，
     * 因此语料用例 {@code accept.absent} 期望 {@code [en_US, en]}。
     */
    public static AcceptHeader fromString(String headerValue) {
        List<AcceptHeaderItem> parsed = new ArrayList<>();
        List<Object> groups = HeaderUtils.split(headerValue == null ? "" : headerValue, ",;=");
        int index = 0;
        for (Object group : groups) {
            int i = index++;
            @SuppressWarnings("unchecked")
            List<Object> fields = (List<Object>) group;
            String value = "";
            List<List<String>> rest = new ArrayList<>();
            for (int k = 0; k < fields.size(); k++) {
                @SuppressWarnings("unchecked")
                List<String> sub = (List<String>) fields.get(k);
                if (k == 0) {
                    value = sub.isEmpty() ? "" : sub.get(0);
                } else {
                    rest.add(sub);
                }
            }
            parsed.add(new AcceptHeaderItem(value, HeaderUtils.combine(rest)).setIndex(i));
        }
        return new AcceptHeader(parsed);
    }

    /** {@code add()}：按 canonical key 写入，重复键保留首次位置。 */
    public AcceptHeader add(AcceptHeaderItem item) {
        items.put(canonicalKey(item), item);
        sorted = false;
        return this;
    }

    /** {@code all()}：排好序的全部条目（按 quality 降序、index 升序）。 */
    public List<AcceptHeaderItem> all() {
        sort();
        return new ArrayList<>(items.values());
    }

    /** {@code first()}：{@code all()} 的第一项，空表返回 {@code null}。 */
    public AcceptHeaderItem first() {
        sort();
        return items.isEmpty() ? null : items.values().iterator().next();
    }

    /** {@code has($value)}：只按 canonical key 判断，不做特异性匹配。 */
    public boolean has(String value) {
        return items.containsKey(canonicalKey(AcceptHeaderItem.fromString(value)));
    }

    /**
     * {@code get($value)}：先查 canonical key，再退回「候选 + 特异性排序」。
     */
    public AcceptHeaderItem get(String value) {
        AcceptHeaderItem query = AcceptHeaderItem.fromString(value + ";q=1");
        AcceptHeaderItem direct = items.get(canonicalKey(query));
        if (direct != null) {
            return direct;
        }

        List<AcceptHeaderItem> candidates = new ArrayList<>();
        for (AcceptHeaderItem item : items.values()) {
            if (matches(item, query)) {
                candidates.add(item);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        // 特异性降序 → quality 降序 → index 升序
        candidates.sort((a, b) -> {
            int bySpecificity = Integer.compare(specificity(b, query), specificity(a, query));
            if (bySpecificity != 0) {
                return bySpecificity;
            }
            int byQuality = Double.compare(b.getQuality(), a.getQuality());
            if (byQuality != 0) {
                return byQuality;
            }
            return Integer.compare(a.getIndex(), b.getIndex());
        });
        return candidates.get(0);
    }

    // ================================================================== 内部

    private void sort() {
        if (sorted) {
            return;
        }
        List<Map.Entry<String, AcceptHeaderItem>> entries = new ArrayList<>(items.entrySet());
        entries.sort(Comparator
                .comparingDouble((Map.Entry<String, AcceptHeaderItem> e) -> e.getValue().getQuality())
                .reversed()
                .thenComparingInt(e -> e.getValue().getIndex()));
        items.clear();
        for (Map.Entry<String, AcceptHeaderItem> e : entries) {
            items.put(e.getKey(), e.getValue());
        }
        sorted = true;
    }

    /**
     * {@code getCanonicalKey()}：value + 排序后的属性（去掉 q、键转小写）。
     *
     * <p>值为 {@code null} 的「标志参数」只写名字；值含空白/{@code ;,=} 时用双引号
     * 包裹并把 {@code "} 与 {@code \} 反斜杠转义。
     */
    private static String canonicalKey(AcceptHeaderItem item) {
        Map<String, String> attributes = mediaParams(item);
        List<String> names = new ArrayList<>(attributes.keySet());
        names.sort(null);

        StringBuilder sb = new StringBuilder(item.getValue());
        if (names.isEmpty()) {
            return sb.toString();
        }
        sb.append(';');
        boolean first = true;
        for (String name : names) {
            if (!first) {
                sb.append(';');
            }
            first = false;
            String v = attributes.get(name);
            sb.append(name).append('=').append(quoteValue(v));
        }
        return sb.toString();
    }

    private static String quoteValue(String value) {
        if (value == null) {
            // 目前 combine() 不会产生 null（单元素项是 "1"），保留分支以防后续扩展
            return "";
        }
        boolean needsQuote = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == ';' || c == ',' || c == '=') {
                needsQuote = true;
                break;
            }
        }
        if (!needsQuote) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.append('"').toString();
    }

    /** {@code getMediaParams()}：属性键转小写并去掉 {@code q}。 */
    private static Map<String, String> mediaParams(AcceptHeaderItem item) {
        Map<String, String> out = new LinkedHashMap<>();
        item.getAttributes().forEach((k, v) -> out.put(k.toLowerCase(java.util.Locale.ROOT), v));
        out.remove("q");
        return out;
    }

    private boolean matches(AcceptHeaderItem rangeItem, AcceptHeaderItem queryItem) {
        String rangeValue = lower(rangeItem.getValue());
        String queryValue = lower(queryItem.getValue());

        if ("*".equals(rangeValue) || "*/*".equals(rangeValue)) {
            return rangeParametersMatch(rangeItem, queryItem);
        }
        if ("*".equals(queryValue)) {
            return false;
        }

        boolean queryMedia = queryValue.indexOf('/') >= 0;
        boolean rangeMedia = rangeValue.indexOf('/') >= 0;
        if (queryMedia != rangeMedia) {
            return false;
        }
        if (!queryMedia) {
            return rangeValue.equals(queryValue) && rangeParametersMatch(rangeItem, queryItem);
        }

        String[] query = queryValue.split("/", 2);
        String[] range = rangeValue.indexOf('/') >= 0 ? rangeValue.split("/", 2) : new String[] {rangeValue, "*"};

        if (!"*".equals(range[0]) && !range[0].equals(query[0])) {
            return false;
        }
        if (!"*".equals(range[1]) && !range[1].equals(query[1])) {
            return false;
        }
        return rangeParametersMatch(rangeItem, queryItem);
    }

    private boolean rangeParametersMatch(AcceptHeaderItem rangeItem, AcceptHeaderItem queryItem) {
        Map<String, String> queryAttributes = mediaParams(queryItem);
        Map<String, String> rangeAttributes = mediaParams(rangeItem);

        for (Map.Entry<String, String> e : rangeAttributes.entrySet()) {
            String name = e.getKey();
            String rangeValue = e.getValue();
            if (!queryAttributes.containsKey(name)) {
                return false;
            }
            String queryValue = queryAttributes.get(name);
            if (rangeValue == null) {
                return queryValue == null;
            }
            if (queryValue == null || !lower(queryValue).equals(lower(rangeValue))) {
                return false;
            }
        }
        return true;
    }

    private static int specificity(AcceptHeaderItem item, AcceptHeaderItem queryItem) {
        String rangeValue = lower(item.getValue());
        String queryValue = lower(queryItem.getValue());
        int paramCount = mediaParams(item).size();

        boolean queryMedia = queryValue.indexOf('/') >= 0;
        boolean rangeMedia = rangeValue.indexOf('/') >= 0;
        if (!queryMedia && !rangeMedia) {
            return ("*".equals(rangeValue) ? 1000 : 2000) + paramCount;
        }

        String[] range = rangeValue.indexOf('/') >= 0 ? rangeValue.split("/", 2) : new String[] {rangeValue, "*"};
        int base;
        if (!"*".equals(range[1])) {
            base = 3000;
        } else if (!"*".equals(range[0])) {
            base = 2000;
        } else {
            base = 1000;
        }
        return base + paramCount;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }
}
