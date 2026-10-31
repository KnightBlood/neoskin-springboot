package com.neoskin.web.pagination;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code Illuminate\Pagination\LengthAwarePaginator} + {@code UrlWindow} 的移植。
 *
 * <p>公告/站内信三个视图（{@code admin.communications}、{@code user.inbox}、
 * {@code shared/communication-pagination}）直接调用分页器的方法，所以这些方法的
 * <b>返回值形态</b>是视图契约的一部分，必须逐字对齐：</p>
 * <ul>
 *   <li>{@code currentPage()/lastPage()/total()/perPage()/firstItem()/lastItem()}；</li>
 *   <li>{@code onFirstPage()/hasMorePages()/hasPages()}；</li>
 *   <li>{@code previousPageUrl()/nextPageUrl()} —— 到边界时返回 <b>null</b>
 *       （视图用 {@code {% if %} } 判空，返回空串会渲染出 href=""）；</li>
 *   <li>{@code url($page)} / {@code getUrlRange($from, $to)} —— 页码 1 时
 *       <b>不带</b> {@code ?page=}（源 {@code UrlGenerator} 的行为）。</li>
 * </ul>
 *
 * <h2>页码 URL 的拼接</h2>
 * <p>{@code withQueryString()} 之后，每个页码 URL = 当前路径 + 原查询串（去掉
 * {@code page}）+ {@code page=N}。查询串里其它参数要保留并重新编码（源用
 * {@code Arr::query()}，即 RFC 3986 的 {@code http_build_query}）。</p>
 */
public final class LengthAwarePaginator<T> {

    private final List<T> items;
    private final int total;
    private final int perPage;
    private final int currentPage;

    /** 去掉 {@code page} 后的查询参数（保持插入顺序，值可能是 List）。 */
    private final Map<String, Object> query;

    private final String path;

    /** {@code onEachSide}（默认 3；源里 {@code ->onEachSide(1)}）。 */
    private int onEachSide = 3;

    private LengthAwarePaginator(List<T> items, int total, int perPage, int currentPage,
            Map<String, Object> query, String path) {
        this.items = items;
        this.total = total;
        this.perPage = perPage;
        this.currentPage = currentPage;
        this.query = query;
        this.path = path;
    }

    /**
     * {@code Model::paginate($perPage)}。
     *
     * @param items       当前页的数据
     * @param total       总条数
     * @param perPage     每页条数
     * @param currentPage 当前页（源从 {@code ?page=} 读，最小 1）
     * @param query       当前请求的查询参数（已去掉 {@code page}）
     * @param path        当前请求路径（不含查询串）
     */
    public static <T> LengthAwarePaginator<T> of(List<T> items, long total, int perPage,
            int currentPage, Map<String, Object> query, String path) {
        return new LengthAwarePaginator<>(new ArrayList<>(items), (int) total, perPage,
                Math.max(currentPage, 1), query == null ? Map.of() : query, path);
    }

    /** {@code ->onEachSide($n)}。 */
    public LengthAwarePaginator<T> onEachSide(int n) {
        this.onEachSide = n;
        return this;
    }

    /** {@code ->withQueryString()}：本类构造时就保留了查询串，这里是显式声明。 */
    public LengthAwarePaginator<T> withQueryString() {
        return this;
    }

    // ------------------------------------------------------------------ 数据

    public List<T> items() {
        return items;
    }

    /** 视图 {@code {% for item in messages %}} 用。 */
    public List<T> getItems() {
        return items;
    }

    /** {@code $messages->setCollection(...)}（收件箱按可见性过滤后回填）。 */
    public LengthAwarePaginator<T> withItems(List<T> replacement) {
        LengthAwarePaginator<T> copy = new LengthAwarePaginator<>(new ArrayList<>(replacement),
                total, perPage, currentPage, query, path);
        copy.onEachSide = onEachSide;
        return copy;
    }

    /** {@code ->count()}。 */
    public int count() {
        return items.size();
    }

    public int total() {
        return total;
    }

    public int perPage() {
        return perPage;
    }

    public int currentPage() {
        return currentPage;
    }

    /** {@code lastPage() = max((int) ceil(total / perPage), 1)}。 */
    public int lastPage() {
        if (perPage <= 0) {
            return 1;
        }
        int last = (int) Math.ceil(total / (double) perPage);
        return Math.max(last, 1);
    }

    /** {@code onFirstPage()}：当前页 &lt;= 1。 */
    public boolean onFirstPage() {
        return currentPage <= 1;
    }

