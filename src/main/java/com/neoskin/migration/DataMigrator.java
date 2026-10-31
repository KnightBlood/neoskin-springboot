package com.neoskin.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.service.Option;
import com.neoskin.service.OptionDefaults;
import com.neoskin.service.hash.LaravelHash;
import com.neoskin.support.PhpDate;
import com.neoskin.support.PhpJson;
import com.neoskin.support.PhpString;

/**
 * 源项目里 4 个<b>写数据</b>的 migration 的等价实现。
 *
 * <p>{@link SchemaInstaller} 只负责建表并登记 23 个纯结构 migration；剩下的 4 个
 * 会改动数据，单独放在这里，原因见 {@code SchemaInstaller#SCHEMA_MIGRATIONS} 的说明。
 *
 * <table border="1">
 *   <caption>4 个数据迁移</caption>
 *   <tr><th>migration</th><th>作用</th></tr>
 *   <tr><td>{@code 2016_11_18_134542_import_options}</td>
 *       <td>把 {@code config/options.php} 的 54 个键写入 {@code options} 表，并插值
 *           {@code {version}} / {@code {year}}</td></tr>
 *   <tr><td>{@code 2025_06_29_064944_add_max_texture_width_option}</td>
 *       <td>写入 {@code max_texture_width = 8192}</td></tr>
 *   <tr><td>{@code 2026_10_24_000001_create_communications}</td>
 *       <td>建表（由 {@code SchemaInstaller} 完成）+ 把老公告/站内信回填成
 *           {@code communications} 行，并回写 {@code notifications.data.communication_id}</td></tr>
 *   <tr><td>{@code 2026_10_25_000001_hash_oauth_client_secrets}</td>
 *       <td>把 {@code oauth_clients.secret} 的明文用 bcrypt 哈希化（Passport 13 起必需）</td></tr>
 * </table>
 *
 * <h2>幂等性</h2>
 * <p>与 PHP 版一样，每个迁移执行完都会往 {@code migrations} 表插一行；已经登记过的直接跳过。
 * 除此之外，回填逻辑<b>自身</b>也是幂等的（靠 {@code legacy_key} 唯一键和
 * {@code communication_id} 存在性判断），因此即使 {@code migrations} 表被手工清空、
 * 或者中途崩溃，重复执行也不会产生重复数据。
 *
 * <p>为什么这一点很重要：{@code 2026_10_24_000001} 在 PHP 里是「建表 + 回填」混在一起的，
 * 建表部分我们已经放在 {@code SchemaInstaller}（它建完表就提交了）。如果回填中途失败，
 * 下一次启动必须能从断点继续，而不是因为「表已存在」就整段跳过。
 *
 * <h2>与 PHP 的字节级兼容</h2>
 * <ul>
 *   <li>{@code notifications.data} 的回写用 {@code JSON_UNESCAPED_UNICODE}（中文原样，
 *       斜杠仍转义为 {@code \/}），见 {@link PhpJson}；</li>
 *   <li>{@code communications.audience} 用<b>裸</b> {@code json_encode}
 *       （{@code {"receiver":"uid","uid":42}}，全 ASCII 所以两种选项结果相同）；</li>
 *   <li>{@code trim()} 用 {@link PhpString#trim}（PHP 的字符集含 {@code \x0B}、不含
 *       {@code \x0C}）；</li>
 *   <li>{@code mb_substr(…, 0, 100)} 按<b>码点</b>截断，不能用
 *       {@link String#substring}（会劈开 emoji）；</li>
 *   <li>写入的时间戳统一 {@code Y-m-d H:i:s} + {@code Asia/Shanghai}，见 {@link PhpDate}。</li>
 * </ul>
 */
@Component
public class DataMigrator {

    private static final Logger log = LoggerFactory.getLogger(DataMigrator.class);

    /** 灌入 {@code config/options.php}。 */
    public static final String IMPORT_OPTIONS = "2016_11_18_134542_import_options";

