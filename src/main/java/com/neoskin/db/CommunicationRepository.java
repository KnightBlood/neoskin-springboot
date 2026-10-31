package com.neoskin.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import com.neoskin.support.PhpDate;
import com.neoskin.support.PhpJson;

/**
 * {@code communications} 表的读写（对应 {@code App\Models\Communication}）。
 *
 * <p>模型契约（源 {@code Communication.php}，{@code $guarded = []}）：</p>
 * <ul>
 *   <li>{@code $casts = ['blocks' => 'array', 'audience' => 'array',
 *       'published_at' => 'datetime']} —— blocks / audience 是 JSON 列；列上的
 *       NULL 保持为 null（Laravel 的 array cast 对 null 仍返回 null）；</li>
 *   <li>Eloquent 自动时间戳：insert 写 {@code created_at} + {@code updated_at}，
 *       update 只刷新 {@code updated_at}，格式 {@code Y-m-d H:i:s}，时区取
 *       {@code config('app.timezone')}（本工程为 {@code settings.getTimezone()}）。</li>
 * </ul>
 */
public final class CommunicationRepository {

    /** {@code kind} 的取值（校验规则 {@code in:announcement,message,both}）。 */
    public static final String KIND_ANNOUNCEMENT = "announcement";

    public static final String KIND_MESSAGE = "message";

    public static final String KIND_BOTH = "both";

    /** {@code status} 的取值。 */
    public static final String STATUS_DRAFT = "draft";

    public static final String STATUS_PUBLISHED = "published";

    public static final String STATUS_WITHDRAWN = "withdrawn";

    /** {@code communications} 的一行（已应用 casts）。 */
    public static final class Row {

        public long id;

        public Integer authorId;

        public String legacyKey;

        public String deliveryToken;

        public String title;

        public String kind;

        public String status;

        public String locale;

        public String editor;

        public String source;

        public Object blocks;

        public Object audience;

        public String publishedAt;

        public String createdAt;

        public String updatedAt;