    /** {@code hasMorePages()}：当前页 &lt; 最后一页。 */
    public boolean hasMorePages() {
        return currentPage < lastPage();
    }

    /** {@code hasPages()}：最后一页 &gt; 1。 */
    public boolean hasPages() {
        return lastPage() > 1;
    }

    /** {@code firstItem()}：无数据时返回 null。 */
    public Integer firstItem() {
        return total == 0 ? null : (currentPage - 1) * perPage + 1;
    }

    /** {@code lastItem()}：无数据时返回 null。 */
    public Integer lastItem() {
        if (total == 0) {
            return null;
        }
        return Math.min(total, currentPage * perPage);
    }

    // ------------------------------------------------------------------ URL

    /** {@code url($page)}：页码 1 不带 {@code ?page=}。 */
    public String url(int page) {
        if (page <= 1) {
            return path + queryString(null);
        }
        return path + queryString(page);
    }

    /** {@code previousPageUrl()}：第一页返回 null。 */
    public String previousPageUrl() {
        return onFirstPage() ? null : url(currentPage - 1);
    }

    /** {@code nextPageUrl()}：最后一页返回 null。 */
    public String nextPageUrl() {
        return hasMorePages() ? url(currentPage + 1) : null;
    }

    /** {@code getUrlRange($start, $end)}：{@code [page => url]}（有序）。 */
    public Map<Integer, String> getUrlRange(int start, int end) {
        Map<Integer, String> range = new LinkedHashMap<>();
        if (start < 1) {
            start = 1;
        }
        if (end > lastPage()) {
            end = lastPage();
        }
        for (int page = start; page <= end; page++) {
            range.put(page, url(page));
        }
        return range;
    }

    /** 拼接查询串：先原参数，再 {@code page}（页码 1 或 null 时不带）。 */
    private String queryString(Integer page) {
        Map<String, Object> merged = new LinkedHashMap<>(query);
        if (page != null && page > 1) {
            merged.put("page", page);
        }
        if (merged.isEmpty()) {
            return "";
        }
        return "?" + buildQuery(merged);
    }