    /** 写入 {@code max_texture_width}。 */
    public static final String MAX_TEXTURE_WIDTH = "2025_06_29_064944_add_max_texture_width_option";

    /** 建 {@code communications} 并回填公告 / 站内信。 */
    public static final String CREATE_COMMUNICATIONS = "2026_10_24_000001_create_communications";

    /** 哈希化 OAuth 客户端密钥。 */
    public static final String HASH_OAUTH_SECRETS = "2026_10_25_000001_hash_oauth_client_secrets";

    /**
     * {@code notifications.type} 里站内信的字面量。
     *
     * <p>PHP 侧写的是 {@code App\Notifications\SiteMessage::class}，它的值是
     * <b>不带</b>前导反斜杠的 {@code App\Notifications\SiteMessage}，必须逐字匹配，
     * 否则一条都回填不到。
     */
    public static final String SITE_MESSAGE_TYPE = "App\\Notifications\\SiteMessage";

    /** {@code chunkById(200)} 的分页大小，与 PHP 一致。 */
    private static final int CHUNK_SIZE = 200;

    /** 回填后的公告标题（PHP 里是硬编码的中文）。 */
    private static final String ANNOUNCEMENT_TITLE = "站点公告";

    /** 站内信缺少 {@code title} 时的默认值。 */
    private static final String MESSAGE_DEFAULT_TITLE = "站内信";

    /** {@code communications.title} 的字符上限。 */
    private static final int TITLE_LIMIT = 100;

    /** {@code announcement_} 前缀的长度，用于 {@code substr($name, 13)}。 */
    private static final int ANNOUNCEMENT_PREFIX = 13;

    private final DataSource dataSource;
    private final SchemaInstaller schema;
    private final Option option;
    private final NeoSkinSettings settings;
    private final LaravelHash hash;

    public DataMigrator(DataSource dataSource, SchemaInstaller schema, Option option,
            NeoSkinSettings settings) {
        this.dataSource = dataSource;
        this.schema = schema;
        this.option = option;
        this.settings = settings;
        // 只用于 oauth_clients.secret：成本因子是 config/hashing.php 的 10，
        // 与用户密码的 12 是两套独立的配置，见 NeoSkinSettings.Hashing。
        this.hash = new LaravelHash(settings.getHashing().getClientSecretRounds());
    }

    /** 供测试检查成本因子。 */
    public LaravelHash hash() {
        return hash;
    }

    /**
     * 依次执行 4 个数据迁移。
     *
     * <p>调用前必须已执行 {@link SchemaInstaller#install()}（表结构就绪、{@code migrations}
     * 表已创建）。本方法自身也会兜底创建 {@code migrations} 表，因此单独调用也不会炸。
     *
     * @return 本次真正执行了的 migration 名（已登记过的不会出现在里面）
     */
    public List<String> migrate() {
        schema.ensureMigrationsTable();
        List<String> applied = new ArrayList<>();
        run(IMPORT_OPTIONS, this::importOptions, applied);
        run(MAX_TEXTURE_WIDTH, this::addMaxTextureWidth, applied);
        run(CREATE_COMMUNICATIONS, this::backfillCommunications, applied);
        run(HASH_OAUTH_SECRETS, this::hashOauthClientSecrets, applied);
        return applied;
    }

    /** 单独执行某个迁移（测试用）；已登记则直接返回 {@code false}。 */
    public boolean migrate(String name) {
        schema.ensureMigrationsTable();
        if (schema.isMigrationRecorded(name)) {
            return false;
        }
        switch (name) {
            case IMPORT_OPTIONS -> importOptions();
            case MAX_TEXTURE_WIDTH -> addMaxTextureWidth();
            case CREATE_COMMUNICATIONS -> backfillCommunications();
            case HASH_OAUTH_SECRETS -> hashOauthClientSecrets();
            default -> throw new IllegalArgumentException("not a data migration: " + name);
        }
        schema.recordMigration(name);
        return true;
    }

