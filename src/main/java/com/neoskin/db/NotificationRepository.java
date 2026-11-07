package com.neoskin.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.neoskin.support.PhpJson;

/**
 * {@code notifications} 表的读写（Laravel DatabaseNotification 通道的存储）。
 *
 * <p>行契约（源 {@code 2019_07_03_094434_create_notifications_table.php} +
 * {@code Illuminate\Notifications\DatabaseNotification}）：</p>
 * <ul>
 *   <li>{@code id} 是 <b>带连字符</b>的 UUIDv4 字符串（Ramsey）；</li>
 *   <li>{@code type} = {@code App\Notifications\SiteMessage}（见
 *       {@code DataMigrator.SITE_MESSAGE_TYPE}，字面量必须逐字一致）；</li>
 *   <li>{@code notifiable_type} = {@code App\Models\User}；</li>
 *   <li>{@code data} 是 {@code SiteMessage::toArray()} 的 JSON
 *       （{@code title, content, format=html, communication_id, delivery_token}）；</li>
 *   <li>Eloquent 的 {@code notifications()} 关系默认
 *       {@code orderBy('created_at', 'desc')}。</li>
 * </ul>
 */
public final class NotificationRepository {

    /** {@code SiteMessage::class} 的字面量。 */
    public static final String SITE_MESSAGE_TYPE = "App\\Notifications\\SiteMessage";

    /** {@code $notification->notifiable_type} 的字面量。 */
    public static final String NOTIFIABLE_TYPE = "App\\Models\\User";

    /** {@code notifications} 里 {@code read_at} 为 NULL 的一行。 */
    public static final class Row {

        public String id;

        public long notifiableId;

        public Object data;

        public String readAt;

        public String createdAt;

        public String updatedAt;

        /** 控制器注入的 {@code display_title}（源里 {@code $service->title(...)}）。 */
        public String displayTitle;

        /** {@code $notification->data}（已 json_decode, assoc）。 */
        @SuppressWarnings("unchecked")
        public Map<String, Object> data() {
            return data instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }
    }

    /** 读列及顺序（{@link #map} 的下标与之对应）。 */
    private static final String[] COLUMNS = {"id", "notifiable_type", "notifiable_id", "data",
            "read_at", "created_at", "updated_at"};

    private final DataSource dataSource;

    private final SchemaInstaller schema;

    private final String timezone;

    public NotificationRepository(DataSource dataSource, SchemaInstaller schema, String timezone) {
        this.dataSource = dataSource;
        this.schema = schema;
        this.timezone = timezone;
    }

    /** 渲染 / 收件箱开关里要用的方言（JSON 路径跨方言）。 */
    public SqlDialect dialect() {
        return schema.dialect();
    }

    private String q(String identifier) {
        return schema.dialect().quote(identifier);
    }

    private String table() {
        return schema.table("notifications");
    }

    private String select() {
        return select(false);
    }

    /** SQL Server 的行数限制查询：{@code SELECT TOP (?) ...}。 */
    private String selectLimited() {
        return select(true);
    }

    private String select(boolean limited) {
        StringBuilder sb = new StringBuilder("SELECT ");
        if (limited) {
            sb.append(dialect().limitPrefix());
        }
        for (int i = 0; i < COLUMNS.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(q(COLUMNS[i]));
        }
        return sb.append(" FROM ").append(table()).append(" n").toString();
    }

    // ------------------------------------------------------------------ 投递

    /**
     * 写一行通知（{@code Notification::sendNow} 里 array 通道的插库）。
     *
     * <p>Laravel 的插库不带事务包裹（一个用户一行），失败即发送中断 —— 保持一致。</p>
     */
    public void insert(String id, long notifiableId, String dataJson,
            String createdAt) throws SQLException {
        String sql = "INSERT INTO " + table() + " ("
                + q("id") + ", " + q("type") + ", " + q("notifiable_type") + ", "
                + q("notifiable_id") + ", " + q("data") + ", " + q("read_at") + ", "
                + q("created_at") + ", " + q("updated_at")
                + ") VALUES (?, ?, ?, ?, ?, NULL, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, SITE_MESSAGE_TYPE);
            stmt.setString(3, NOTIFIABLE_TYPE);
            stmt.setLong(4, notifiableId);
            stmt.setString(5, dataJson);
            stmt.setString(6, createdAt);
            stmt.setString(7, createdAt);
            stmt.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 收件箱

    /**
     * 用户的通知列表页内数据（{@code $service->inboxQuery($user)->latest()
     * ->paginate(20)->items()}）。
     *
     * <p>{@code inboxQuery} 的 SQL（源）：</p>
     * <pre>
     * where NULL data-&gt;communication_id
     * or whereExists communications.status=published, kind in (message,both)
     *    and (delivery_token 匹配 or 双方都没有 token)
     * </pre>
     */
    public List<Row> inboxPage(long notifiableId, long limit, long offset) throws SQLException {
        SqlDialect dialect = schema.dialect();
        String commId = dialect.castText(dialect.jsonText("n." + q("data"), "communication_id"));
        String token = dialect.castText(dialect.jsonText("n." + q("data"), "delivery_token"));

        String sql = select()
                + " WHERE n." + q("notifiable_type") + " = ?"
                + " AND n." + q("notifiable_id") + " = ?"
                + " AND (" + commId + " IS NULL OR EXISTS (SELECT 1 FROM "
                + schema.table("communications") + " c"
                + " WHERE c." + q("id") + " = " + dialect.jsonInt("n." + q("data"), "communication_id")
                + " AND c." + q("status") + " = ?"
                + " AND c." + q("kind") + " IN (?, ?)"
                + " AND (c." + q("delivery_token") + " = " + token
                + " OR (c." + q("delivery_token") + " IS NULL AND " + token + " IS NULL))))"
                + " ORDER BY n." + q("created_at") + " DESC" + dialect.pagingClause();

        List<Row> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            int i = 1;
            stmt.setString(i++, NOTIFIABLE_TYPE);
            stmt.setLong(i++, notifiableId);
            stmt.setString(i++, CommunicationRepository.STATUS_PUBLISHED);
            stmt.setString(i++, CommunicationRepository.KIND_MESSAGE);
            stmt.setString(i++, CommunicationRepository.KIND_BOTH);
            dialect.bindPaging(stmt, i, limit, offset);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        }
        return out;
    }

