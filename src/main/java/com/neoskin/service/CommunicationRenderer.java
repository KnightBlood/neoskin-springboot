package com.neoskin.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import com.neoskin.web.error.PhpHtml;

/**
 * {@code App\Services\CommunicationRenderer} 的移植：公告 / 站内信内容渲染 + 消毒。
 *
 * <h2>源的三段式</h2>
 * <ol>
 *   <li><b>markdown → html</b>：{@code GithubFlavoredMarkdownConverter}
 *       （GFM 扩展集固定：autolink / DisallowedRawHtml / strikethrough /
 *       table / task-list；本工程配置 {@code allow_unsafe_links=false}）；</li>
 *   <li><b>blocks 拼装</b>：type ∈ text/heading/alert/collapse/divider，
 *       title 经 {@code htmlspecialchars(ENT_QUOTES)}，tone 允许
 *       info/success/warning/danger/primary/secondary，非法一律
 *       {@code info}；</li>
 *   <li><b>消毒</b>：DOM 级白名单（标签 / 属性 / class / style / id 重写 +
 *       {@code data-target} / {@code aria-controls} / {@code href} 的前缀同步）。</li>
 * </ol>
 *
 * <h2>与 PHP 的可实现差异</h2>
 * <ul>
 *   <li>markdown 引擎是 {@code commonmark-java}（对应 league/commonmark
 *       2.10.4），极端 case（引用嵌套、软/硬换行的边界）可能出现渲染差异；
 *       标准 case（标题/列表/链接/强调/表格/删除线）以
 *       {@code CommunicationRendererTest} 里的 PHP 实测输出为准；</li>
 *   <li>DOM 是 jsoup（对应 PHP DOMDocument + libxml 宽容解析），破损输入的
 *       修复路径可能不同 —— 但本管线的输入都来自校验过的 blocks 或
 *       上一轮 sanitize 的输出，双向都是受控内容。</li>
 * </ul>
 */
public final class CommunicationRenderer {

    /**
     * GFM 转换器默认启用的 DisallowedRawHtml 标签集（源
     * {@code DisallowedRawHtmlExtension::DEFAULT_DISALLOWED_TAGS}）。
     */
    private static final String[] DISALLOWED_RAW_TAGS = {"title", "textarea", "style", "xmp",
            "iframe", "noembed", "noframes", "script", "plaintext"};

    /** 原串：{@code /<(\/?(?:tags)(?:[\s\/>]|$))/i}。 */
    private static final Pattern DISALLOWED_TAG = Pattern.compile(
            "<(\\/?(" + String.join("|", DISALLOWED_RAW_TAGS) + "))(?=[\\s\\/>]|$)",
            Pattern.CASE_INSENSITIVE);

    /** tone 的合法值（合法的保持原值，否则落 info —— 与 PHP 的 match 白名单一致）。 */
    private static final List<String> TONES = List.of("info", "success", "warning", "danger",
            "primary", "secondary");

    private final Parser parser;

    /** GFM 扩展集：parser 与 renderer 都要注册（后者的扩展节点才有 renderer）。
     *  autolink 不在此列 —— 它由 {@link LeagueAutolink} 以 PostProcessor 复刻
     *  （commonmark-java 自带的走 nibor 库，域名规则与 league 不同）。 */
    private static final List<org.commonmark.Extension> EXTENSIONS = List.of(
            TablesExtension.create(), StrikethroughExtension.create(),
            TaskListItemsExtension.create());

    /** 标签白名单（不在名单内的标签解包：子节点保留、标签壳丢掉）。 */
    private static final List<String> KEEP_TAGS = List.of("div", "span", "p", "br", "hr", "h1",
            "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "strong", "b", "em", "i", "u", "s",
            "del", "small", "blockquote", "pre", "code", "a", "button", "table", "thead",
            "tbody", "tr", "th", "td", "details", "summary", "img");

    /** 连子节点一起删的标签。 */
    private static final List<String> REMOVE_TAGS = List.of("script", "style", "iframe",
            "object", "embed", "svg", "math", "form", "input", "textarea", "select", "meta",
            "link", "base");