    private void run(String name, Runnable body, List<String> applied) {
        if (schema.isMigrationRecorded(name)) {
            log.debug("data migration {} already recorded, skipping", name);
            return;
        }
        log.info("running data migration {}", name);
        body.run();
        schema.recordMigration(name);
        applied.add(name);
    }

    // ================================================================== 1. import_options

    /**
     * {@code 2016_11_18_134542_import_options}。
     *
     * <p>逐字复刻：
     * <pre>{@code
     * $options = config('options');
     * $options['version'] = config('app.version');
     * $options['announcement'] = str_replace('{version}', $options['version'], $options['announcement']);
     * $options['copyright_text'] = str_replace('{year}', Carbon::now()->year, $options['copyright_text']);
     * foreach ($options as $key => $value) Option::set($key, $value);
     * }</pre>
     *
     * <p>两个插值都发生在<b>写入之前</b>，所以数据库里存的是已经替换好的文本，
     * 而不是带占位符的模板。这一点很容易被误解成「运行时再替换」。
     */
    void importOptions() {
        String version = settings.getVersion();
        int year = PhpDate.currentYear(settings.getTimezone());

        Map<String, String> values = new LinkedHashMap<>(OptionDefaults.all());
        values.put("version", version);
        values.put("announcement",
                OptionDefaults.get("announcement").replace("{version}", version));
        values.put("copyright_text",
                OptionDefaults.get("copyright_text").replace("{year}", String.valueOf(year)));

        for (Map.Entry<String, String> e : values.entrySet()) {
            option.set(e.getKey(), e.getValue());
        }
        log.info("imported {} options (version={}, year={})", values.size(), version, year);
    }

    // ================================================================== 2. max_texture_width

    /**
     * {@code 2025_06_29_064944_add_max_texture_width_option}：只有一行
     * {@code Option::set('max_texture_width', 8192)}。
     *
     * <p>这个键<b>不在</b> {@code config/options.php} 里（那是 54 个键），所以不能塞进
     * {@link OptionDefaults}——否则「新建站点」和「升级站点」的默认值表会不一致。
     */
    void addMaxTextureWidth() {
        option.set("max_texture_width", 8192);
    }

    // ================================================================== 3. communications

    /**
     * {@code 2026_10_24_000001_create_communications} 的数据部分（建表由
     * {@link SchemaInstaller} 完成）。
     *
     * <p>分两段：
     * <ol>
     *   <li><b>老公告 → communications</b>：{@code options} 里 {@code announcement} 与
     *       {@code announcement_*} 开头的键，值 {@code trim()} 后非空的，逐条变成
     *       {@code kind='announcement'} 的行，{@code legacy_key='option:<name>'}；</li>
     *   <li><b>老站内信 → communications</b>：{@code notifications} 里
     *       {@code type='App\Notifications\SiteMessage'} 的行，按 {@code id} 升序分块
     *       （每块 200）处理，把 {@code data} JSON 里的标题/正文搬进 {@code communications}，
     *       再把新 id 回写进 {@code data.communication_id}。</li>
     * </ol>
     *
     * <p>两个迁移细节必须照抄：
     * <ul>
     *   <li>公告的 {@code locale}：基键是空串，{@code announcement_<locale>} 取
     *       {@code substr($name, 13)}（按字节，而不是「分列后」）；</li>
     *   <li>站内信的 {@code published_at}/{@code created_at}/{@code updated_at} 用
     *       <b>那条通知自己的</b>时间戳，不是 {@code now()}——否则用户看到的
     *       「发送时间」会全部变成升级时间。</li>
     * </ul>
     */
    void backfillCommunications() {
        try (Connection conn = dataSource.getConnection()) {
            ensureCommunicationsColumns(conn);
            int announcements = backfillAnnouncements(conn);
            int messages = backfillSiteMessages(conn);
            log.info("communications backfill: {} announcements, {} site messages",
                    announcements, messages);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to backfill communications", e);
        }
    }