    /**
     * {@code http_build_query()}（RFC 1738 编码，空格转 {@code +}）。
     *
     * <p>PHP 的 {@code http_build_query} 用 {@code PHP_QUERY_RFC1738}：
     * 空格 → {@code +}，其余按 {@code urlencode}。列表值展开成 {@code k[0]=v0&k[1]=v1}。</p>
     */
    static String buildQuery(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    append(sb, entry.getKey() + "[" + i + "]", list.get(i));
                }
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> inner : map.entrySet()) {
                    append(sb, entry.getKey() + "[" + inner.getKey() + "]", inner.getValue());
                }
            } else {
                append(sb, entry.getKey(), value);
            }
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String key, Object value) {
        if (sb.length() > 0) {
            sb.append('&');
        }
        sb.append(urlEncode(key)).append('=').append(urlEncode(value == null ? "" : String.valueOf(value)));
    }

    /** PHP {@code urlencode()}：字母数字与 {@code -_.} 之外全部百分号编码，空格转 {@code +}。 */
    private static String urlEncode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.') {
                sb.append((char) c);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ UrlWindow

    /**
     * {@code UrlWindow::make($paginator)} 的等价结果。
     *
     * <p>返回 {@code [first, slider, last]} 三段，每段是 {@code [页码 => url]} 或
     * null；视图按 {@code {% for segment in pagination %}{% if segment %}} 消费。</p>
     */
    /**
     * {@code $paginator->links()}：Laravel {@code bootstrap-4.blade.php} 的同构输出。
     *
     * <p>{@code elements()} 的组装规则：{@code [first]}、slider 为数组时插入
     * {@code '...'}、slider、last 为数组时插入 {@code '...'}、last。</p>
     */
    public String linksHtml() {
        if (!hasPages()) {
            return "";
        }
        List<Map<Integer, String>> window = urlWindow();
        StringBuilder html = new StringBuilder();
        html.append("<nav>\n        <ul class=\"pagination\">\n");

        if (onFirstPage()) {
            html.append("            <li class=\"page-item disabled\" aria-disabled=\"true\" "
                    + "aria-label=\"Previous\">\n                <span class=\"page-link\" "
                    + "aria-hidden=\"true\">&lsaquo;</span>\n            </li>\n");
        } else {
            html.append("            <li class=\"page-item\">\n                <a "
                    + "class=\"page-link\" href=\"").append(escapeHtml(previousPageUrl()))
                    .append("\" rel=\"prev\" aria-label=\"Previous\">&lsaquo;</a>\n"
                            + "            </li>\n");
        }

        appendPageRange(html, window.get(0));
        if (window.get(1) != null) {
            html.append("            <li class=\"page-item disabled\" aria-disabled=\"true\">"
                    + "<span class=\"page-link\">...</span></li>\n");
            appendPageRange(html, window.get(1));
        }
        if (window.get(2) != null) {
            html.append("            <li class=\"page-item disabled\" aria-disabled=\"true\">"
                    + "<span class=\"page-link\">...</span></li>\n");
            appendPageRange(html, window.get(2));
        }

        if (hasMorePages()) {
            html.append("            <li class=\"page-item\">\n                <a "
                    + "class=\"page-link\" href=\"").append(escapeHtml(nextPageUrl()))
                    .append("\" rel=\"next\" aria-label=\"Next\">&rsaquo;</a>\n"
                            + "            </li>\n");
        } else {
            html.append("            <li class=\"page-item disabled\" aria-disabled=\"true\" "
                    + "aria-label=\"Next\">\n                <span class=\"page-link\" "
                    + "aria-hidden=\"true\">&rsaquo;</span>\n            </li>\n");
        }

        html.append("        </ul>\n    </nav>");

        return html.toString();
    }

    /**
     * {@code LengthAwarePaginator::toArray()['links']}：与 Laravel 一致的
     * {@code [{url,label,active}]}（previous / 页码窗口 / next，省略处为
     * {@code label="..."} 且 {@code url=null}）。
     *
     * <p>页码窗口走 {@link #urlWindow()}（onEachSide 默认 3）；URL 总是带
     * {@code ?page=N}（Laravel {@code url($page)} 的行为，与视图 links 不同）。</p>
     */
    public List<Map<String, Object>> links() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(link(currentPage > 1 ? jsonPageUrl(currentPage - 1) : null,
                "&laquo; Previous", false));

        List<Map<Integer, String>> window = urlWindow();
        if (window.get(0) != null) {
            appendLinks(out, window.get(0));
        }
        if (window.get(1) != null) {
            out.add(link(null, "...", false));
            appendLinks(out, window.get(1));
        }
        if (window.get(2) != null) {
            out.add(link(null, "...", false));
            appendLinks(out, window.get(2));
        }

        out.add(link(currentPage < lastPage() ? jsonPageUrl(currentPage + 1) : null,
                "Next &raquo;", false));

        return out;
    }

    private void appendLinks(List<Map<String, Object>> out, Map<Integer, String> range) {
        for (Integer page : range.keySet()) {
            out.add(link(jsonPageUrl(page), String.valueOf(page), page == currentPage));
        }
    }

    private static Map<String, Object> link(String url, String label, boolean active) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("url", url);
        item.put("label", label);
        item.put("active", active);

        return item;
    }

    private String jsonPageUrl(int page) {
        return path + "?page=" + page;
    }

    private void appendPageRange(StringBuilder html, Map<Integer, String> range) {
        if (range == null) {
            return;
        }
        for (Map.Entry<Integer, String> entry : range.entrySet()) {
            if (entry.getKey() == currentPage()) {
                html.append("            <li class=\"page-item active\" aria-current=\"page\">"
                        + "<span class=\"page-link\">").append(entry.getKey())
                        .append("</span></li>\n");
            } else {
                html.append("            <li class=\"page-item\"><a class=\"page-link\" href=\"")
                        .append(escapeHtml(entry.getValue())).append("\">")
                        .append(entry.getKey()).append("</a></li>\n");
            }
        }
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }

        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public List<Map<Integer, String>> urlWindow() {
        List<Map<Integer, String>> window = new ArrayList<>();

        if (lastPage() < onEachSide * 2 + 8) {
            window.add(getUrlRange(1, lastPage()));
            window.add(null);
            window.add(null);
            return window;
        }

        int width = onEachSide + 4;

        if (!hasPages()) {
            window.add(null);
            window.add(null);
            window.add(null);
            return window;
        }

        if (currentPage() <= width) {
            window.add(getUrlRange(1, width + onEachSide));
            window.add(null);
            window.add(getUrlRange(lastPage() - 1, lastPage()));
            return window;
        }

        if (currentPage() > lastPage() - width) {
            window.add(getUrlRange(1, 2));
            window.add(null);
            window.add(getUrlRange(lastPage() - (width + (onEachSide - 1)), lastPage()));
            return window;
        }

        window.add(getUrlRange(1, 2));
        window.add(getUrlRange(currentPage() - onEachSide, currentPage() + onEachSide));
        window.add(getUrlRange(lastPage() - 1, lastPage()));
        return window;
    }
}