    private static final Pattern ID_PATTERN = Pattern.compile("\\A[A-Za-z][\\w-]*\\z");

    private static final Pattern HREF_PATTERN = Pattern.compile(
            "\\A(?:https?://|mailto:|/(?!/)|#[A-Za-z][\\w-]*\\z)", Pattern.CASE_INSENSITIVE);

    private static final Pattern SRC_PATTERN = Pattern.compile("\\A(?:https://|/(?!/))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DATA_TARGET_PATTERN = Pattern.compile("\\A#[A-Za-z][\\w-]*\\z");

    private static final Pattern ARIA_CONTROLS_PATTERN = Pattern.compile("\\A[A-Za-z][\\w-]*\\z");

    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "\\A(?:card(?:-[\\w-]+)?|alert(?:-[\\w-]+)?|bg-[\\w-]+|text-[\\w-]+"
                    + "|font-weight-[\\w-]+|btn(?:-[\\w-]+)?|fa[srlbd]?|fa-[\\w-]+"
                    + "|collapse|collapsed|show|table(?:-[\\w-]+)?|[mp][trblxy]?-[0-5]"
                    + "|rounded|border(?:-[\\w-]+)?)\\z");

    private static final Pattern FONT_WEIGHT_PATTERN =
            Pattern.compile("\\A(?:bold|normal|[1-9]00)\\z");

    private static final Pattern PADDING_PATTERN = Pattern.compile("\\A[0-9 px.]{1,30}\\z");

    /** import 的 tone 探测正则（{@code (?:alert-|bg-|card-)(danger|red|...)}）。 */
    private static final Pattern IMPORT_TONE_PATTERN = Pattern.compile(
            "(?:alert-|bg-|card-)(danger|red|success|green|warning|yellow|primary|secondary|info|cyan)\\b");

    /** {@code h[1-6]}。 */
    private static final Pattern HEADING_PATTERN = Pattern.compile("\\Ah[1-6]\\z");

    public CommunicationRenderer() {
        // GithubFlavoredMarkdownConverter 的 GFM 扩展集固定，公开配置只有
        // allow_unsafe_links；DisallowedRawHtml 由 {@link #disallowRawHtml} 复刻。
        this.parser = Parser.builder()
                .extensions(EXTENSIONS)
                .postProcessor(new LeagueAutolink())
                .build();
    }

    // ------------------------------------------------------------ render

    /**
     * {@code render($data, $prefix)}。
     *
     * @param data   一条 {@code communication} 的 {@code toArray()}，或编辑器数据；
     *               {@code editor = 'source'} 用 {@code source}，否则用 {@code blocks}
     * @param prefix id 前缀（null 则随机）
     */
    public String render(Map<String, Object> data, String prefix) {
        String realPrefix = prefix != null ? prefix : "notice-" + randomPrefix();

        Object editor = data.get("editor");
        boolean isSource = editor == null || "source".equals(editor);

        StringBuilder html = new StringBuilder();
        if (isSource) {
            Object source = data.get("source");
            html.append(convertMarkdown(source == null ? "" : String.valueOf(source)));
        } else {
            Object rawBlocks = data.get("blocks");
            if (rawBlocks instanceof List<?> blocks) {
                int index = 0;
                for (Object item : blocks) {
                    // PHP: foreach ($data['blocks'] ?? [] as $i => $block) —— 每一块都
                    // 先过 markdown（heading/divider 虽然最终不用 });
                    String text = convertMarkdown(contentOf(item));
                    String title = PhpHtml.escape(titleOf(item));
                    String tone = toneOf(item);
                    html.append(switch (typeOf(item)) {
                        case "heading" -> "<h3>" + title + "</h3>";
                        case "alert" -> "<div class=\"alert alert-" + tone + "\">"
                                + text + "</div>";
                        case "collapse" -> collapseBlock(index, title, text, tone,
                                !empty(((Map<?, ?>) item).get("expanded")));
                        case "divider" -> "<hr>";
                        default -> text;
                    });
                    index++;
                }
            }
        }

        return sanitize(html.toString(), realPrefix);
    }