    /**
     * 兼容「中途版本」的库：{@code communications} 已存在但没有 {@code legacy_key} /
     * {@code delivery_token} 列（PHP 迁移里同样有这两个 {@code hasColumn} 分支）。
     */
    private void ensureCommunicationsColumns(Connection conn) throws SQLException {
        if (!hasColumn(conn, "communications", "legacy_key")) {
            execute(conn, "ALTER TABLE " + q("communications") + " ADD COLUMN "
                    + q("legacy_key") + " " + dialect().varchar(100) + " NULL");
            // Laravel 在 SQLite 上把 ->unique() 编译成独立的 CREATE UNIQUE INDEX，
            // 这里同样补一个；失败只记日志，不阻断回填。
            try {
                execute(conn, "CREATE UNIQUE INDEX " + q("communications_legacy_key_unique")
                        + " ON " + q("communications") + " (" + q("legacy_key") + ")");
            } catch (SQLException e) {
                log.warn("could not create unique index on communications.legacy_key: {}",
                        e.getMessage());
            }
        }
        if (!hasColumn(conn, "communications", "delivery_token")) {
            execute(conn, "ALTER TABLE " + q("communications") + " ADD COLUMN "
                    + q("delivery_token") + " " + dialect().varchar(32) + " NULL");
        }
    }

