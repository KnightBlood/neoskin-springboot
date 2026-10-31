package com.neoskin.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.PostProcessor;

/**
 * {@code League\CommonMark\Extension\Autolink} 两个内联解析器的等价实现。
 *
 * <h2>为什么不用 {@code commonmark-ext-autolink}</h2>
 * <p>commonmark-java 的 autolink 扩展把扫描委托给 nibor 的 autolink 库，而该库的
 * 域名扫描用 {@code isAlnum(c) || isNonAscii(c)}（见 {@code EmailScanner#subDomainAllowed}）
 * 判定域名字符 —— <b>任意非 ASCII 字符都算域名字符</b>。于是中文正文里的
 * {@code 测试邮箱 a@b.com。} 会把句号一起吞进链接（{@code mailto:a@b.com。}），
 * 而 PHP 侧 league 的邮箱正则是纯 ASCII 字符类
 * （{@code [A-Za-z0-9.\-_+]+@[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_.]+}），句号留在正文里。
 * 中文站的公告正文几乎必然出现这种写法，所以必须复刻 PHP 的行为。</p>
 *
 * <h2>复刻的两个解析器</h2>
 * <ol>
 *   <li>{@code EmailAutolinkParser}：整行任意位置匹配邮箱；末字符为 {@code -}/{@code _}
 *       则放弃，为 {@code .} 则去掉一个再建链接；</li>
 *   <li>{@code UrlAutolinkParser}：只在 {@code www.} / {@code http://} / {@code https://} /
 *       {@code ftp://} 处起匹配，且前一个字符必须是行首或
 *       {@code [ \t\n\x0b\x0c\r*_~(]}；匹配后再按「尾部标点 → 实体引用 → 不配对括号」
 *       三步修剪。</li>
 * </ol>
 *
 * <p>两个解析器在 league 里同优先级、按注册顺序（邮箱在前）参与「同一位置谁先命中」，
 * 因此候选按起点排序、同起点邮箱优先。</p>
 *
 * <p>简化项：IPv6 字面量分支用 {@code \[[0-9A-Fa-f:.]+\]} 近似（PHP 侧是一条
 * 完整 IPv6 文法）；对公告正文的实际内容无影响，记入 PORT-NOTES。</p>
 */
final class LeagueAutolink implements PostProcessor {

