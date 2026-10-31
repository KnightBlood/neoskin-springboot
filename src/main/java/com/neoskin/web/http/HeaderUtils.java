package com.neoskin.web.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.neoskin.support.PhpString;

/**
 * Symfony {@code HeaderUtils} 的移植（只移植 Web 层用得到的部分）。
 *
 * <p>这个类的 {@link #split} 是 Symfony 里最难照抄的一段：它用一条带
 * {@code (?:... )++} 的 PCRE 把 "a;b=c, d" 这类头切成「按第一个分隔符分组、
 * 再按第二个分隔符分组」的嵌套数组。Java 不能直接搬 PCRE 的 possessive 量词，
 * 所以这里用手写扫描器复现它的三个要点：
 *
 * <ol>
 *   <li><b>值（token / quoted-string）优先于分隔符</b>：同一位置先尝试值分支，
 *       失败才当分隔符；</li>
 *   <li><b>值绝不会以空白结尾</b>：空白只有在「后面还有非分隔符内容」时才被吞掉，
 *       否则值到此为止（这就是 {@code "a  b" => ["a  b"]} 而不是 {@code ["a","b"]} 的原因）；</li>
 *   <li><b>分隔符会吃掉两侧空白</b>：{@code "en;q=0.5, zh-CN"} 里的 {@code ", "}
 *       整体作为一次分隔符匹配。</li>
 * </ol>
 *
 * <p>返回值是「叶子为 {@link String} 的嵌套 {@link List}」，层数等于分隔符个数：
 * {@code split(h, ";=")} 得到 {@code List<List<String>>}，
 * {@code split(h, ",;=")} 得到 {@code List<List<List<String>>>}。
 * 这样调用方可以像 PHP 那样 {@code array_shift()} 再 {@code combine()}。
 *
 * <p>已用 PHP 8.5 实测锁定（见 {@code probe_split.php} 的输出）：
 * <pre>
 * ""                  → []
 * ";;;"               → []
 * "a;b;c"       (;}  → ["a"],["b"],["c"]
 * "a;b=c;d"     (;}  → ["a"],["b","c"],["d"]
 * "a  b"        (,;=) → [["a  b"]]        ← 内部双空格保留、不切分
 * "a b;c=d e"   (;=)  → ["a b"],["c","d e"]
 * </pre>
 */
public final class HeaderUtils {

    private HeaderUtils() {
    }

    /** 一次正则匹配：值分支时 {@code separator == null}，分隔符分支时带上分隔符字符。 */
    private record Match(String text, String separator) {
    }

    /**
     * {@code HeaderUtils::split($header, $separators)}。
     *
     * @param header     头值，{@code null} 按空串处理
     * @param separators 分隔符集合，例如 {@code ";="}、{@code ",;="}；不能为空
     */
    public static List<Object> split(String header, String separators) {
        if (separators == null || separators.isEmpty()) {
            throw new IllegalArgumentException("At least one separator must be specified.");
        }
        List<Match> matches = tokenize(PhpString.trim(header == null ? "" : header), separators);
        return groupParts(matches, separators, true);
    }

    /**
     * 等价于 {@code HeaderUtils::combine()}：把若干个「两元素数组」合成关联数组。
     *
     * <p>键转小写；只有一个元素的项（例如 {@code ;flowed}）值为 {@code "1"}
     * ——PHP 那边是通过 {@code (string) true} 隐式转换得到的。
     */
    public static Map<String, String> combine(List<List<String>> parts) {
        Map<String, String> assoc = new LinkedHashMap<>();
        for (List<String> part : parts) {
            String name = part.get(0).toLowerCase(java.util.Locale.ROOT);
            assoc.put(name, part.size() > 1 ? part.get(1) : "1");
        }
        return assoc;
    }

    // ================================================================== 扫描

    private static List<Match> tokenize(String s, String separators) {
        List<Match> out = new ArrayList<>();
        int i = 0;
        int len = s.length();
        while (i < len) {
            int next = consumeValue(s, i, separators);
            if (next > i) {
                out.add(new Match(s.substring(i, next), null));
                i = next;
                continue;
            }
            // 分隔符分支：\s* (sep) \s*
            int k = i;
            while (k < len && isSpace(s.charAt(k))) {
                k++;
            }
            if (k < len && separators.indexOf(s.charAt(k)) >= 0) {
                int m = k + 1;
                while (m < len && isSpace(s.charAt(m))) {
                    m++;
                }
                out.add(new Match(s.substring(i, m), String.valueOf(s.charAt(k))));
                i = m;
                continue;
            }
            i++;
        }
        return out;
    }

