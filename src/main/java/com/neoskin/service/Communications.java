package com.neoskin.service;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.neoskin.db.CommunicationRepository;
import com.neoskin.db.NotificationRepository;
import com.neoskin.support.PhpDate;
import com.neoskin.support.PhpJson;

/**
 * {@code App\Services\Communications} 的移植：公告 / 站内信的发布、投递、同步、撤回与读取。
 *
 * <h2>源实现的关键语义</h2>
 * <ol>
 *   <li><b>save</b> 分三条路径：{@code kind=announcement} 只写公告；{@code message}
 *       只写站内信；{@code both} 先写站内信、发布时再写一条指向它的公告
 *       （{@code audience.communication_id}）；</li>
 *   <li><b>投递开关</b>：{@code startDelivery} 仅在「本次发布 + kind 含 message +
 *       之前不在收件箱」时为真 —— 编辑已发布内容走 <b>synchronize</b>（原地更新
 *       通知行、保留 read_at），而不是重新投递；</li>
 *   <li><b>delivery_token</b>：新投递时生成 16 字节（32 位十六进制）随机串，
 *       写进 communications 与每条通知的 {@code data.delivery_token}；收件箱查询
 *       靠它把「已撤回/已重新投递」的旧通知挡掉；</li>
 *   <li><b>分块投递</b>：{@code chunkById(200, ..., 'uid')} —— 按 uid 升序、每批
 *       200 个用户，避免一次性把全站用户读进内存。</li>
 * </ol>
 *
 * <p>所有写操作都在一个事务里（{@code DB::transaction}）：公告与站内信要么一起
 * 成功要么一起回滚。</p>
 */
public final class Communications {

    /** {@code chunkById(200)} 的批大小。 */
    private static final int CHUNK_SIZE = 200;

    private final DataSource dataSource;

    private final CommunicationRepository communications;

    private final NotificationRepository notifications;

    private final CommunicationRenderer renderer;

    private final String timezone;

    private final SecureRandom random = new SecureRandom();

    public Communications(DataSource dataSource, CommunicationRepository communications,
            NotificationRepository notifications, CommunicationRenderer renderer, String timezone) {
        this.dataSource = dataSource;
        this.communications = communications;
        this.notifications = notifications;
        this.renderer = renderer;
        this.timezone = timezone;
    }

    // ================================================================== 保存

    /**
     * {@code save($data, $message, $author)}。
     *
     * @param data    校验后的数据（title/kind/editor/source/blocks/locale/audience/operation）
     * @param message 编辑时的原记录；新建为 null
     * @param author  操作者 uid
     */
    public CommunicationRepository.Row save(Map<String, Object> data,
            CommunicationRepository.Row message, int author) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                CommunicationRepository.Row saved = doSave(conn, data, message, author);
                conn.commit();
                return saved;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private CommunicationRepository.Row doSave(Connection conn, Map<String, Object> data,
            CommunicationRepository.Row message, int author) throws SQLException {
        if (CommunicationRepository.KIND_ANNOUNCEMENT.equals(str(data.get("kind")))) {
            return saveAnnouncement(data, author, null);
        }

        CommunicationRepository.Row target = message;
        boolean existed = target != null;
        boolean wasPublished = existed && CommunicationRepository.STATUS_PUBLISHED.equals(target.status);
        boolean wasInbox = wasPublished
                && (CommunicationRepository.KIND_MESSAGE.equals(target.kind)
                        || CommunicationRepository.KIND_BOTH.equals(target.kind));

        if (target == null) {
            target = new CommunicationRepository.Row();
        }

        // fill(array_intersect_key($data, array_flip([...])))
        target.title = str(data.get("title"));
        target.kind = str(data.get("kind"));
        target.editor = str(data.get("editor"));
        target.source = nullable(data.get("source"));
        target.blocks = data.get("blocks");
        target.locale = str(data.get("locale"));
        target.audience = data.get("audience");

        target.authorId = author;
        boolean publish = "publish".equals(str(data.get("operation")));
        target.status = publish ? CommunicationRepository.STATUS_PUBLISHED
                : CommunicationRepository.STATUS_DRAFT;

        if (CommunicationRepository.STATUS_PUBLISHED.equals(target.status) && !wasPublished) {
            target.publishedAt = PhpDate.now(timezone);
        }

        boolean hasInboxKind = CommunicationRepository.KIND_MESSAGE.equals(target.kind)
                || CommunicationRepository.KIND_BOTH.equals(target.kind);
        boolean startDelivery = CommunicationRepository.STATUS_PUBLISHED.equals(target.status)
                && hasInboxKind && !wasInbox;

        if (startDelivery) {
            target.deliveryToken = randomToken();
        }

        communications.save(target);

        if (CommunicationRepository.STATUS_PUBLISHED.equals(target.status) && hasInboxKind) {
            if (startDelivery) {
                deliver(target);
            } else {
                synchronize(target);
            }
        }

        if (CommunicationRepository.KIND_BOTH.equals(target.kind)
                && CommunicationRepository.STATUS_PUBLISHED.equals(target.status)) {
            saveAnnouncement(data, author, target.id);
        }

        return target;
    }