    /** PHP 的 {@code empty()} 语义（null / '' / 0 / false → 真）。 */
    private static boolean empty(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return !b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() == 0;
        }
        String s = value.toString();
        return s.isEmpty() || "0".equals(s);
    }

    private static String typeOf(Object block) {
        return stringOf(field(block, "type"), "");
    }

    private static String contentOf(Object block) {
        return stringOf(field(block, "content"), "");
    }

    private static String titleOf(Object block) {
        return stringOf(field(block, "title"), "");
    }

    private static Object field(Object block, String key) {
        return block instanceof Map<?, ?> map ? map.get(key) : null;
    }

    private static String stringOf(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String toneOf(Object block) {
        String value = stringOf(field(block, "tone"), "");
        return TONES.contains(value) ? value : "info";
    }

    /** collapse 卡片（与 PHP 的拼接串逐字一致）。 */
    private static String collapseBlock(int index, String title, String text, String tone,
            boolean expanded) {
        return "<div class=\"card card-outline card-" + tone
                + "\"><div class=\"card-header\"><button type=\"button\""
                + " class=\"btn btn-link text-left p-0\" data-toggle=\"collapse\""
                + " data-target=\"#block-" + index + "\" aria-expanded=\""
                + (expanded ? "true" : "false") + "\">" + title + "</button></div>"
                + "<div id=\"block-" + index + "\" class=\"card-body collapse"
                + (expanded ? " show" : "") + "\">" + text + "</div></div>";
    }

    /** 随机前缀（{@code bin2hex(random_bytes(6))} → 12 个十六进制字符）。 */
    private static String randomPrefix() {
        byte[] bytes = new byte[6];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(12);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ markdown

    /** markdown → html，附 DisallowedRawHtml 的转义。 */
    public String convertMarkdown(String markdown) {
        Node document = parser.parse(markdown == null ? "" : markdown);
        // renderer 侧同样要挂扩展：Strikethrough / Table / TaskListItemMarker
        // 的节点类在核心里没有 renderer，不挂会「节点+全部子文」整体漏出
        String html = HtmlRenderer.builder().extensions(EXTENSIONS).build().render(document);
        return disallowRawHtml(html);
    }

    /**
     * league {@code DisallowedRawHtmlRenderer} 的正则复刻：
     * {@code /<(\/?(?:tags)(?:[\s\/>]|$))/i} → {@code &lt;$1}。
     */
    private static String disallowRawHtml(String html) {
        return DISALLOWED_TAG.matcher(html).replaceAll("&lt;$1");
    }

    // ------------------------------------------------------------ importBlocks

    /** 把 HTML 拆回 blocks（「源码模式 → 可视化组件」的导入）。 */
    public List<Object> importBlocks(String source) {
        String html = render(Map.<String, Object>of("editor", "source",
                "source", source == null ? "" : source), null);
        Document dom = Jsoup.parseBodyFragment(wrapRoot("import-root", html));
        dom.outputSettings().prettyPrint(false);

        Element root = dom.getElementById("import-root");
        List<Object> blocks = new ArrayList<>();
        if (root != null) {
            walk(root, blocks);
        }
        return blocks;
    }

    /**
     * PHP 的 {@code <?xml encoding="UTF-8"><div id="…-root">…</div>} 包装
     * （{@code LIBXML_HTML_NOIMPLIED | LIBXML_HTML_NODEFDTD} 的等价形态）。
     */
    private static String wrapRoot(String id, String html) {
        return "<div id=\"" + id + "\">" + html + "</div>";
    }

    /** {@code $inner($node)}：子节点的 HTML 串接。 */
    private static String inner(Element node) {
        StringBuilder sb = new StringBuilder();
        for (org.jsoup.nodes.Node child : node.childNodes()) {
            sb.append(child.outerHtml());
        }
        return sb.toString();
    }

    private void walk(Element node, List<Object> blocks) {
        for (org.jsoup.nodes.Node childNode : node.childNodes()) {
            if (blocks.size() >= 80) {
                break;
            }
            if (!(childNode instanceof Element child)) {
                String text = childNode instanceof TextNode textNode
                        ? textNode.getWholeText()
                        : childNode.outerHtml();
                if (!text.trim().isEmpty()) {
                    blocks.add(textBlock(text));
                }
                continue;
            }
            String cssClass = " " + child.attr("class") + " ";
            String tone = "info";
            var toneMatcher = IMPORT_TONE_PATTERN.matcher(cssClass);
            if (toneMatcher.find()) {
                tone = switch (toneMatcher.group(1)) {
                    case "red" -> "danger";
                    case "green" -> "success";
                    case "yellow" -> "warning";
                    case "cyan" -> "info";
                    default -> toneMatcher.group(1);
                };
            }
            if (cssClass.contains(" card ")) {
                Element body = child.getElementsByClass("card-body").first();
                Element header = child.getElementsByClass("card-header").first();
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "collapse");
                block.put("title", snip(header == null ? "折叠卡片" : header.text().trim(), 200));
                block.put("content", snip(body != null ? inner(body) : inner(child), 10000));
                block.put("tone", tone);
                block.put("expanded", body != null
                        && (" " + body.attr("class") + " ").contains(" show "));
                blocks.add(block);
            } else if (cssClass.contains(" alert ")) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "alert");
                block.put("content", snip(inner(child), 10000));
                block.put("tone", tone);
                blocks.add(block);
            } else if (HEADING_PATTERN.matcher(child.tagName()).matches()) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "heading");
                block.put("title", snip(child.text(), 200));
                blocks.add(block);
            } else if ("hr".equals(child.tagName())) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "divider");
                blocks.add(block);
            } else if ("div".equals(child.tagName())) {
                walk(child, blocks);
            } else {
                blocks.add(textBlock(snip(child.outerHtml(), 10000)));
            }
        }
    }

    private static Map<String, Object> textBlock(String content) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("content", content);
        return block;
    }

    /** {@code mb_substr($s, 0, $n)}。 */
    private static String snip(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max);
    }

    // ------------------------------------------------------------ 消毒

    /**
     * {@code sanitize($html, $prefix)}：白名单消毒 + id 前缀重写 + class/style
     * 过滤 + {@code a/button/img} 补属性 + {@code data-target} / {@code aria-controls}
     * / {@code href} 的 id 引用同步（首现映射，bug-for-bug）。
     */
    public String sanitize(String html, String prefix) {
        Document dom = Jsoup.parseBodyFragment(wrapRoot("safe-root", html));
        dom.outputSettings().prettyPrint(false);

        Element root = dom.getElementById("safe-root");
        if (root == null) {
            return "";
        }
        Map<String, String> idMap = new LinkedHashMap<>();
        int[] index = {0};
        clean(root, idMap, index, prefix);
        rewriteReferences(root, idMap);

        StringBuilder out = new StringBuilder();
        for (org.jsoup.nodes.Node child : root.childNodes()) {
            out.append(child.outerHtml());
        }
        return out.toString();
    }

    /** {@code $clean($node)} 的递归体。 */
    private void clean(Element parent, Map<String, String> idMap, int[] index, String prefix) {
        // 快照遍历（对应 PHP 的 iterator_to_array）： 子节点删除/解包不影响迭代
        List<org.jsoup.nodes.Node> children = new ArrayList<>(parent.childNodes());
        for (org.jsoup.nodes.Node childNode : children) {
            if (!(childNode instanceof Element child)) {
                if (childNode instanceof Comment) {
                    childNode.remove();
                }
                continue;
            }
            String tag = child.tagName();

            if (REMOVE_TAGS.contains(tag)) {
                childNode.remove();
                continue;
            }
            clean(child, idMap, index, prefix);

            if (!KEEP_TAGS.contains(tag)) {
                // 解包：子节点上提到同级，再移除标签壳（PHP 的 insertBefore 循环）
                for (org.jsoup.nodes.Node inner : new ArrayList<>(child.childNodes())) {
                    childNode.before(inner);
                }
                childNode.remove();
                continue;
            }
            filterAttributes(child, tag);

            if (child.hasAttr("id")) {
                String original = child.attr("id");
                String fresh = prefix + "-" + (++index[0]);
                idMap.putIfAbsent(original, fresh);
                child.attr("id", fresh);
            }
            if (child.hasAttr("class")) {
                List<String> keep = new ArrayList<>();
                for (String cls : child.attr("class").split("\\s+")) {
                    if (CLASS_PATTERN.matcher(cls).matches()) {
                        keep.add(cls);
                    }
                }
                child.attr("class", String.join(" ", keep));
            }
            if (child.hasAttr("style")) {
                StringBuilder kept = new StringBuilder();
                for (String rule : child.attr("style").split(";")) {
                    int colon = rule.indexOf(':');
                    if (colon < 0) {
                        continue;
                    }
                    String key = rule.substring(0, colon).trim();
                    String value = rule.substring(colon + 1).trim();
                    if (("font-weight".equals(key) && FONT_WEIGHT_PATTERN.matcher(value).matches())
                            || ("padding".equals(key) && PADDING_PATTERN.matcher(value).matches())) {
                        if (kept.length() > 0) {
                            kept.append(';');
                        }
                        kept.append(key).append(':').append(value);
                    }
                }
                child.attr("style", kept.toString());
            }
            if ("a".equals(tag)) {
                child.attr("rel", "noopener noreferrer");
            }
            if ("button".equals(tag)) {
                child.attr("type", "button");
            }
            if ("img".equals(tag)) {
                child.attr("class", "img-fluid");
            }
        }
    }

    /** 属性白名单（与 PHP 的 match 一一对应）。 */
    private static void filterAttributes(Element child, String tag) {
        List<Attribute> attributes = new ArrayList<>(child.attributes().asList());
        for (Attribute attribute : attributes) {
            String name = attribute.getKey().toLowerCase(java.util.Locale.ROOT);
            String value = attribute.getValue() == null ? "" : attribute.getValue();

            boolean keep;
            switch (name) {
                case "id" -> keep = ID_PATTERN.matcher(value).matches();
                case "class", "title", "alt", "style" -> keep = true;
                case "href" -> keep = "a".equals(tag) && HREF_PATTERN.matcher(value).find();
                case "src" -> keep = "img".equals(tag) && SRC_PATTERN.matcher(value).find();
                case "type" -> keep = "button".equals(tag) && "button".equals(value);
                case "data-toggle" -> keep = "collapse".equals(value);
                case "data-target" -> keep = DATA_TARGET_PATTERN.matcher(value).matches();
                case "aria-controls" -> keep = ARIA_CONTROLS_PATTERN.matcher(value).matches();
                case "aria-expanded" -> keep = "true".equals(value) || "false".equals(value);
                case "role" -> keep = "button".equals(value) || "alert".equals(value);
                case "open" -> keep = "details".equals(tag);
                default -> keep = false;
            }
            if (!keep) {
                child.removeAttr(name);
            }
        }
    }

    /**
     * id 引用同步（PHP 的 {@code $fix}）：
     * {@code aria-controls} 无井号、{@code data-target}/{@code href} 要求井号开头；
     * 引用不到就移除属性（data-target / aria-controls）或保持原样（href）。
     */
    private static void rewriteReferences(Element root, Map<String, String> idMap) {
        for (Element child : root.getAllElements()) {
            for (String name : List.of("data-target", "aria-controls", "href")) {
                if (!child.hasAttr(name)) {
                    continue;
                }
                String value = child.attr(name);
                String key = trimLeadingHash(value);
                boolean referenced = "aria-controls".equals(name) || value.startsWith("#");
                if (referenced && idMap.containsKey(key)) {
                    child.attr(name, ("aria-controls".equals(name) ? "" : "#")
                            + idMap.get(key));
                } else if ("data-target".equals(name) || "aria-controls".equals(name)) {
                    child.removeAttr(name);
                }
            }
        }
    }

    private static String trimLeadingHash(String value) {
        int i = 0;
        while (i < value.length() && value.charAt(i) == '#') {
            i++;
        }
        return value.substring(i);
    }
}