    /** {@code EmailAutolinkParser::REGEX}。 */
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9.\\-_+]+@[A-Za-z0-9\\-_]+\\.[A-Za-z0-9\\-_.]+",
            Pattern.CASE_INSENSITIVE);

    /** {@code UrlAutolinkParser::ALLOWED_AFTER}：行首（null）或这些字符。 */
    private static final String ALLOWED_AFTER = " \t\n\u000b\u000c\r*_~(";

    /** {@code UrlAutolinkParser::REGEX}（去掉 x 修饰的展开形式，A 锚用 region+lookingAt 实现）。 */
    private static final Pattern URL = Pattern.compile(
            "(?:(?:(?:https?|ftp)://"
                    + "(?:(?:(?:[_.\\p{L}\\p{N}-]|%[0-9A-Fa-f]{2})+:)?"
                    + "(?:[_.\\p{L}\\p{N}-]|%[0-9A-Fa-f]{2})+@)?)"
                    + "|www\\.)"
                    + "(?:(?:(?:xn--[a-z0-9-]+\\.)*xn--[a-z0-9-]+"
                    + "|(?:[\\p{L}\\p{N}\\p{S}\\p{M}\\-_]+\\.){1,127}[\\p{L}\\p{N}\\p{M}]+"
                    + "|[a-z0-9\\-_]+)\\.?"
                    + "|\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"
                    + "|\\[[0-9A-Fa-f:.]+\\])"
                    + "(?::[0-9]+)?"
                    + "(?:/(?:[\\p{L}\\p{N}\\-._~!$&\\'()*+,;=:@]|%[0-9A-Fa-f]{2})*)*"
                    + "(?:\\?(?:[\\p{L}\\p{N}\\-._~!$&\\'\\[\\]()*+,;=:@/?]|%[0-9A-Fa-f]{2})*)?"
                    + "(?:#(?:[\\p{L}\\p{N}\\-._~!$&\\'()*+,;=:@/?]|%[0-9A-Fa-f]{2})*)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** 尾部标点：{@code /(.+?)([?!.,:*_~]+)$/}。 */
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("(.+?)([?!.,:*_~]+)$");

    /** 实体引用：{@code /(.+)(&[A-Za-z0-9]+;)$/}。 */
    private static final Pattern TRAILING_ENTITY = Pattern.compile("(.+)(&[A-Za-z0-9]+;)$");

    /** 候选（起点、终点、href、显示文本）。 */
    private record Candidate(int start, int end, String href, String label) {
    }

    @Override
    public Node process(Node node) {
        node.accept(new Visitor());
        return node;
    }

    /** {@code Link} 内的文字不再二次解析（对应 nibor 的 {@code inLink} 计数）。 */
    private static final class Visitor extends AbstractVisitor {

        private int inLink;

        @Override
        public void visit(Link link) {
            inLink++;
            visitChildren(link);
            inLink--;
        }

        @Override
        public void visit(Text text) {
            if (inLink == 0) {
                autolinkify(text);
            }
        }
    }

    /** 把一个文本节点拆成「文本 + 链接 + 文本…」。 */
    private static void autolinkify(Text textNode) {
        String literal = textNode.getLiteral();
        List<Candidate> candidates = findCandidates(literal);
        if (candidates.isEmpty()) {
            return;
        }

        Node cursor = textNode;
        int pos = 0;
        for (Candidate candidate : candidates) {
            if (candidate.start() < pos) {
                // 与已消费区间重叠：league 的游标已经越过去了
                continue;
            }
            if (candidate.start() > pos) {
                Text before = new Text(literal.substring(pos, candidate.start()));
                cursor.insertAfter(before);
                cursor = before;
            }
            Link link = new Link(candidate.href(), null);
            link.appendChild(new Text(candidate.label()));
            cursor.insertAfter(link);
            cursor = link;
            pos = candidate.end();
        }
        if (pos < literal.length()) {
            cursor.insertAfter(new Text(literal.substring(pos)));
        }
        textNode.unlink();
    }

    /** 收集两个解析器在同一段文字上的全部候选（按起点、邮箱优先）。 */
    private static List<Candidate> findCandidates(String text) {
        List<Candidate> out = new ArrayList<>();

        Matcher email = EMAIL.matcher(text);
        while (email.find()) {
            String value = email.group();
            // 末字符是 - 或 _ 时解析器直接放弃（不建链接，也不推进游标）
            char last = value.charAt(value.length() - 1);
            if (last == '-' || last == '_') {
                continue;
            }
            if (last == '.') {
                value = value.substring(0, value.length() - 1);
            }
            int start = email.start();
            out.add(new Candidate(start, start + value.length(),
                    "mailto:" + value, value));
        }

        Matcher url = URL.matcher(text);
        int from = 0;
        while (from < text.length()) {
            url.region(from, text.length());
            if (!url.lookingAt()) {
                // 前缀不存在于当前起点：跳到下一个可能的前缀
                int next = nextPrefix(text, from);
                if (next < 0) {
                    break;
                }
                from = next;
                continue;
            }
            int start = url.start();
            String value = trimUrl(url.group());
            if (value.isEmpty()) {
                from = start + 1;
                continue;
            }
            if (!previousAllowsAutolink(text, start)) {
                from = start + 1;
                continue;
            }
            String href = value.startsWith("www.") ? "http://" + value : value;
            out.add(new Candidate(start, start + value.length(), href, value));
            from = start + value.length();
        }

        out.sort(Comparator.comparingInt(Candidate::start));
        return out;
    }

    /** 从 {@code from} 起找下一个前缀起点（{@code www.} / {@code http://} / {@code https://} / {@code ftp://}）。 */
    private static int nextPrefix(String text, int from) {
        int best = -1;
        for (String prefix : List.of("www.", "http://", "https://", "ftp://")) {
            int at = indexOfIgnoreCase(text, prefix, from);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best;
    }

    private static int indexOfIgnoreCase(String text, String needle, int from) {
        int limit = text.length() - needle.length();
        for (int i = Math.max(from, 0); i <= limit; i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    /** {@code $cursor->peek(-1)} 必须属于 {@code ALLOWED_AFTER}（行首也允许）。 */
    private static boolean previousAllowsAutolink(String text, int start) {
        if (start == 0) {
            return true;
        }
        return ALLOWED_AFTER.indexOf(text.charAt(start - 1)) >= 0;
    }

    /**
     * 尾部修剪三步：标点 → 实体引用 → 不配对右括号。
     * 对应 {@code UrlAutolinkParser::parse} 里的三段 {@code preg_match}。
     */
    private static String trimUrl(String url) {
        Matcher punctuation = TRAILING_PUNCTUATION.matcher(url);
        if (punctuation.find()) {
            url = punctuation.group(1);
        }
        Matcher entity = TRAILING_ENTITY.matcher(url);
        if (entity.find()) {
            url = entity.group(1);
        }
        if (url.endsWith(")")) {
            int diff = diffParens(url);
            if (diff > 0) {
                url = url.substring(0, url.length() - diff);
            }
        }
        return url;
    }

    /** {@code 右括号数 - 左括号数}。 */
    private static int diffParens(String content) {
        int open = 0;
        int close = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '(') {
                open++;
            } else if (c == ')') {
                close++;
            }
        }
        return close - open;
    }
}