    /**
     * {@code saveAnnouncement($data, $author, $origin)}：按 locale 找「同语言最新一条公告」
     * 覆盖写（没有则新建）。
     */
    private CommunicationRepository.Row saveAnnouncement(Map<String, Object> data, int author,
            Long origin) throws SQLException {
        String locale = data.get("locale") == null ? "" : str(data.get("locale"));

        CommunicationRepository.Row announcement = latestAnnouncement(locale);
        if (announcement == null) {
            announcement = new CommunicationRepository.Row();
        }

        announcement.title = str(data.get("title"));
        announcement.editor = str(data.get("editor"));
        announcement.source = nullable(data.get("source"));
        announcement.blocks = data.get("blocks");
        announcement.kind = CommunicationRepository.KIND_ANNOUNCEMENT;
        announcement.locale = locale;
        announcement.authorId = author;
        announcement.audience = origin == null ? null
                : new LinkedHashMap<>(Map.of("communication_id", origin));

        boolean publish = "publish".equals(str(data.get("operation")));
        announcement.status = publish ? CommunicationRepository.STATUS_PUBLISHED
                : CommunicationRepository.STATUS_DRAFT;
        if (publish) {
            announcement.publishedAt = PhpDate.now(timezone);
        }

        communications.save(announcement);
        return announcement;
    }

