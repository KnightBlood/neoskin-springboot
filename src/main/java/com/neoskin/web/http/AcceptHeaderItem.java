package com.neoskin.web.http;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Symfony {@code AcceptHeaderItem} 的移植。
 *
 * <p>只保留 Web 层用得到的部分（{@code value} / {@code quality} / {@code index} /
 * {@code attributes}）。三个必须照抄的细节：
 *
 * <ol>
 *   <li>{@code q} 参数<b>不进</b> {@code attributes}，而是被 {@link #setAttribute}
 *       直接吃掉并转成 {@code (float)} 赋给 quality；</li>
 *   <li>{@code combine()} 里单元素项的值为 {@code (string) true} = {@code "1"}，
 *       所以在 {@code rangeParametersMatch} 里不会被当成 {@code null} 标志位；</li>
 *   <li>{@code setAttribute} 的参数类型是 {@code string}——PHP 在弱类型模式下会把
 *       {@code true} 转成 {@code "1"}，这里用 {@link String} 表达同一事实。</li>
 * </ol>
 */
public final class AcceptHeaderItem {

    private String value;
    private double quality = 1.0;
    private int index = 0;
    private final Map<String, String> attributes = new LinkedHashMap<>();

    public AcceptHeaderItem(String value, Map<String, String> attributes) {
        this.value = value;
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            setAttribute(e.getKey(), e.getValue());
        }
    }

    /** {@code AcceptHeaderItem::fromString($itemValue)}，分隔符固定为 {@code ";="}。 */
    public static AcceptHeaderItem fromString(String itemValue) {
        var parts = HeaderUtils.split(itemValue == null ? "" : itemValue, ";=");
        String value = "";
        var rest = new java.util.ArrayList<java.util.List<String>>();
        for (int i = 0; i < parts.size(); i++) {
            @SuppressWarnings("unchecked")
            java.util.List<String> group = (java.util.List<String>) parts.get(i);
            if (i == 0) {
                value = group.isEmpty() ? "" : group.get(0);
            } else {
                rest.add(group);
            }
        }
        return new AcceptHeaderItem(value, HeaderUtils.combine(rest));
    }

    public String getValue() {
        return value;
    }

    public AcceptHeaderItem setValue(String value) {
        this.value = value;
        return this;
    }

    public double getQuality() {
        return quality;
    }

    public AcceptHeaderItem setQuality(double quality) {
        this.quality = quality;
        return this;
    }

    public int getIndex() {
        return index;
    }

    public AcceptHeaderItem setIndex(int index) {
        this.index = index;
        return this;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    /**
     * {@code setAttribute()}：{@code q} 被转成 quality（PHP 的 {@code (float)} 语义，
     * 非数字前缀退化成 0），其余原样放进 attributes。
     */
    public AcceptHeaderItem setAttribute(String name, String value) {
        if ("q".equals(name)) {
            this.quality = phpFloat(value);
        } else {
            this.attributes.put(name, value);
        }
        return this;
    }

    /** PHP 的 {@code (float) $string}：取最长的合法数字前缀，失败为 0。 */
    static double phpFloat(String s) {
        if (s == null) {
            return 0.0;
        }
        String t = s.stripLeading();
        int i = 0;
        int len = t.length();
        int start = 0;
        if (i < len && (t.charAt(i) == '+' || t.charAt(i) == '-')) {
            i++;
        }
        int digitsStart = i;
        while (i < len && Character.isDigit(t.charAt(i))) {
            i++;
        }
        boolean hasDigits = i > digitsStart;
        if (i < len && t.charAt(i) == '.') {
            int fracStart = ++i;
            while (i < len && Character.isDigit(t.charAt(i))) {
                i++;
            }
            hasDigits = hasDigits || i > fracStart;
        }
        if (!hasDigits) {
            return 0.0;
        }
        int mantissaEnd = i;
        if (i < len && (t.charAt(i) == 'e' || t.charAt(i) == 'E')) {
            int expMark = i;
            int j = i + 1;
            if (j < len && (t.charAt(j) == '+' || t.charAt(j) == '-')) {
                j++;
            }
            int expStart = j;
            while (j < len && Character.isDigit(t.charAt(j))) {
                j++;
            }
            if (j > expStart) {
                mantissaEnd = j;
            } else {
                mantissaEnd = expMark;
            }
        }
        String numeric = t.substring(start, mantissaEnd);
        try {
            return Double.parseDouble(numeric);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(value);
        if (quality < 1) {
            sb.append(";q=").append(quality);
        }
        if (!attributes.isEmpty()) {
            sb.append("; ");
            boolean first = true;
            for (Map.Entry<String, String> e : attributes.entrySet()) {
                if (!first) {
                    sb.append("; ");
                }
                first = false;
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return sb.toString();
    }
}
