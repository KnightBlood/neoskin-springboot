package com.neoskin.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.service.Option;
import com.neoskin.service.OptionDefaults;
import com.neoskin.service.hash.LaravelHash;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * {@link DataMigrator} 的行为测试（SQLite 临时库）。
 *
 * <p>覆盖 4 个数据迁移与它们之间<b>相互依赖</b>的部分：
 * <ul>
 *   <li>{@code import_options} 必须在 {@code create_communications} <b>之前</b>跑完——
 *       后者是<b>读</b> {@code options} 表来决定回填哪些公告的。真实升级路径也是这个顺序，
 *       所以这里的用例先只跑前两个迁移、再手工改公告、最后跑回填；</li>
 *   <li>{@code create_communications} 会回写 {@code notifications.data}，回写的 JSON
 *       必须能被 PHP 版逐字节读回（{@code JSON_UNESCAPED_UNICODE} + {@code \/}）；</li>
 *   <li>{@code hash_oauth_client_secrets} 的跳过条件依赖 {@code Hash::isHashed()} 与
 *       {@code needsRehash()}，两者的「只认 {@code $2y$}」行为很容易写错。</li>
 * </ul>
 *
 * <p>为什么只测 SQLite：CI 里没有其他四种数据库的实例，而 4 个迁移的 SQL 都只用
 * {@code SELECT/INSERT/UPDATE} 与 {@code SchemaInstaller} 建好的表，
 * 方言差异已在 {@code SchemaInstallerTest} 与 {@code SqlDialect} 里覆盖。
 */
class DataMigratorTest {

    // ================================================================== 环境

    /** 一个临时库的全套组件；{@code close()} 关闭连接池。 */
    private static final class Env implements AutoCloseable {
        final HikariDataSource ds;
        final NeoSkinSettings settings;
        final SchemaInstaller schema;
        final Option option;
        final DataMigrator migrator;

        Env(Path dir) throws Exception {
            Path file = dir.resolve("neoskin.sqlite");
            Files.createFile(file);

            settings = new NeoSkinSettings();
            settings.getDatabase().setConnection("sqlite");
            settings.getDatabase().setName(file.toAbsolutePath().toString());

            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
            cfg.setDriverClassName("org.sqlite.JDBC");
            cfg.setMaximumPoolSize(1);
            ds = new HikariDataSource(cfg);

            // Option 在未安装时保持空集合（PHP 行为），这里模拟安装完成
            Path storage = dir.resolve("storage");
            Files.createDirectories(storage);
            Files.createFile(storage.resolve("install.lock"));

            AppPaths paths = new AppPaths(dir, settings);
            schema = new SchemaInstaller(ds, settings);
            schema.install();
            option = new Option(ds, schema, paths);
            migrator = new DataMigrator(ds, schema, option, settings);
        }

        @Override
        public void close() {
            ds.close();
        }

        // ---------------------------------------------------------- 查询辅助