    /** {@code Communication::where('kind','announcement')->where('locale',$locale)->latest('id')->first()}。 */
    private CommunicationRepository.Row latestAnnouncement(String locale) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            var dialect = com.neoskin.db.Paging.dialectOf(conn);
            String sql = "SELECT " + dialect.firstRowPrefix() + "* FROM " + communications.tableName()
                    + " WHERE " + communications.quote("kind") + " = ?"
                    + " AND " + communications.quote("locale") + " = ?"
                    + " ORDER BY " + communications.quote("id") + " DESC"
                    + dialect.firstRowSuffix();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, CommunicationRepository.KIND_ANNOUNCEMENT);
                stmt.setString(2, locale);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? communications.mapRow(rs) : null;
                }
            }
        }
    }

    // ================================================================== 当前公告

    /**
     * {@code currentAnnouncement()}：先按当前语言、再按空语言找；
     * 排序 {@code updated_at DESC, kind ASC, id DESC}。
     */
    public CommunicationRepository.Row currentAnnouncement(String locale) throws SQLException {
        for (String candidate : new java.util.LinkedHashSet<>(
                List.of(locale == null ? "" : locale, ""))) {
            CommunicationRepository.Row row = communications.findCurrent(candidate);
            if (row != null) {
                return row;
            }
        }
        return null;
    }

    // ================================================================== 投递

    /**
     * {@code payload($message)}：通知行的 {@code data}（键顺序即 PHP 数组顺序）。
     */
    private Map<String, Object> payload(CommunicationRepository.Row message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", message.title);
        data.put("content", renderer.render(message.toArray(), "mail-" + message.id));
        data.put("format", "html");
        data.put("communication_id", message.id);
        data.put("delivery_token", message.deliveryToken);
        return data;
    }

    /**
     * {@code deliver($message)}：按 audience 选收件人，按 uid 分块投递。
     *
     * <pre>
     * receiver = all    → permission &gt;= NORMAL
     *          = normal → permission == NORMAL
     *          = uid    → uid = $audience['uid']
     *          = email  → email = $audience['email']
     * </pre>
     */
    private void deliver(CommunicationRepository.Row message) throws SQLException {
        Map<String, Object> audience = asMap(message.audience);
        String receiver = audience.isEmpty() ? "all"
                : (audience.get("receiver") == null ? "all" : String.valueOf(audience.get("receiver")));

        Map<String, Object> data = payload(message);
        String dataJson = PhpJson.encode(data, PhpJson.JSON_UNESCAPED_UNICODE);
        String now = PhpDate.now(timezone);

        long lastUid = 0;
        while (true) {
            List<Long> uids = recipientChunk(receiver, audience, lastUid);
            if (uids.isEmpty()) {
                break;
            }
            for (long uid : uids) {
                notifications.insert(UUID.randomUUID().toString(), uid, dataJson, now);
                lastUid = uid;
            }
            if (uids.size() < CHUNK_SIZE) {
                break;
            }
        }
    }

    /** 一批收件人 uid（{@code chunkById(200, ..., 'uid')}）。 */
    private List<Long> recipientChunk(String receiver, Map<String, Object> audience, long afterUid)
            throws SQLException {
        String where;
        List<Object> params = new ArrayList<>();
        switch (receiver) {
            case "normal" -> {
                where = " WHERE permission = ?";
                params.add(0); // User::NORMAL
            }
            case "uid" -> {
                where = " WHERE uid = ?";
                params.add(asLong(audience.get("uid")));
            }
            case "email" -> {
                where = " WHERE email = ?";
                params.add(String.valueOf(audience.get("email")));
            }
            default -> {
                where = " WHERE permission >= ?";
                params.add(0); // User::NORMAL
            }
        }
        // chunkById 的追加条件：uid > $lastId
        where += " AND uid > ?";
        params.add(afterUid);

        List<Long> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT uid FROM users" + where + " ORDER BY uid ASC"
                    + com.neoskin.db.Paging.dialectOf(conn).limitLiteralSuffix(CHUNK_SIZE);
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                int i = 1;
                for (Object param : params) {
                    stmt.setObject(i++, param);
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getLong(1));
                    }
                }
            }
        }
        return out;
    }

    /**
     * {@code synchronize($message)}：已投递过的内容被编辑后，原地刷新通知行的 data
     * （保留 read_at）。
     */
    private void synchronize(CommunicationRepository.Row message) throws SQLException {
        Map<String, Object> data = payload(message);
        notifications.synchronize(message.id, message.deliveryToken,
                PhpJson.encode(data, PhpJson.JSON_UNESCAPED_UNICODE), PhpDate.now(timezone));
    }

    // ================================================================== 撤回

    /**
     * {@code withdraw($message)}：状态置 withdrawn；{@code kind=both} 时把它派生出的
     * 公告一并撤回。
     */
    public void withdraw(CommunicationRepository.Row message) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                message.status = CommunicationRepository.STATUS_WITHDRAWN;
                message.updatedAt = PhpDate.now(timezone);
                communications.save(message);

                if (CommunicationRepository.KIND_BOTH.equals(message.kind)) {
                    communications.withdrawAnnouncementsOfOrigin(message.id);
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    // ================================================================== 读取

    /**
     * {@code visible($notification)}：通知引用的通信必须仍处于「已发布 + 收件箱类型 +
     * delivery_token 一致」。
     *
     * <p>{@code !$id || ...}：没有 {@code communication_id} 的旧通知永远可见。</p>
     */
    public boolean visible(NotificationRepository.Row notification) throws SQLException {
        Map<String, Object> data = notification.data();
        Object rawId = data.get("communication_id");
        Long id = asLong(rawId);
        if (id == null || id == 0) {
            return true;
        }
        Object rawToken = data.get("delivery_token");
        return communications.existsVisible(id, rawToken == null ? null : String.valueOf(rawToken));
    }

    /** {@code title($notification)}：优先取通信的当前标题，否则 data.title，最后「站内信」。 */
    public String title(NotificationRepository.Row notification) throws SQLException {
        Map<String, Object> data = notification.data();
        Long id = asLong(data.get("communication_id"));
        if (id != null && id != 0) {
            String title = communications.findTitle(id);
            return title == null ? "" : title;
        }
        Object title = data.get("title");
        return title == null ? "站内信" : String.valueOf(title);
    }

    /** {@code content($notification)}。 */
    public String content(NotificationRepository.Row notification) throws SQLException {
        Map<String, Object> data = notification.data();
        Object rawId = data.get("communication_id");
        if (rawId != null && !"".equals(String.valueOf(rawId)) && !"0".equals(String.valueOf(rawId))) {
            Long id = asLong(rawId);
            if (id == null) {
                return "";
            }
            CommunicationRepository.Row message = communications.find(id);
            return message == null ? "" : renderer.render(message.toArray(), "mail-" + message.id);
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("editor", "source");
        source.put("source", data.get("content") == null ? "" : data.get("content"));
        String prefix = "legacy-" + String.valueOf(notification.id).replace("-", "");
        return renderer.render(source, prefix);
    }

    /** {@code announcements()}：当前公告若已发布则渲染，否则空串。 */
    public String announcements(String locale) throws SQLException {
        CommunicationRepository.Row announcement = currentAnnouncement(locale);
        if (announcement == null
                || !CommunicationRepository.STATUS_PUBLISHED.equals(announcement.status)) {
            return "";
        }
        return renderer.render(announcement.toArray(), "announcement-" + announcement.id);
    }

    // ================================================================== helpers

    /** {@code bin2hex(random_bytes(16))} → 32 位小写十六进制。 */
    private String randomToken() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullable(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** PHP 的宽松整数转换（{@code (int)} / {@code integer()} 的入参侧）。 */
    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
