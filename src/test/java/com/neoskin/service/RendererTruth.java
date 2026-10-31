package com.neoskin.service;

/** PHP 实测输出（CommunicationRenderer ground truth）。 */
final class RendererTruth {
    private RendererTruth() {
    }

    static final String EXPECTED_HEADING = "<h2>标题</h2>\n<p>正文 <strong>粗体</strong> <em>斜体</em>。</p>\n<ul>\n<li>列表一</li>\n<li>列表二</li>\n</ul>\n";
    static final String EXPECTED_LINK = "<p><a href=\"https://e.example.com/a?b=1&amp;c=2\" rel=\"noopener noreferrer\">E negli angluars</a> 及 <a rel=\"noopener noreferrer\">bad</a> 与 <a rel=\"noopener noreferrer\">data</a> 端 <a rel=\"noopener noreferrer\">file</a>。</p>\n";
    static final String EXPECTED_AUTOLINK = "<p>访问 <a href=\"https://a.example.com\" rel=\"noopener noreferrer\">https://a.example.com</a> 或测试邮箱 <a href=\"mailto:a@b.com\" rel=\"noopener noreferrer\">a@b.com</a>。</p>\n";
    static final String EXPECTED_TABLE = "<table>\n<thead>\n<tr>\n<th>A</th>\n<th>B</th>\n</tr>\n</thead>\n<tbody>\n<tr>\n<td>1</td>\n<td>2</td>\n</tr>\n</tbody>\n</table>\n";
    static final String EXPECTED_STRIKETHROUGH = "<p><del>没了</del> 与 <code>code</code> 及<code>多行</code>。</p>\n";
    static final String EXPECTED_TASKLIST = "<ul>\n<li> 待办</li>\n<li> 完成</li>\n</ul>\n";
    static final String EXPECTED_RAWHTML = "&lt;script&gt;alert(1)&lt;/script&gt;\n&lt;script type=\"text/javascript\"&gt;var a=1;&lt;/script&gt;\n<div>ok</div>\n<b>bold</b>\n&lt;TEXTAREA&gt;x&lt;/TEXTAREA&gt;\n";
    static final String EXPECTED_BLOCKQUOTE = "<blockquote>\n<p>引</p>\n</blockquote>\n<pre><code>code\n</code></pre>\n<hr>\n";
    static final String EXPECTED_IMAGE = "<p><img src=\"https://img.example.com/a.png\" alt=\"图\" class=\"img-fluid\"> <img alt=\"data图\" class=\"img-fluid\"></p>\n";

    static final String EXPECTED_BLOCKS = "<p>纯 <strong>文本</strong>\n第二行</p>\n<h3>含&amp;与\"引号\"与&lt;'撇&gt;</h3><div class=\"alert alert-danger\"><p>提示内容</p>\n</div><div class=\"card card-outline card-warning\"><div class=\"card-header\"><button type=\"button\" class=\"btn btn-link text-left p-0\" data-toggle=\"collapse\" data-target=\"#pfx-1\" aria-expanded=\"true\">折叠</button></div><div id=\"pfx-1\" class=\"card-body collapse show\"><p>折叠内容</p>\n</div></div><hr><div class=\"alert alert-info\"><p>无tone</p>\n</div>";

    static final String IMPORT_0 = "[{\"type\":\"heading\",\"title\":\"标题\"},{\"type\":\"text\",\"content\":\"<p>段落 <b>粗<\\/b><\\/p>\"},{\"type\":\"alert\",\"content\":\"红色提示\",\"tone\":\"danger\"}]";

    static final String IMPORT_1 = "[{\"type\":\"collapse\",\"title\":\"折叠标题\",\"content\":\"<p>内文<\\/p>\",\"tone\":\"primary\",\"expanded\":true}]";
}
