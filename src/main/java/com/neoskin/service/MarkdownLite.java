package com.neoskin.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 GFM 子集渲染器。
 *
 * <p>对应源里 {@code League\CommonMark\GithubFlavoredMarkdownConverter}
 * 在「内容策略文案」这类富文本块上的用法：管理员文案一般是标题、段落、
 * 列表、强调、行内代码、链接、引用、分隔线的组合。支持这些常用子集，
 * 未覆盖的语法按纯文本处理（见 PORT-NOTES）。</p>
 */
public final class MarkdownLite {

    private MarkdownLite() {
    }

    /** 渲染为 HTML 片段（{@code getContent()}）。 */
    public static String render(String md) {
        if (md == null || md.isEmpty()) {
            return "";
        }

        List<String> lines = java.util.Arrays.asList(
                md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        StringBuilder html = new StringBuilder();
        List<String> para = new ArrayList<>();
        boolean inFence = false;
        List<String> fenceLines = new ArrayList<>();
        String listKind = null;
        List<String> listItems = new ArrayList<>();

        for (String raw : lines) {
            if (inFence) {
                if (raw.matches("^\\s*`{3,}\\s*$") || raw.matches("^\\s*~{3,}\\s*$")) {
                    html.append("<pre><code>")
                            .append(escape(String.join("\n", fenceLines)))
                            .append("</code></pre>\n");
                    inFence = false;
                    fenceLines.clear();
                } else {
                    fenceLines.add(raw);
                }
                continue;
            }

            String line = raw;
            String trimmed = line == null ? "" : line.trim();

            // 围栏代码块
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                flushParagraph(html, para);

                if (listKind != null) {
                    flushList(html, listItems, listKind);
                    listKind = null;
                }

                inFence = true;
                continue;
            }

            if (trimmed.isEmpty()) {
                flushParagraph(html, para);

                if (listKind != null) {
                    flushList(html, listItems, listKind);
                    listKind = null;
                }
                continue;
            }

            // ATX 标题
            if (trimmed.matches("#{1,6}\\s.*") || trimmed.matches("#{1,6}")) {
                flushParagraph(html, para);

                int level = trimmed.indexOf(' ') >= 0
                        ? Math.min(6, trimmed.indexOf(' '))
                        : Math.min(6, trimmed.length());
                html.append("<h").append(level).append(">")
                        .append(inline(trimmed.replaceFirst("^#{1,6}\\s*", "")))
                        .append("</h").append(level).append(">\n");
                continue;
            }

            // 分隔线
            if (trimmed.matches("(?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,}")) {
                flushParagraph(html, para);

                if (listKind != null) {
                    flushList(html, listItems, listKind);
                    listKind = null;
                }
                html.append("<hr />\n");
                continue;
            }

            // 引用
            if (trimmed.startsWith(">")) {
                flushParagraph(html, para);

                if (listKind != null) {
                    flushList(html, listItems, listKind);
                    listKind = null;
                }
                html.append("<blockquote>\n<p>")
                        .append(inline(trimmed.replaceFirst("^>\\s*", "")))
                        .append("</p>\n</blockquote>\n");
                continue;
            }

            // 列表
            Matcher ul = Pattern.compile("[-*+]\\s+(.*)").matcher(trimmed);
            Matcher ol = Pattern.compile("\\d+\\.\\s+(.*)").matcher(trimmed);

            if (ul.matches() || ol.matches()) {
                flushParagraph(html, para);

                if (listKind == null) {
                    listKind = ul.matches() ? "ul" : "ol";
                }

                listItems.add(ul.matches() ? ul.group(1) : ol.group(1));
                continue;
            }

            if (listKind != null) {
                // 列表项延续行
                listItems.set(listItems.size() - 1,
                        listItems.get(listItems.size() - 1) + " " + trimmed);
                continue;
            }

            para.add(trimmed);
        }

        flushParagraph(html, para);

        if (listKind != null) {
            flushList(html, listItems, listKind);
        }

        return html.toString();
    }

    private static void flushParagraph(StringBuilder html, List<String> para) {
        if (para.isEmpty()) {
            return;
        }

        html.append("<p>").append(inline(String.join("\n", para))).append("</p>\n");
        para.clear();
    }

    private static void flushList(StringBuilder html, List<String> items, String kind) {
        if (items.isEmpty()) {
            return;
        }

        html.append("<").append(kind).append(">\n");

        for (String item : items) {
            html.append("<li>").append(inline(item)).append("</li>\n");
        }

        html.append("</").append(kind).append(">\n");
        items.clear();
    }

    private static String inline(String text) {
        String s = escape(text);
        // 行内代码
        s = Pattern.compile("`([^`]+)`").matcher(s).replaceAll(m -> "<code>"
                + m.group(1) + "</code>");
        // 图片（先于链接）
        s = Pattern.compile("!\\[([^\\]]*)]\\(([^)\\s]+)\\)").matcher(s).replaceAll(
                m -> "<img src=\"" + m.group(2) + "\" alt=\"" + m.group(1)
                        + "\" />");
        // 链接
        s = Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]+)\\)").matcher(s).replaceAll(
                m -> "<a href=\"" + m.group(2) + "\">" + m.group(1) + "</a>");
        // 粗体 / 斜体
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        s = s.replaceAll("__(.+)__", "<strong>$1</strong>");
        s = s.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<em>$1</em>");
        s = s.replaceAll("(?<!_)_([^_]+)_(?!_)", "<em>$1</em>");
        // 删除线
        s = s.replaceAll("~~(.+)~~", "<del>$1</del>");
        // 硬换行（行尾双空格或反斜杠）
        s = Pattern.compile("(  +|\\\\)\\n").matcher(s).replaceAll(m ->
                (m.group(1).startsWith("\\") ? "" : "<br />") + "\n");
        // Windows 行尾残留
        s = s.replace("\r", "");

        return s;
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }

        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