        /** {@code $communication->toArray()}（渲染器的输入就是它）。 */
        public java.util.Map<String, Object> toArray() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("id", id);
            map.put("author_id", authorId);
            map.put("legacy_key", legacyKey);
            map.put("delivery_token", deliveryToken);
            map.put("title", title);
            map.put("kind", kind);
            map.put("status", status);
            map.put("locale", locale);
            map.put("editor", editor);
            map.put("source", source);
            map.put("blocks", blocks);
            map.put("audience", audience);
            map.put("published_at", publishedAt);
            map.put("created_at", createdAt);
            map.put("updated_at", updatedAt);
            return map;
        }
    }

    /** 读列及其顺序（{@link #map} 里的下标必须与之对应）。 */
    private static final String[] COLUMNS = {"id", "author_id", "legacy_key", "delivery_token",
            "title", "kind", "status", "locale", "editor", "source", "blocks", "audience",
            "published_at", "created_at", "updated_at"};

    private final DataSource dataSource;

    private final SchemaInstaller schema;

    private final String timezone;

    public CommunicationRepository(DataSource dataSource, SchemaInstaller schema, String timezone) {
        this.dataSource = dataSource;
        this.schema = schema;
        this.timezone = timezone;
    }

    /** 可见的方言（{@code synchronize} 侧的 JSON 路径查询要跨方言）。 */
    public SqlDialect dialect() {
        return schema.dialect();
    }

    private String q(String identifier) {
        return schema.dialect().quote(identifier);
    }

    private String table() {
        return schema.table("communications");
    }

    /** 服务层自定义查询要拼的表名（含前缀）。 */
    public String tableName() {
        return table();
    }

    /** 服务层自定义查询要拼的标识符引号形式。 */
    public String quote(String identifier) {
        return q(identifier);
    }

    /** {@link #map} 的公开别名（服务层自己发 SQL 时复用同一套 casts）。 */
    public Row mapRow(ResultSet rs) throws SQLException {
        return map(rs);
    }

    private String select() {
        return select(false);
    }

    /** SQL Server 的单行查询：{@code SELECT TOP 1 ...}。 */
    private String selectFirst() {
        return select(true);
    }

    private String select(boolean firstRow) {
        StringBuilder sb = new StringBuilder("SELECT ");
        if (firstRow) {
            sb.append(dialect().firstRowPrefix());
        }
        for (int i = 0; i < COLUMNS.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(q(COLUMNS[i]));
        }
        return sb.append(" FROM ").append(table()).toString();
    }

    // ------------------------------------------------------------------ 读

    /** {@code Communication::find($id)}（{@code findOrFail} 的底层）。 */
    public Row find(long id) throws SQLException {
        String sql = select() + " WHERE " + q("id") + " = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    /**
     * {@code Communications::currentAnnouncement()} 的单候选查询：
     * 给定 locale，取「announcement，或（both 且 published）」里
     * {@code updated_at DESC, kind ASC, id DESC} 的第一行。
     *
     * <p>服务层循环 {@code [appLocale, '']} 两个候选 locale，先命中先返回。</p>
     */
    public Row findCurrent(String locale) throws SQLException {
        String sql = selectFirst()
                + " WHERE " + q("locale") + " = ? AND ("
                + q("kind") + " = ? OR ("
                + q("kind") + " = ? AND " + q("status") + " = ?))"
                + " ORDER BY " + q("updated_at") + " DESC, "
                + q("kind") + " ASC, " + q("id") + " DESC"
                + dialect().firstRowSuffix();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, locale);
            stmt.setString(2, KIND_ANNOUNCEMENT);
            stmt.setString(3, KIND_BOTH);
            stmt.setString(4, STATUS_PUBLISHED);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    /**
     * 管理端列表页内数据：{@code whereIn('kind', ['message','both'])
     * ->orderByDesc('id')->paginate($perPage)->items()}。
     */
    public List<Row> messagePage(long limit, long offset) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            String sql = select() + " WHERE " + q("kind") + " IN (?, ?)"
                    + " ORDER BY " + q("id") + " DESC" + Paging.clause(conn);
            List<Row> out = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, KIND_MESSAGE);
                stmt.setString(2, KIND_BOTH);
                Paging.bind(conn, stmt, 3, limit, offset);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            }
            return out;
        }
    }

    /** 同列表的总数（{@code paginate} 的 count 查询）。 */
    public long countMessages() throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table() + " WHERE " + q("kind") + " IN (?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, KIND_MESSAGE);
            stmt.setString(2, KIND_BOTH);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /**
     * {@code Communications::visible()} 的查体：
     *
     * <pre>
     * Communication::whereKey($id)-&gt;where('status','published')
     *   -&gt;whereIn('kind',['message','both'])
     *   -&gt;where('delivery_token', $token ?? null)-&gt;exists();
     * </pre>
     *
     * <p>注意 {@code where('col', null)} 在 Laravel 里会退化成 {@code whereNull}，
     * 所以「双方都没有 token」的旧数据（{@code delivery_token} 为 NULL）同样可见。</p>
     */
    public boolean existsVisible(long id, String deliveryToken) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table()
                + " WHERE " + q("id") + " = ?"
                + " AND " + q("status") + " = ?"
                + " AND " + q("kind") + " IN (?, ?)"
                + " AND " + (deliveryToken == null
                        ? q("delivery_token") + " IS NULL"
                        : q("delivery_token") + " = ?");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            int i = 1;
            stmt.setLong(i++, id);
            stmt.setString(i++, STATUS_PUBLISHED);
            stmt.setString(i++, KIND_MESSAGE);
            stmt.setString(i++, KIND_BOTH);
            if (deliveryToken != null) {
                stmt.setString(i, deliveryToken);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }

    /** {@code Communication::whereKey($id)->value('title')}（没有则 null）。 */
    public String findTitle(long id) throws SQLException {
        String sql = "SELECT " + q("title") + " FROM " + table() + " WHERE " + q("id") + " = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ------------------------------------------------------------------ 写

    /**
     * {@code $message->save()}：insert（{@code id == 0}）或 update。
     *
     * <p>Eloquent 时间戳语义：insert 时 {@code created_at} 与 {@code updated_at}
     * 同时写；update 时只刷新 {@code updated_at}。要自定义时间戳（例如
     * {@code published_at} 已定稿），调用方先自行填 {@code updatedAt}。</p>
     *
     * @return 生效的 {@code id}（新插入返回自增值，写回 {@code row.id}）
     */
    public Row save(Row row) throws SQLException {
        boolean insert = row.id == 0;
        if (row.updatedAt == null || row.updatedAt.isEmpty()) {
            row.updatedAt = PhpDate.now(timezone);
        }
        if (insert) {
            if (row.createdAt == null || row.createdAt.isEmpty()) {
                row.createdAt = row.updatedAt;
            }
            insertRow(row);
        } else {
            updateRow(row);
        }
        return row;
    }

    private void insertRow(Row row) throws SQLException {
        String sql = "INSERT INTO " + table() + " ("
                + q("author_id") + ", " + q("legacy_key") + ", " + q("delivery_token") + ", "
                + q("title") + ", " + q("kind") + ", " + q("status") + ", " + q("locale") + ", "
                + q("editor") + ", " + q("source") + ", " + q("blocks") + ", " + q("audience") + ", "
                + q("published_at") + ", " + q("created_at") + ", " + q("updated_at")
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setObject(1, row.authorId);
            stmt.setString(2, row.legacyKey);
            stmt.setString(3, row.deliveryToken);
            stmt.setString(4, row.title);
            stmt.setString(5, row.kind);
            stmt.setString(6, row.status);
            stmt.setString(7, row.locale);
            stmt.setString(8, row.editor);
            stmt.setString(9, row.source);
            stmt.setString(10, json(row.blocks));
            stmt.setString(11, json(row.audience));
            stmt.setString(12, row.publishedAt);
            stmt.setString(13, row.createdAt);
            stmt.setString(14, row.updatedAt);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                row.id = keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    private void updateRow(Row row) throws SQLException {
        String sql = "UPDATE " + table() + " SET "
                + q("author_id") + " = ?, " + q("legacy_key") + " = ?, "
                + q("delivery_token") + " = ?, " + q("title") + " = ?, "
                + q("kind") + " = ?, " + q("status") + " = ?, " + q("locale") + " = ?, "
                + q("editor") + " = ?, " + q("source") + " = ?, " + q("blocks") + " = ?, "
                + q("audience") + " = ?, " + q("published_at") + " = ?, "
                + q("updated_at") + " = ?"
                + " WHERE " + q("id") + " = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, row.authorId);
            stmt.setString(2, row.legacyKey);
            stmt.setString(3, row.deliveryToken);
            stmt.setString(4, row.title);
            stmt.setString(5, row.kind);
            stmt.setString(6, row.status);
            stmt.setString(7, row.locale);
            stmt.setString(8, row.editor);
            stmt.setString(9, row.source);
            stmt.setString(10, json(row.blocks));
            stmt.setString(11, json(row.audience));
            stmt.setString(12, row.publishedAt);
            stmt.setString(13, row.updatedAt);
            stmt.setLong(14, row.id);
            stmt.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 撤回

    /**
     * {@code withdraw} 的级联撤回：
     * {@code Communication::where('kind','announcement')
     * ->where('audience->communication_id', $message->id)
     * ->update(['status' => 'withdrawn'])}。
     */
    public void withdrawAnnouncementsOfOrigin(long originId) throws SQLException {
        SqlDialect dialect = schema.dialect();
        String audienceKey = dialect.castText(dialect.jsonText(q("audience"), "communication_id"));

        String sql = "UPDATE " + table() + " SET " + q("status") + " = ?"
                + " WHERE " + q("kind") + " = ?"
                + " AND " + audienceKey + " = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, STATUS_WITHDRAWN);
            stmt.setString(2, KIND_ANNOUNCEMENT);
            stmt.setString(3, String.valueOf(originId));
            stmt.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** cast 后的 JSON 文本（null → SQL null）。 */
    private static String json(Object value) {
        return value == null ? null : PhpJson.encode(value);
    }

    private Row map(ResultSet rs) throws SQLException {
        Row row = new Row();
        row.id = rs.getLong(1);
        long author = rs.getLong(2);
        row.authorId = rs.wasNull() ? null : (int) author;
        row.legacyKey = rs.getString(3);
        row.deliveryToken = rs.getString(4);
        row.title = rs.getString(5);
        row.kind = rs.getString(6);
        row.status = rs.getString(7);
        row.locale = rs.getString(8);
        row.editor = rs.getString(9);
        row.source = rs.getString(10);
        row.blocks = decoded(rs.getString(11));
        row.audience = decoded(rs.getString(12));
        row.publishedAt = rs.getString(13);
        row.createdAt = rs.getString(14);
        row.updatedAt = rs.getString(15);
        return row;
    }

    /** casts：{@code castAttribute => !is_null($value) ? json_decode($value, true) : null}。 */
    private static Object decoded(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        return PhpJson.decode(text);
    }
}
