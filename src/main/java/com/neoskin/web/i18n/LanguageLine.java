package com.neoskin.web.i18n;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.sql.DataSource;

import com.neoskin.support.PhpDate;
import com.neoskin.support.PhpJson;

/**
 * {@code Spatie\TranslationLoader\LanguageLine} 的写侧移植
 * （读侧在 {@link DbTranslationLoader}）。
 *
 * <p>覆盖 admin 翻译管理（{@code TranslationsController}）用到的模型操作，
 * 语义逐条对齐源模型：</p>
 *
 * <ul>
 *   <li>{@code text} 是 Eloquent {@code 'array'} cast —— 以 JSON 对象存取，
 *       {@code getTranslation} 走 {@code text[$locale] ?? text[fallback] ?? null}；</li>
 *   <li>{@code setTranslation($locale, $value)} = {@code array_merge($text, [$locale => $value])}
 *       —— 已有 locale 覆盖、新 locale 追加到末尾（键序就是 array_merge 语义）；</li>
 *   <li>{@code boot()} 里的模型事件：{@code saved} / {@code deleted} 都触发
 *       {@code flushGroupCache()}（∅脏检查：即便 {@code save()} 没有实际 SQL，
 *       Laravel 也会触发 {@code saved} 事件 —— 这里同样无条件 flush）；</li>
 *   <li>{@code flushGroupCache()} 对该行 {@code text} 里出现过的每个 locale 调
 *       {@code Cache::forget("spatie.translation-loader.{$group}.{$locale}")}；
 *       Java 侧 {@link DbTranslationLoader#invalidateGroup(String)} 按 group 整组失效，
 *       是 forget 的超集（结果等价：下一次按组读取会重建缓存）。</li>
 * </ul>
 *
 * <p>时间戳：Eloquent 自动维护 {@code created_at} / {@code updated_at}（
 * {@code Y-m-d H:i:s}、应用时区，见 {@link PhpDate}）。INSERT 两个都写，
 * UPDATE 只动 {@code updated_at}。</p>
 */
public final class LanguageLine {

    private int id;

    private String group;

    private String key;

    /** {@code text} 列的反序列化形态（保序 {\code locale → value}）。 */
    private Map<String, String> text = new LinkedHashMap<>();

    private String createdAt;

    private String updatedAt;