        Object scalar(String sql, Object... args) throws Exception {
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getObject(1) : null;
                }
            }
        }

        int count(String sql, Object... args) throws Exception {
            Object value = scalar(sql, args);
            return value == null ? 0 : ((Number) value).intValue();
        }

        void exec(String sql, Object... args) throws Exception {
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
        }

        /** 取单行多列（按列名）。 */
        Map<String, Object> row(String sql, Object... args) throws Exception {
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    var meta = rs.getMetaData();
                    for (int c = 1; c <= meta.getColumnCount(); c++) {
                        out.put(meta.getColumnLabel(c), rs.getObject(c));
                    }
                    return out;
                }
            }
        }

        List<Map<String, Object>> rows(String sql, Object... args) throws Exception {
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> out = new ArrayList<>();
                    var meta = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        for (int c = 1; c <= meta.getColumnCount(); c++) {
                            m.put(meta.getColumnLabel(c), rs.getObject(c));
                        }
                        out.add(m);
                    }
                    return out;
                }
            }
        }

        void insertNotification(String id, long notifiableId, String data,
                String createdAt, String updatedAt) throws Exception {
            exec("INSERT INTO notifications (id, type, notifiable_type, notifiable_id, data,"
                            + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    id, DataMigrator.SITE_MESSAGE_TYPE, "App\\Models\\User", notifiableId,
                    data, createdAt, updatedAt);
        }

        void insertClient(long id, String secret) throws Exception {
            exec("INSERT INTO oauth_clients (id, name, secret, redirect, personal_access_client,"
                            + " password_client, revoked) VALUES (?, ?, ?, ?, 0, 0, 0)",
                    id, "client-" + id, secret, "https://example.com/callback");
        }
    }

    // ================================================================== 1. import_options

    @Test
    @DisplayName("import_options：54 个 config 键 + version 插值 + {year} 替换")
    void importsOptions(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir)) {
            assertEquals(List.of(
                    DataMigrator.IMPORT_OPTIONS,
                    DataMigrator.MAX_TEXTURE_WIDTH,
                    DataMigrator.CREATE_COMMUNICATIONS,
                    DataMigrator.HASH_OAUTH_SECRETS),
                    env.migrator.migrate(), "首次运行应当依次执行 4 个数据迁移");

            // 54 个 config 键 + max_texture_width = 55
            assertEquals(55, env.count("SELECT COUNT(*) FROM options"));

            assertEquals("6.0.11_patch", env.option.get("version"));
            assertEquals("Welcome to NeoSkin 6.0.11_patch!",
                    env.option.get("announcement"),
                    "announcement 的 {version} 必须在写入前替换，数据库里不存模板");

            String copyright = (String) env.option.get("copyright_text");
            int year = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).getYear();
            assertTrue(copyright.contains(String.valueOf(year)),
                    "copyright_text 的 {year} 应当替换成当前年份: " + copyright);
            assertFalse(copyright.contains("{year}"), copyright);

            assertEquals("8192", env.option.get("max_texture_width", null, true),
                    "max_texture_width 不在 config/options.php 里，由独立迁移写入");

            // 二次调用不得重复执行
            assertTrue(env.migrator.migrate().isEmpty(), "已登记的迁移必须跳过");
            assertEquals(55, env.count("SELECT COUNT(*) FROM options"));
        }
    }

    @Test
    @DisplayName("import_options 写入的键集合与 OptionDefaults 完全一致（除 version 被覆盖）")
    void importsEveryConfigKey(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir)) {
            env.migrator.migrate(DataMigrator.IMPORT_OPTIONS);
            List<String> names = new ArrayList<>();
            for (Map<String, Object> row : env.rows("SELECT option_name FROM options ORDER BY id")) {
                names.add((String) row.get("option_name"));
            }
            assertEquals(List.copyOf(OptionDefaults.all().keySet()), names,
                    "键名与顺序都必须与 config/options.php 一致");
        }
    }

    // ================================================================== 2. max_texture_width

    @Test
    @DisplayName("add_max_texture_width_option：单独执行也能落库")
    void addsMaxTextureWidth(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir)) {
            assertTrue(env.migrator.migrate(DataMigrator.MAX_TEXTURE_WIDTH));
            assertEquals("8192", env.scalar(
                    "SELECT option_value FROM options WHERE option_name = 'max_texture_width'"));
            assertFalse(env.migrator.migrate(DataMigrator.MAX_TEXTURE_WIDTH));
        }
    }

    // ================================================================== 3. 公告回填

    @Test
    @DisplayName("create_communications：announcement / announcement_<locale> → 两条公告")
    void backfillsAnnouncements(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir)) {
            env.migrator.migrate(DataMigrator.IMPORT_OPTIONS);
            env.migrator.migrate(DataMigrator.MAX_TEXTURE_WIDTH);

            env.option.set("announcement", "  Maintenance tonight  ");
            env.option.set("announcement_zh_CN", "今晚维护");

            assertTrue(env.migrator.migrate(DataMigrator.CREATE_COMMUNICATIONS));

            List<Map<String, Object>> rows = env.rows(
                    "SELECT legacy_key, title, kind, status, locale, editor, source, audience,"
                            + " published_at FROM communications WHERE kind = 'announcement'"
                            + " ORDER BY id");
            assertEquals(2, rows.size());

            Map<String, Object> base = rows.get(0);
            assertEquals("option:announcement", base.get("legacy_key"));
            assertEquals("站点公告", base.get("title"));
            assertEquals("announcement", base.get("kind"));
            assertEquals("published", base.get("status"));
            assertEquals("", base.get("locale"), "基键的 locale 是空串，不是 zh_CN");
            assertEquals("source", base.get("editor"));
            assertEquals("  Maintenance tonight  ", base.get("source"),
                    "source 存原值，只 trim 用于判空");
            assertEquals(null, base.get("audience"));
            assertNotNull(base.get("published_at"));

            Map<String, Object> localized = rows.get(1);
            assertEquals("option:announcement_zh_CN", localized.get("legacy_key"));
            assertEquals("zh_CN", localized.get("locale"),
                    "locale 必须来自 substr($name, 13) 而不是分隔后的第二段");
            assertEquals("今晚维护", localized.get("source"));
        }
    }

    @Test
    @DisplayName("announcement 值 trim 后为空时跳过，其余非空键照常回填")
    void skipsBlankAnnouncement(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir)) {
            env.migrator.migrate(DataMigrator.IMPORT_OPTIONS);
            env.option.set("announcement", " \t\n\r\0\u000B ");
            env.option.set("announcement_en", "Hello");
            env.migrator.migrate(DataMigrator.CREATE_COMMUNICATIONS);

            assertEquals(0, env.count(
                    "SELECT COUNT(*) FROM communications WHERE legacy_key = 'option:announcement'"));
            assertEquals(1, env.count("SELECT COUNT(*) FROM communications"));
        }
    }

    @Test
    @DisplayName("公告回填幂等：legacy_key 已存在时不再插入")
    void announcementBackfillIsIdempotent(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir)) {
            env.migrator.migrate(DataMigrator.IMPORT_OPTIONS);
            env.migrator.migrate(DataMigrator.CREATE_COMMUNICATIONS);
            int first = env.count("SELECT COUNT(*) FROM communications");
            assertEquals(1, first);

            // 抹掉登记记录，模拟「migrations 表被清空但数据还在」
            env.exec("DELETE FROM migrations WHERE migration = ?",
                    DataMigrator.CREATE_COMMUNICATIONS);
            assertTrue(env.migrator.migrate(DataMigrator.CREATE_COMMUNICATIONS));
            assertEquals(first, env.count("SELECT COUNT(*) FROM communications"));
        }
    }

    // ================================================================== 4. 站内信回填

    @Test
    @DisplayName("create_communications：站内信 → communications + 回写 communication_id")
    void backfillsSiteMessage(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir)) {
            env.migrator.migrate(DataMigrator.IMPORT_OPTIONS);
            env.migrator.migrate(DataMigrator.MAX_TEXTURE_WIDTH);
            env.insertNotification("n-1", 42L,
                    "{\"title\":\"站内信\",\"content\":\"你好\\/世界\"}",
                    "2026-10-24 12:00:00", "2026-10-24 12:30:00");

            env.migrator.migrate(DataMigrator.CREATE_COMMUNICATIONS);

            Map<String, Object> c = env.row(
                    "SELECT title, kind, status, locale, editor, source, audience,"
                            + " published_at, created_at, updated_at FROM communications"
                            + " WHERE legacy_key = 'notification:n-1'");
            assertNotNull(c, "必须按 legacy_key 建一条 communications 行");
            assertEquals("站内信", c.get("title"));
            assertEquals("message", c.get("kind"));
            assertEquals("published", c.get("status"));
            assertEquals("", c.get("locale"));
            assertEquals("source", c.get("editor"));
            assertEquals("你好/世界", c.get("source"));
            assertEquals("{\"receiver\":\"uid\",\"uid\":42}", c.get("audience"),
                    "audience 用裸 json_encode，键顺序 receiver 在前");
            // 时间戳用**那条通知自己的**，不是 now()
            assertEquals("2026-10-24 12:00:00", c.get("published_at"));
            assertEquals("2026-10-24 12:00:00", c.get("created_at"));
            assertEquals("2026-10-24 12:30:00", c.get("updated_at"));

            Object id = c.get("id") != null ? c.get("id")
                    : env.scalar("SELECT id FROM communications WHERE legacy_key = 'notification:n-1'");
            String data = (String) env.scalar("SELECT data FROM notifications WHERE id = 'n-1'");
            assertEquals("{\"title\":\"站内信\",\"content\":\"你好\\/世界\",\"communication_id\":"
                    + id + "}", data,
                    "回写用 JSON_UNESCAPED_UNICODE：中文原样、斜杠仍转义、新键追加在末尾");
        }
    }

    @Test
    @DisplayName("communication_id 已存在且指向真实行时跳过；指向不存在的行时重新回填")
    void respectsExistingCommunicationId(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir)) {
            env.migrator.migrate(DataMigrator.IMPORT_OPTIONS);
            env.migrator.migrate(DataMigrator.MAX_TEXTURE_WIDTH);

            // 第一轮：正常回填
            env.insertNotification("n-1", 7L, "{\"title\":\"A\",\"content\":\"x\"}",
                    "2026-10-01 00:00:00", "2026-10-01 00:00:00");
            env.migrator.migrate(DataMigrator.CREATE_COMMUNICATIONS);
            String afterFirst = (String) env.scalar(
                    "SELECT data FROM notifications WHERE id = 'n-1'");
            assertTrue(afterFirst.contains("communication_id"));
            assertTrue(afterFirst.contains("\"title\":\"A\""));

            // 第二轮：把登记抹掉重跑，已回填的行必须原样不动（不新增第二条）
            env.exec("DELETE FROM migrations WHERE migration = ?",
                    DataMigrator.CREATE_COMMUNICATIONS);
            env.migrator.migrate(DataMigrator.CREATE_COMMUNICATIONS);
            assertEquals(afterFirst, env.scalar("SELECT data FROM notifications WHERE id = 'n-1'"));
            // import_options 会先写一条非空的 announcement，所以公告行恒为 1；
            // 这里只数站内信产生的行，避免把公告算进来
            assertEquals(1, env.count(
                    "SELECT COUNT(*) FROM communications WHERE legacy_key = 'notification:n-1'"));

            // 第三轮：communication_id 指向不存在的行 → 视为未回填，重新插一条
            env.exec("UPDATE notifications SET data = ? WHERE id = 'n-1'",
                    "{\"title\":\"A\",\"content\":\"x\",\"communication_id\":9999}");
            env.exec("DELETE FROM migrations WHERE migration = ?",
                    DataMigrator.CREATE_COMMUNICATIONS);
            env.migrator.migrate(DataMigrator.CREATE_COMMUNICATIONS);
            assertEquals(1, env.count(
                            "SELECT COUNT(*) FROM communications WHERE legacy_key = 'notification:n-1'"),
                    "legacy_key 已存在，复用旧行而不是插新行");
            String afterThird = (String) env.scalar(
                    "SELECT data FROM notifications WHERE id = 'n-1'");
            assertFalse(afterThird.contains("9999"), afterThird);
        }
    }

    @Test
    @DisplayName("data 为坏 JSON / 非数组 / null 时跳过，且不改写原值")
    void skipsMalformedNotificationData(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir)) {
            env.migrator.migrate(DataMigrator.IMPORT_OPTIONS);
            env.migrator.migrate(DataMigrator.MAX_TEXTURE_WIDTH);

            env.insertNotification("n-bad", 1L, "{not json", "2026-10-01 00:00:00", null);
            env.insertNotification("n-null", 1L, "null", "2026-10-01 00:00:00", null);
            env.insertNotification("n-str", 1L, "\"text\"", "2026-10-01 00:00:00", null);
            env.migrator.migrate(DataMigrator.CREATE_COMMUNICATIONS);

            // import_options 写下的 announcement 会产生 1 条公告，这里只看站内信
            assertEquals(0, env.count(
                    "SELECT COUNT(*) FROM communications WHERE kind = 'message'"));
            assertEquals("{not json", env.scalar("SELECT data FROM notifications WHERE id = 'n-bad'"));
            assertEquals("null", env.scalar("SELECT data FROM notifications WHERE id = 'n-null'"));
            assertEquals("\"text\"", env.scalar("SELECT data FROM notifications WHERE id = 'n-str'"));
        }
    }

    @Test
    @DisplayName("标题缺失/为 null 用默认值，超长按码点截到 100")
    void messageTitleRules(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir)) {
            env.migrator.migrate(DataMigrator.IMPORT_OPTIONS);
            env.migrator.migrate(DataMigrator.MAX_TEXTURE_WIDTH);

            env.insertNotification("n-missing", 1L, "{\"content\":\"c\"}",
                    "2026-10-01 00:00:00", "2026-10-01 00:00:00");
            env.insertNotification("n-null-title", 2L, "{\"title\":null}",
                    "2026-10-01 00:00:00", "2026-10-01 00:00:00");
            // 120 个 emoji = 120 个码点 / 240 个 UTF-16 码元
            String emoji = "\uD83D\uDE00".repeat(120);
            env.insertNotification("n-long", 3L,
                    "{\"title\":\"" + emoji + "\",\"content\":\"\"}",
                    "2026-10-01 00:00:00", "2026-10-01 00:00:00");
            env.migrator.migrate(DataMigrator.CREATE_COMMUNICATIONS);

            assertEquals("站内信", env.scalar(
                    "SELECT title FROM communications WHERE legacy_key = 'notification:n-missing'"));
            assertEquals("站内信", env.scalar(
                    "SELECT title FROM communications WHERE legacy_key = 'notification:n-null-title'"));

            String longTitle = (String) env.scalar(
                    "SELECT title FROM communications WHERE legacy_key = 'notification:n-long'");
            assertEquals(100, longTitle.codePointCount(0, longTitle.length()),
                    "mb_substr 按码点截断，不能把 emoji 劈成半个代理项");
            assertEquals(emoji.substring(0, 200), longTitle);
        }
    }

    @Test
    @DisplayName("只有 type = App\\Notifications\\SiteMessage 的通知会被回填")
    void onlySiteMessageType(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir)) {
            env.migrator.migrate(DataMigrator.IMPORT_OPTIONS);
            env.migrator.migrate(DataMigrator.MAX_TEXTURE_WIDTH);

            env.exec("INSERT INTO notifications (id, type, notifiable_type, notifiable_id, data,"
                            + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    "n-other", "App\\Notifications\\OtherNotice", "App\\Models\\User", 1L,
                    "{\"title\":\"x\"}", "2026-10-01 00:00:00", "2026-10-01 00:00:00");
            env.migrator.migrate(DataMigrator.CREATE_COMMUNICATIONS);

            // import_options 写下的 announcement 会产生 1 条公告，这里只看站内信
            assertEquals(0, env.count(
                    "SELECT COUNT(*) FROM communications WHERE kind = 'message'"));
            assertEquals("{\"title\":\"x\"}", env.scalar(
                    "SELECT data FROM notifications WHERE id = 'n-other'"));
        }
    }

    @Test
    @DisplayName("分块读取：300 条通知都要回填（chunkById 200 的往返）")
    void backfillsMoreThanOneChunk(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir)) {
            env.migrator.migrate(DataMigrator.IMPORT_OPTIONS);
            env.migrator.migrate(DataMigrator.MAX_TEXTURE_WIDTH);
            for (int i = 0; i < 300; i++) {
                // 补零保证字典序 = 数值序（chunkById 依赖 ORDER BY id）
                env.insertNotification(String.format("n-%04d", i), i,
                        "{\"title\":\"t" + i + "\"}",
                        "2026-10-01 00:00:00", "2026-10-01 00:00:00");
            }
            env.migrator.migrate(DataMigrator.CREATE_COMMUNICATIONS);

            // 300 条站内信 + import_options 写下的 1 条公告
            assertEquals(301, env.count("SELECT COUNT(*) FROM communications"));
            assertEquals(300, env.count(
                    "SELECT COUNT(*) FROM communications WHERE kind = 'message'"));
            assertEquals(300, env.count(
                    "SELECT COUNT(*) FROM notifications WHERE data LIKE '%communication_id%'"));
        }
    }

    // ================================================================== 5. OAuth 密钥

    @Test
    @DisplayName("hash_oauth_client_secrets：明文/argon2/$2y$12$ 重哈希，$2y$10$ 保持原样")
    void hashesOauthClientSecrets(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir)) {
            LaravelHash cost10 = new LaravelHash(10);
            LaravelHash cost12 = new LaravelHash(12);
            String alreadyGood = cost10.make("keep-me");
            String staleCost = cost12.make("old-cost");
            String argon2 = "$argon2id$v=19$m=65536,t=4,p=1$NXhIdE9EdjZMM0ZoY3ZKdw"
                    + "$TTmSQK5P66jvAI7Th+4i8819X+Par0eKOOT5q8OcEU4";

            env.insertClient(1L, "plain-secret");
            env.insertClient(2L, alreadyGood);
            env.insertClient(3L, staleCost);
            env.insertClient(4L, argon2);
            env.insertClient(5L, "");          // WHERE secret <> '' 排除
            env.insertClient(6L, null);        // WHERE secret IS NOT NULL 排除

            env.migrator.migrate(DataMigrator.HASH_OAUTH_SECRETS);

            assertEquals(10, env.migrator.hash().rounds(),
                    "OAuth 密钥用 config/hashing.php 的 10，不是用户密码的 12");

            assertEquals(alreadyGood, env.scalar("SELECT secret FROM oauth_clients WHERE id = 2"),
                    "已哈希且 cost 一致的行必须逐字节保持原样");
            assertEquals("", env.scalar("SELECT secret FROM oauth_clients WHERE id = 5"));
            assertEquals(null, env.scalar("SELECT secret FROM oauth_clients WHERE id = 6"));

            for (long id : new long[] { 1, 3, 4 }) {
                String secret = (String) env.scalar(
                        "SELECT secret FROM oauth_clients WHERE id = ?", id);
                assertTrue(secret.startsWith("$2y$10$"),
                        "id=" + id + " 应当被重新哈希成 $2y$10$: " + secret);
                // 注意：迁移是对**库里已存的字符串**做 Hash::make()，不是对明文。
                // 所以 id=3 的「原文」是那个 $2y$12$ 哈希串本身，不是 'old-cost'
                // —— 这一点与 PHP 完全一致，也是本次迁移最容易被误读的地方。
                String plain = switch ((int) id) {
                    case 1 -> "plain-secret";
                    case 3 -> staleCost;
                    default -> argon2;
                };
                assertTrue(env.migrator.hash().check(plain, secret),
                        "id=" + id + " 的新哈希必须能验证被哈希的那个字符串");
            }
        }
    }

    @Test
    @DisplayName("hash_oauth_client_secrets：oauth_clients 表缺失时静默跳过")
    void hashOauthSecretsWithoutTable(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir)) {
            env.exec("DROP TABLE oauth_clients");
            assertTrue(env.migrator.migrate(DataMigrator.HASH_OAUTH_SECRETS),
                    "迁移本身仍然算执行成功（与 PHP 的 return 分支一致）");
            assertTrue(env.schema.isMigrationRecorded(DataMigrator.HASH_OAUTH_SECRETS));
        }
    }

    // ================================================================== 6. 迁移登记

    @Test
    @DisplayName("4 个数据迁移执行后一共登记 27 个 migration")
    void recordsAllMigrations(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir)) {
            assertEquals(23, env.count("SELECT COUNT(*) FROM migrations"),
                    "SchemaInstaller 只登记 23 个建表迁移");
            env.migrator.migrate();
            assertEquals(27, env.count("SELECT COUNT(*) FROM migrations"));
            for (String name : List.of(
                    DataMigrator.IMPORT_OPTIONS,
                    DataMigrator.MAX_TEXTURE_WIDTH,
                    DataMigrator.CREATE_COMMUNICATIONS,
                    DataMigrator.HASH_OAUTH_SECRETS)) {
                assertTrue(env.schema.isMigrationRecorded(name), name);
            }
        }
    }

    @Test
    @DisplayName("migrate(name) 拒绝台账之外的迁移名")
    void rejectsUnknownMigration(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir)) {
            // 注意：不能用 2016_11_18_133939_create_all_tables —— 它已被 SchemaInstaller
            // 登记，migrate(name) 会在 switch 之前直接返回 false（视为已执行）。
            IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> env.migrator.migrate("2099_01_01_000000_not_a_real_migration"));
            assertTrue(e.getMessage().contains("not a data migration"));
        }
    }

    @Test
    @DisplayName("communication_id 的回写保留原键顺序，新键追加在末尾")
    void writebackKeepsKeyOrder(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir)) {
            env.migrator.migrate(DataMigrator.IMPORT_OPTIONS);
            env.migrator.migrate(DataMigrator.MAX_TEXTURE_WIDTH);
            env.insertNotification("n-order", 1L,
                    "{\"b\":2,\"a\":1,\"content\":\"x\"}",
                    "2026-10-01 00:00:00", "2026-10-01 00:00:00");
            env.migrator.migrate(DataMigrator.CREATE_COMMUNICATIONS);

            String data = (String) env.scalar("SELECT data FROM notifications WHERE id = 'n-order'");
            assertTrue(data.startsWith("{\"b\":2,\"a\":1,\"content\":\"x\",\"communication_id\":"),
                    "insert 顺序必须保留，communication_id 追加在末尾: " + data);
        }
    }

    /** 便于调试：把整张表 dump 成文本（失败时看日志用，不在断言里）。 */
    @SuppressWarnings("unused")
    private static String dump(Env env, String table) throws Exception {
        StringBuilder sb = new StringBuilder(table).append(":\n");
        for (Map<String, Object> row : env.rows("SELECT * FROM " + table)) {
            sb.append("  ").append(row).append('\n');
        }
        return sb.toString();
    }

    /** 便于在 IDE 里单独跑一次纯 JDBC 检查（不在测试中发现异常即视为通过）。 */
    @SuppressWarnings("unused")
    private static void sanity(Env env) throws Exception {
        try (Connection conn = env.ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("SELECT 1");
        }
    }
}