    /** 收件箱总数（同样的过滤条件）。 */
    public long countInbox(long notifiableId) throws SQLException {
        SqlDialect dialect = schema.dialect();
        String commId = dialect.castText(dialect.jsonText("n." + q("data"), "communication_id"));
        String token = dialect.castText(dialect.jsonText("n." + q("data"), "delivery_token"));

        String sql = "SELECT COUNT(*) FROM " + table() + " n"
                + " WHERE n." + q("notifiable_type") + " = ?"
                + " AND n." + q("notifiable_id") + " = ?"
                + " AND (" + commId + " IS NULL OR EXISTS (SELECT 1 FROM "
                + schema.table("communications") + " c"
                + " WHERE c." + q("id") + " = " + dialect.jsonInt("n." + q("data"), "communication_id")
                + " AND c." + q("status") + " = ?"
                + " AND c." + q("kind") + " IN (?, ?)"
                + " AND (c." + q("delivery_token") + " = " + token
                + " OR (c." + q("delivery_token") + " IS NULL AND " + token + " IS NULL))))";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            int i = 1;
            stmt.setString(i++, NOTIFIABLE_TYPE);
            stmt.setLong(i++, notifiableId);
            stmt.setString(i++, CommunicationRepository.STATUS_PUBLISHED);
            stmt.setString(i++, CommunicationRepository.KIND_MESSAGE);
            stmt.setString(i++, CommunicationRepository.KIND_BOTH);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** 未读数（同样的可见性过滤 + {@code read_at IS NULL}）。 */
    public long countUnread(long notifiableId) throws SQLException {
        SqlDialect dialect = schema.dialect();
        String commId = dialect.castText(dialect.jsonText("n." + q("data"), "communication_id"));
        String token = dialect.castText(dialect.jsonText("n." + q("data"), "delivery_token"));

        String sql = "SELECT COUNT(*) FROM " + table() + " n"
                + " WHERE n." + q("notifiable_type") + " = ?"
                + " AND n." + q("notifiable_id") + " = ?"
                + " AND n." + q("read_at") + " IS NULL"
                + " AND (" + commId + " IS NULL OR EXISTS (SELECT 1 FROM "
                + schema.table("communications") + " c"
                + " WHERE c." + q("id") + " = " + dialect.jsonInt("n." + q("data"), "communication_id")
                + " AND c." + q("status") + " = ?"
                + " AND c." + q("kind") + " IN (?, ?)"
                + " AND (c." + q("delivery_token") + " = " + token
                + " OR (c." + q("delivery_token") + " IS NULL AND " + token + " IS NULL))))";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            int i = 1;
            stmt.setString(i++, NOTIFIABLE_TYPE);
            stmt.setLong(i++, notifiableId);
            stmt.setString(i++, CommunicationRepository.STATUS_PUBLISHED);
            stmt.setString(i++, CommunicationRepository.KIND_MESSAGE);
            stmt.setString(i++, CommunicationRepository.KIND_BOTH);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    // ------------------------------------------------------------------ 管理

    /** {@code DB::table('notifications')->where('data->communication_id', $row->id)->count()}。 */
    public long countDelivery(long communicationId) throws SQLException {
        SqlDialect dialect = schema.dialect();
        String key = dialect.castText(dialect.jsonText(q("data"), "communication_id"));

        String sql = "SELECT COUNT(*) FROM " + table() + " WHERE " + key + " = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, String.valueOf(communicationId));
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** {@code ... ->whereNotNull('read_at')->count()}。 */
    public long countRead(long communicationId) throws SQLException {
        SqlDialect dialect = schema.dialect();
        String key = dialect.castText(dialect.jsonText(q("data"), "communication_id"));

        String sql = "SELECT COUNT(*) FROM " + table() + " WHERE " + key + " = ?"
                + " AND " + q("read_at") + " IS NOT NULL";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, String.valueOf(communicationId));
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /**
     * {@code synchronize}：把同一批投递的通知行的 {@code data} 整体更新。
     *
     * <pre>
     * DB::table('notifications')
     *   -&gt;where('data-&gt;communication_id', $id)
     *   -&gt;where('data-&gt;delivery_token', $token)
     *   -&gt;update(['data' =&gt; ..., 'updated_at' =&gt; ...]);
     * </pre>
     */
    public int synchronize(long communicationId, String deliveryToken, String dataJson,
            String updatedAt) throws SQLException {
        SqlDialect dialect = schema.dialect();
        String idKey = dialect.castText(dialect.jsonText(q("data"), "communication_id"));
        String tokenKey = dialect.castText(dialect.jsonText(q("data"), "delivery_token"));

        String sql = "UPDATE " + table() + " SET " + q("data") + " = ?, "
                + q("updated_at") + " = ?"
                + " WHERE " + idKey + " = ? AND " + tokenKey + " = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, dataJson);
            stmt.setString(2, updatedAt);
            stmt.setString(3, String.valueOf(communicationId));
            stmt.setString(4, deliveryToken);
            return stmt.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 单挑

    /**
     * {@code $user->notifications()->whereKey($id)->firstOrFail()} 的查体。
     *
     * <p>注意另一个约束只按 id 取，不管 notifiable —— 数据不相干的浏览只发生在
     * markAsRead 之前就被 visible 挡下，行为一致即可。</p>
     */
    public Row findByNotifiableAndId(long notifiableId, String id) throws SQLException {
        String sql = select()
                + " WHERE n." + q("notifiable_type") + " = ?"
                + " AND n." + q("notifiable_id") + " = ?"
                + " AND n." + q("id") + " = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, NOTIFIABLE_TYPE);
            stmt.setLong(2, notifiableId);
            stmt.setString(3, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    /** {@code $message->markAsRead()}：read_at = now()。 */
    public void markAsRead(String id, String readAt) throws SQLException {
        String sql = "UPDATE " + table() + " SET " + q("read_at") + " = ?, "
                + q("updated_at") + " = ? WHERE " + q("id") + " = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, readAt);
            stmt.setString(2, readAt);
            stmt.setString(3, id);
            stmt.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 未读通知（{@code inboxQuery($user)->whereNull('read_at')->limit($n)->get()}，
     * 供页面顶部的通知下拉与 {@code NotificationsController@all} 使用）。
     */
    public List<Row> unread(long notifiableId, int limit) throws SQLException {
        SqlDialect dialect = schema.dialect();
        String commId = dialect.castText(dialect.jsonText("n." + q("data"), "communication_id"));
        String token = dialect.castText(dialect.jsonText("n." + q("data"), "delivery_token"));

        String sql = selectLimited()
                + " WHERE n." + q("notifiable_type") + " = ?"
                + " AND n." + q("notifiable_id") + " = ?"
                + " AND n." + q("read_at") + " IS NULL"
                + " AND (" + commId + " IS NULL OR EXISTS (SELECT 1 FROM "
                + schema.table("communications") + " c"
                + " WHERE c." + q("id") + " = " + dialect.jsonInt("n." + q("data"), "communication_id")
                + " AND c." + q("status") + " = ?"
                + " AND c." + q("kind") + " IN (?, ?)"
                + " AND (c." + q("delivery_token") + " = " + token
                + " OR (c." + q("delivery_token") + " IS NULL AND " + token + " IS NULL))))"
                + " ORDER BY n." + q("created_at") + " DESC" + dialect.limitSuffix();

        List<Row> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            int i = 1;
            if (dialect.limitParameterFirst()) {
                stmt.setInt(i++, limit);
            }
            stmt.setString(i++, NOTIFIABLE_TYPE);
            stmt.setLong(i++, notifiableId);
            stmt.setString(i++, CommunicationRepository.STATUS_PUBLISHED);
            stmt.setString(i++, CommunicationRepository.KIND_MESSAGE);
            stmt.setString(i++, CommunicationRepository.KIND_BOTH);
            if (!dialect.limitParameterFirst()) {
                stmt.setInt(i, limit);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        }

        return out;
    }

    private Row map(ResultSet rs) throws SQLException {
        Row row = new Row();
        row.id = rs.getString(1);
        row.notifiableId = rs.getLong(3);
        row.data = decoded(rs.getString(4));
        row.readAt = rs.getString(5);
        row.createdAt = rs.getString(6);
        row.updatedAt = rs.getString(7);
        return row;
    }

    private static Object decoded(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        return PhpJson.decode(text);
    }
}