    /**
     * 匹配一个「值」：{@code (quoted-string | token | 内部空白)++}。
     *
     * @return 结束下标；等于 {@code start} 表示该位置不是值的起点
     */
    private static int consumeValue(String s, int start, String separators) {
        int i = start;
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (c == '"') {
                i = consumeQuoted(s, i);
            } else if (!isSpace(c) && separators.indexOf(c) < 0) {
                // token: [^"\s<seps>]++
                while (i < len) {
                    char t = s.charAt(i);
                    if (t == '"' || isSpace(t) || separators.indexOf(t) >= 0) {
                        break;
                    }
                    i++;
                }
            } else if (isSpace(c)) {
                // 空白只有在「后面还有非分隔符内容」时才吞掉
                int run = i;
                while (run < len && isSpace(s.charAt(run))) {
                    run++;
                }
                if (run >= len || separators.indexOf(s.charAt(run)) >= 0) {
                    break;
                }
                i = run;
            } else {
                break;
            }
        }
        return i;
    }

    /** {@code "(?:[^"\\]|\\.)*(?:"|\\|$)} —— 从引号开始，永远能匹配到串尾。 */
    private static int consumeQuoted(String s, int start) {
        int i = start + 1;
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (c == '"') {
                return i + 1;
            }
            if (c == '\\' && i + 1 < len) {
                i += 2;
            } else {
                i++;
            }
        }
        return len;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
    }

    // ================================================================== 分组

    /**
     * {@code HeaderUtils::groupParts()}。
     *
     * @param first 是否是最外层（最外层不产生「尾随空串」）
     */
    private static List<Object> groupParts(List<Match> matches, String separators, boolean first) {
        String separator = separators.substring(0, 1);
        String rest = separators.substring(1);
        int i = 0;

        if (rest.isEmpty() && !first) {
            List<Object> parts = new ArrayList<>();
            parts.add("");
            for (Match match : matches) {
                if (i == 0 && match.separator() != null) {
                    i = 1;
                    parts.add("");
                } else {
                    parts.set(i, (String) parts.get(i) + unquote(match.text()));
                }
            }
            return parts;
        }

        List<Object> parts = new ArrayList<>();
        Map<Integer, List<Match>> grouped = new LinkedHashMap<>();
        for (Match match : matches) {
            if (separator.equals(match.separator())) {
                i++;
            } else {
                grouped.computeIfAbsent(i, k -> new ArrayList<>()).add(match);
            }
        }

        for (List<Match> group : grouped.values()) {
            if (rest.isEmpty()) {
                String unquoted = unquote(group.get(0).text());
                if (!unquoted.isEmpty()) {
                    parts.add(unquoted);
                }
            } else {
                List<Object> nested = groupParts(group, rest, false);
                if (!nested.isEmpty()) {
                    parts.add(nested);
                }
            }
        }

        return parts;
    }

    /** {@code HeaderUtils::unquote()}：去掉反斜杠转义与双引号。 */
    public static String unquote(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < len) {
                out.append(s.charAt(i + 1));
                i += 2;
            } else if (c == '"') {
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 把 {@code split(h, ",;=")} 的结果摊平成「每项一组字段」的形式，便于遍历。 */
    public static List<List<String>> leaves(List<Object> splitResult) {
        List<List<String>> out = new ArrayList<>();
        for (Object item : splitResult) {
            out.add(strings(item));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object node) {
        List<String> out = new ArrayList<>();
        if (node instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof List<?>) {
                    out.addAll(strings(child));
                } else {
                    out.add((String) child);
                }
            }
        } else if (node != null) {
            out.add((String) node);
        }
        return out;
    }

    /** 去掉重复项但保留首次出现的顺序（PHP {@code array_unique}）。 */
    static List<String> unique(List<String> values) {
        Set<String> seen = new LinkedHashSet<>(values);
        return new ArrayList<>(seen);
    }
}