    /** 第 1 段：{@code options} 里的老公告。 */
    private int backfillAnnouncements(Connection conn) throws SQLException {
        String sql = "SELECT " + q("option_name") + ", " + q("option_value")
                + " FROM " + q(schema.table("options"))
                + " WHERE " + q("option_name") + " = ? OR " + q("option_name") + " LIKE ?"
                + " ORDER BY " + q("id");
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "announcement");
            ps.setString(2, "announcement_%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new String[] { rs.getString(1), rs.getString(2) });
                }
            }
        }

        String now = PhpDate.now(settings.getTimezone());
        int inserted = 0;
        for (String[] row : rows) {
            String name = row[0];
            String value = row[1] == null ? "" : row[1];
            // PHP: if (trim((string) $option->option_value) === '') continue;
            if (PhpString.trim(value).isEmpty()) {
                continue;
            }
            String legacyKey = "option:" + name;
            if (exists(conn, schema.table("communications"), "legacy_key", legacyKey)) {
                continue;
            }
            String locale = "announcement".equals(name)
                    ? ""
                    : PhpString.substrBytes(name, ANNOUNCEMENT_PREFIX);
            insertCommunication(conn, new Communication()
                    .legacyKey(legacyKey)
                    .title(ANNOUNCEMENT_TITLE)
                    .kind("announcement")
                    .status("published")
                    .locale(locale)
                    .editor("source")
                    .source(value)
                    .audience(null)
                    .publishedAt(now)
                    .createdAt(now)
                    .updatedAt(now));
            inserted++;
        }
        return inserted;
    }

    /** 第 2 段：{@code notifications} 里的站内信。 */
    private int backfillSiteMessages(Connection conn) throws SQLException {
        int inserted = 0;
        String lastId = null;
        while (true) {
            List<NotificationRow> page = notificationsPage(conn, lastId);
            if (page.isEmpty()) {
                break;
            }
            for (NotificationRow row : page) {
                lastId = row.id;
                if (backfillOneMessage(conn, row)) {
                    inserted++;
                }
            }
            if (page.size() < CHUNK_SIZE) {
                break;
            }
        }
        return inserted;
    }

    /**
     * 取一页通知（对应 {@code chunkById(200)}）。
     *
     * <p>必须分页读，不能边读边写：MySQL 的未缓冲结果集不允许在遍历期间对同一张表发
     * 新语句（PHP 版为此专门注释了「不能使用 {@code cursor()}」）。
     */
    private List<NotificationRow> notificationsPage(Connection conn, String lastId)
            throws SQLException {
        String table = q(schema.table("notifications"));
        String sql = "SELECT " + q("id") + ", " + q("notifiable_id") + ", " + q("data")
                + ", " + q("created_at") + ", " + q("updated_at")
                + " FROM " + table + " WHERE " + q("type") + " = ?"
                + (lastId == null ? "" : " AND " + q("id") + " > ?")
                + " ORDER BY " + q("id") + " ASC"
                + " LIMIT " + CHUNK_SIZE;
        List<NotificationRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, SITE_MESSAGE_TYPE);
            if (lastId != null) {
                ps.setString(2, lastId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    NotificationRow row = new NotificationRow();
                    row.id = rs.getString(1);
                    row.notifiableId = rs.getLong(2);
                    row.data = rs.getString(3);
                    row.createdAt = dbDate(rs, 4);
                    row.updatedAt = dbDate(rs, 5);
                    out.add(row);
                }
            }
        }
        return out;
    }

    /** 回填一条站内信；返回是否新建了 {@code communications} 行。 */
    private boolean backfillOneMessage(Connection conn, NotificationRow row) throws SQLException {
        PhpJson.Array data = PhpJson.decodeArray(row.data);
        if (data == null) {
            // PHP: if (!is_array($data) …) continue;（含 data 为 "null" 或坏 JSON）
            return false;
        }
        if (hasExistingCommunication(conn, data.get("communication_id"))) {
            return false;
        }

        String legacyKey = "notification:" + row.id;
        Long id = findIdByLegacyKey(conn, legacyKey);
        boolean created = false;
        if (id == null) {
            String title = messageTitle(data.get("title"));
            insertCommunication(conn, new Communication()
                    .legacyKey(legacyKey)
                    .title(title)
                    .kind("message")
                    .status("published")
                    .locale("")
                    .editor("source")
                    .source(messageContent(data.get("content")))
                    .audience(audience(row.notifiableId))
                    .publishedAt(row.createdAt)
                    .createdAt(row.createdAt)
                    .updatedAt(row.updatedAt));
            id = findIdByLegacyKey(conn, legacyKey);
            created = true;
        }
        if (id == null) {
            // 理论上不可能：刚插入的行一定能查到。留一条日志而不是静默写 null。
            log.warn("communications row for legacy_key={} vanished after insert", legacyKey);
            return false;
        }

        // $data['communication_id'] = $id; —— LinkedHashMap 的 put 语义与 PHP 完全一致：
        // 键已存在时保持原位置，不存在时追加到末尾。
        data.put("communication_id", id);
        String json = PhpJson.encode(data, PhpJson.JSON_UNESCAPED_UNICODE);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + q(schema.table("notifications")) + " SET " + q("data") + " = ?"
                        + " WHERE " + q("id") + " = ?")) {
            ps.setString(1, json);
            ps.setString(2, row.id);
            ps.executeUpdate();
        }
        return created;
    }

    /**
     * {@code isset($data['communication_id']) && DB::table('communications')->where('id', …)->exists()}。
     *
     * <p>注意 {@code isset()} 对 {@code null} 返回 false，所以 {@code communication_id: null}
     * 不算「已回填」，会继续往下走并被赋上新值。
     */
    private boolean hasExistingCommunication(Connection conn, Object value) throws SQLException {
        Long id = asLong(value);
        if (id == null) {
            return false;
        }
        String sql = "SELECT 1 FROM " + q(schema.table("communications"))
                + " WHERE " + q("id") + " = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private Long findIdByLegacyKey(Connection conn, String legacyKey) throws SQLException {
        String sql = "SELECT " + q("id") + " FROM " + q(schema.table("communications"))
                + " WHERE " + q("legacy_key") + " = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, legacyKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /**
     * {@code mb_substr((string) ($data['title'] ?? '站内信'), 0, 100)}。
     *
     * <p>{@code ??} 把「键不存在」和「值为 null」都回退到默认值，这两种情况都要覆盖。
     */
    private static String messageTitle(Object raw) {
        String text = raw == null ? MESSAGE_DEFAULT_TITLE : PhpString.cast(raw);
        return PhpString.mbSubstr(text, 0, TITLE_LIMIT);
    }

    /** {@code $data['content'] ?? ''}。 */
    private static String messageContent(Object raw) {
        return raw == null ? "" : PhpString.cast(raw);
    }

    /**
     * {@code json_encode(['receiver' => 'uid', 'uid' => $row->notifiable_id])}。
     *
     * <p>键顺序固定（receiver 在前），因为 PHP 数组保持插入顺序，而回写内容会被 PHP 读回。
     */
    private static String audience(long notifiableId) {
        Map<Object, Object> map = new LinkedHashMap<>();
        map.put("receiver", "uid");
        map.put("uid", notifiableId);
        return PhpJson.encode(map);
    }

    // ================================================================== 4. oauth secrets

    /**
     * {@code 2026_10_25_000001_hash_oauth_client_secrets}。
     *
     * <p>Passport 13 起 {@code oauth_clients.secret} 必须存哈希，否则第三方 OAuth 登录
     * 会全部失效。判定与 {@code php artisan passport:hash} 一致：
     * <pre>{@code
     * if (Hash::isHashed($secret) && !Hash::needsRehash($secret)) continue;
     * DB::table('oauth_clients')->where('id', $client->id)->update(['secret' => Hash::make($secret)]);
     * }</pre>
     *
     * <p>三个容易踩的坑：
     * <ol>
     *   <li>{@code isHashed()} 只认 {@code $2y$}（{@code $2a$}/{@code $2b$}/{@code $2x$}
     *       以及 argon2 都不算），所以老库里 {@code $2a$} 的密钥会被<b>重新哈希</b>——
     *       这是 PHP 的真实行为，不是 bug；</li>
     *   <li>{@code needsRehash()} 比较的是 {@code config('hashing.bcrypt.rounds')}（10），
     *       不是用户密码的 12；</li>
     *   <li>必须先把整表读进内存再更新，不能用游标边读边写（MySQL 未缓冲结果集）。</li>
     * </ol>
     */
    void hashOauthClientSecrets() {
        try (Connection conn = dataSource.getConnection()) {
            if (!exists(conn, schema.table("oauth_clients"))
                    || !hasColumn(conn, schema.table("oauth_clients"), "secret")) {
                return;
            }
            List<Object[]> clients = new ArrayList<>();
            String select = "SELECT " + q("id") + ", " + q("secret")
                    + " FROM " + q(schema.table("oauth_clients"))
                    + " WHERE " + q("secret") + " IS NOT NULL AND " + q("secret") + " <> ''";
            try (PreparedStatement ps = conn.prepareStatement(select);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    clients.add(new Object[] { rs.getLong(1), rs.getString(2) });
                }
            }

            int hashed = 0;
            String update = "UPDATE " + q(schema.table("oauth_clients")) + " SET "
                    + q("secret") + " = ? WHERE " + q("id") + " = ?";
            try (PreparedStatement ps = conn.prepareStatement(update)) {
                for (Object[] client : clients) {
                    long id = ((Number) client[0]).longValue();
                    String secret = (String) client[1];
                    if (LaravelHash.isHashed(secret) && !hash.needsRehash(secret)) {
                        continue;
                    }
                    ps.setString(1, hash.make(secret));
                    ps.setLong(2, id);
                    ps.addBatch();
                    hashed++;
                }
                if (hashed > 0) {
                    ps.executeBatch();
                }
            }
            log.info("hashed {} of {} oauth client secrets (cost={})",
                    hashed, clients.size(), hash.rounds());
        } catch (SQLException e) {
            throw new IllegalStateException("failed to hash oauth client secrets", e);
        }
    }

    // ================================================================== 通用工具

    /** 一条 {@code communications} 插入所需的字段。 */
    private static final class Communication {
        private String legacyKey;
        private String title;
        private String kind;
        private String status;
        private String locale;
        private String editor;
        private String source;
        private String audience;
        private String publishedAt;
        private String createdAt;
        private String updatedAt;

        Communication legacyKey(String v) { this.legacyKey = v; return this; }
        Communication title(String v) { this.title = v; return this; }
        Communication kind(String v) { this.kind = v; return this; }
        Communication status(String v) { this.status = v; return this; }
        Communication locale(String v) { this.locale = v; return this; }
        Communication editor(String v) { this.editor = v; return this; }
        Communication source(String v) { this.source = v; return this; }
        Communication audience(String v) { this.audience = v; return this; }
        Communication publishedAt(String v) { this.publishedAt = v; return this; }
        Communication createdAt(String v) { this.createdAt = v; return this; }
        Communication updatedAt(String v) { this.updatedAt = v; return this; }
    }

    /** 一行 {@code notifications}。 */
    private static final class NotificationRow {
        private String id;
        private long notifiableId;
        private String data;
        private String createdAt;
        private String updatedAt;
    }

    private void insertCommunication(Connection conn, Communication c) throws SQLException {
        String sql = "INSERT INTO " + q(schema.table("communications")) + " ("
                + q("legacy_key") + ", " + q("title") + ", " + q("kind") + ", " + q("status")
                + ", " + q("locale") + ", " + q("editor") + ", " + q("source") + ", "
                + q("audience") + ", " + q("published_at") + ", " + q("created_at") + ", "
                + q("updated_at") + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, c.legacyKey);
            ps.setString(2, c.title);
            ps.setString(3, c.kind);
            ps.setString(4, c.status);
            ps.setString(5, c.locale);
            ps.setString(6, c.editor);
            ps.setString(7, c.source);
            ps.setString(8, c.audience);
            setNullableString(ps, 9, c.publishedAt);
            setNullableString(ps, 10, c.createdAt);
            setNullableString(ps, 11, c.updatedAt);
            ps.executeUpdate();
        }
    }

    private static void setNullableString(PreparedStatement ps, int index, String value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    /**
     * 把 {@link ResultSet} 里的时间列读成 PHP 的 {@code Y-m-d H:i:s}。
     *
     * <p>不同驱动对 {@code TIMESTAMP}/{@code DATETIME} 的 {@code getString()} 结果不同
     * （MySQL 给 {@code 2026-10-24 12:00:00}，SQLite 给原样文本，SQL Server 可能带
     * {@code .0000000}）。这里统一裁到 19 个字符，保证回填出来的时间戳格式与 PHP 一致。
     */
    private static String dbDate(ResultSet rs, int index) throws SQLException {
        String raw = rs.getString(index);
        if (raw == null) {
            return null;
        }
        String trimmed = PhpString.trim(raw);
        if (trimmed.length() > 19 && trimmed.charAt(10) == ' ') {
            return trimmed.substring(0, 19);
        }
        return trimmed;
    }

    /** {@code mixed} 值 → 整数（用于 {@code communication_id}）；非整数返回 {@code null}。 */
    private static Long asLong(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private boolean exists(Connection conn, String table) {
        try (Statement st = conn.createStatement()) {
            st.executeQuery("SELECT 1 FROM " + q(table) + " WHERE 1 = 0").close();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean exists(Connection conn, String table, String column, String value)
            throws SQLException {
        String sql = "SELECT 1 FROM " + q(table) + " WHERE " + q(column) + " = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean hasColumn(Connection conn, String table, String column) {
        try (Statement st = conn.createStatement()) {
            st.executeQuery("SELECT " + q(column) + " FROM " + q(table) + " WHERE 1 = 0").close();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private void execute(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private com.neoskin.db.SqlDialect dialect() {
        return schema.dialect();
    }

    private String q(String identifier) {
        return dialect().quote(identifier);
    }
}