    public int getId() {
        return id;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Map<String, String> getText() {
        return text;
    }

    public void setText(Map<String, String> text) {
        this.text = text;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    // ================================================================= 查询

    /**
     * 路由模型绑定：按主键取一行；不存在返回 {@code null}
     * （Laravel 的 implicit binding 会进 404）。
     */
    public static LanguageLine find(DataSource dataSource, int id) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT id, " + com.neoskin.db.Paging.dialectOf(conn).quote("group")
                    + ", " + com.neoskin.db.Paging.dialectOf(conn).quote("key")
                    + ", text, created_at, updated_at"
                    + " FROM language_lines WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, id);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? read(rs) : null;
                }
            }
        }
    }

    /**
     * {@code LanguageLine::paginate(10)} 的数据部分：
     * {@code count(*)} 为 total、{@code LIMIT 10 OFFSET (page-1)*10}。
     * 页码从 1 开始（Laravel 语义）；低于 1 按 1 处理。
     */
    public static Page list(DataSource dataSource, int page) throws SQLException {
        int current = Math.max(page, 1);
        int perPage = 10;
        int total;
        List<LanguageLine> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            var dialect = com.neoskin.db.Paging.dialectOf(conn);
            try (Statement st = conn.createStatement()) {
                // count(id)：Eloquent paginator 对 paginate() 默认查询 SELECT count(*)
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM language_lines")) {
                    rs.next();
                    total = rs.getInt(1);
                }
            }
            String sql = "SELECT id, " + dialect.quote("group") + ", " + dialect.quote("key")
                    + ", text, created_at, updated_at"
                    + " FROM language_lines ORDER BY id" + dialect.pagingClause();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                dialect.bindPaging(stmt, 1, perPage, (current - 1) * perPage);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        rows.add(read(rs));
                    }
                }
            }
        }
        return new Page(total, current, perPage, rows);
    }

    private static LanguageLine read(ResultSet rs) throws SQLException {
        LanguageLine line = new LanguageLine();
        line.id = rs.getInt(1);
        line.group = rs.getString(2);
        line.key = rs.getString(3);
        // text 列的 array cast：合法 JSON → Map；非法（历史数据损坏）→ 空表，
        // Eloquent 此时会在读取时把字段留在原字符串上，admin 面板按空行走
        Map<String, String> decoded = decodeText(rs.getString(4));
        if (decoded != null) {
            line.text = decoded;
        } else {
            line.text = new LinkedHashMap<>();
        }
        line.createdAt = rs.getString(5);
        line.updatedAt = rs.getString(6);
        return line;
    }

    /** {@code decodeText} 的共享实现（与 {@link DbTranslationLoader} 同语义）。 */
    private static Map<String, String> decodeText(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        Object decoded;
        try {
            decoded = PhpJson.decode(json);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(decoded instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getValue() != null) {
                out.put(String.valueOf(e.getKey()),
                        e.getValue() instanceof String s ? s : String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    // ================================================================= 模型语义

    /**
     * {@code getTranslation($locale)}：text[$locale] ?? text[fallback] ?? null。
     */
    public String getTranslation(String locale, String fallbackLocale) {
        if (text.containsKey(locale)) {
            return text.get(locale);
        }
        return text.get(fallbackLocale);
    }

    /**
     * {@code setTranslation($locale, $value)} = {@code array_merge($text, [...])}。
     */
    public void setTranslation(String locale, String value) {
        text.put(locale, value);
    }

    /**
     * {@code save()}：按 {@code id > 0 ? UPDATE : INSERT}。无论 SQL 是否真的
     * 执行（对应 Laravel 没脏字段时也触发 {@code saved} 事件），都 flush 组缓存。
     *
     * @param flush 缓存失效钩子（连 {@link DbTranslationLoader}）
     */
    public void save(DataSource dataSource, String timezone, Consumer<String> flush)
            throws SQLException {
        String now = PhpDate.now(timezone);
        if (id > 0) {
            try (Connection conn = dataSource.getConnection()) {
                var dialect = com.neoskin.db.Paging.dialectOf(conn);
                String sql = "UPDATE language_lines SET " + dialect.quote("group") + " = ?, "
                        + dialect.quote("key") + " = ?, text = ?,"
                        + " updated_at = ? WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, group);
                    stmt.setString(2, key);
                    stmt.setString(3, PhpJson.encode(text));
                    stmt.setString(4, now);
                    stmt.setInt(5, id);
                    stmt.executeUpdate();
                }
            }
            updatedAt = now;
        } else {
            try (Connection conn = dataSource.getConnection()) {
                var dialect = com.neoskin.db.Paging.dialectOf(conn);
                String sql = "INSERT INTO language_lines (" + dialect.quote("group") + ", "
                        + dialect.quote("key") + ", text, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(sql,
                        PreparedStatement.RETURN_GENERATED_KEYS)) {
                    stmt.setString(1, group);
                    stmt.setString(2, key);
                    stmt.setString(3, PhpJson.encode(text));
                    stmt.setString(4, now);
                    stmt.setString(5, now);
                    stmt.executeUpdate();
                    try (ResultSet keys = stmt.getGeneratedKeys()) {
                        if (keys.next()) {
                            id = keys.getInt(1);
                        }
                    }
                }
            }
            createdAt = now;
            updatedAt = now;
        }
        flushGroupCache(flush);
    }

    /**
     * {@code delete()}：主键删除 + {@code deleted} 事件 → flush 组缓存。
     */
    public void delete(DataSource dataSource, Consumer<String> flush) throws SQLException {
        if (id > 0) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "DELETE FROM language_lines WHERE id = ?")) {
                stmt.setInt(1, id);
                stmt.executeUpdate();
            }
        }
        flushGroupCache(flush);
    }

    /**
     * {@code flushGroupCache()}：源对该行 text 里出现过的每个 locale 逐个
     * forget {@code spatie.translation-loader.{group}.{locale}}；Java 侧
     * {@link DbTranslationLoader#invalidateGroup(String)} 按 group 整组失效，
     * 效果一致且更简单（下次按组读取重建）。locale 先前在 text 里的痕迹
     * 修改后可能消失，这种「旧 locale 的失效」源 API 恰好也覆盖不到
     * （flush 用的是新 text），保持的范围一致。
     */
    void flushGroupCache(Consumer<String> flush) {
        if (group != null) {
            flush.accept(group);
        }
    }

    // ================================================================= 分页容器

    /**
     * {@code LengthAwarePaginator} 的数据子集（true JSON 形状由控制器层组装，
     * 这里只携带对账所需的字段）。
     */
    public record Page(int total, int currentPage, int perPage, List<LanguageLine> rows) {

        /** {@code lastPage} = ceil(total / perPage)，至少 1。 */
        public int lastPage() {
            return Math.max((int) Math.ceil((double) total / perPage), 1);
        }

        /** {@code from}（1 起）／首尾页无值时为 0。 */
        public int from() {
            return total == 0 ? 0 : (currentPage - 1) * perPage + 1;
        }

        public int to() {
            return Math.min(currentPage * perPage, total);
        }
    }
}
