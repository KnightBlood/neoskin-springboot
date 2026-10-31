package com.neoskin.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.neoskin.config.NeoSkinSettings;

/**
 * 表结构安装器。
 *
 * <p>源项目用 27 个 Laravel migration 建出 17 张表。本移植不使用 Flyway / Liquibase
 * （{@code flyway-database-sqlite} 与 {@code flyway-database-mariadb} 在 Maven Central
 * 上并不存在，无法覆盖 5 种数据库），而是用纯 JDBC 复刻同一套 DDL，从而：
 * <ul>
 *   <li>5 种数据库（SQLite / MySQL / MariaDB / PostgreSQL / SQL Server）行为一致；</li>
 *   <li>建出来的表结构与 PHP 版逐字段一致，两边可以互相接管同一个库；</li>
 *   <li>同时写入 Laravel 的 {@code migrations} 表，之后在 PHP 侧执行
 *       {@code php artisan migrate} 不会重复建表。</li>
 * </ul>
 *
 * <p>字段级细节（长度、默认值、可空性、索引名）全部对照
 * {@code database/migrations/} 与 Laravel 13 的 Schema Grammar 得到。
 */
@Component
public class SchemaInstaller {

    private static final Logger log = LoggerFactory.getLogger(SchemaInstaller.class);

    /**
     * 只负责<b>建表</b>的 migration（23 个）。
     *
     * <p>源项目 27 个 migration 里有 4 个会写数据，它们不在这里登记：
     * <ul>
     *   <li>{@code 2016_11_18_134542_import_options} —— 灌入 {@code config/options.php}；</li>
     *   <li>{@code 2025_06_29_064944_add_max_texture_width_option} —— 写入 8192；</li>
     *   <li>{@code 2026_10_24_000001_create_communications} —— 建表<b>并且</b>回填公告/站内信；</li>
     *   <li>{@code 2026_10_25_000001_hash_oauth_client_secrets} —— 把明文密钥哈希化。</li>
     * </ul>
     *
     * <p>这 4 个由 {@link com.neoskin.migration.DataMigrator} 执行并登记。若在这里提前登记，
     * 数据迁移会被当成「已完成」而跳过，结果是新库里一个选项都没有——这正是拆开的原因。
     */
    private static final List<String> SCHEMA_MIGRATIONS = List.of(
            "2016_06_01_000001_create_oauth_auth_codes_table",
            "2016_06_01_000002_create_oauth_access_tokens_table",
            "2016_06_01_000003_create_oauth_refresh_tokens_table",
            "2016_06_01_000004_create_oauth_clients_table",
            "2016_06_01_000005_create_oauth_personal_access_clients_table",
            "2016_11_18_133939_create_all_tables",
            "2018_07_26_130617_add_verification_to_users_table",
            "2018_08_21_105514_add_remember_token_to_users_table",
            "2019_03_01_131420_add_tid_skin",
            "2019_03_13_130311_rename_players_table_columns",
            "2019_03_14_174727_create_closet",
            "2019_03_16_162603_remove_likes_field",
            "2019_03_23_171728_create_report_table",
            "2019_05_05_103143_add_likes_field",
            "2019_07_03_094434_create_notifications_table",
            "2019_07_05_222912_create_jobs_table",
            "2019_09_05_130811_create_language_lines_table",
            "2019_12_14_095751_update_ip_field",
            "2020_03_10_145738_lengthen_ip_field",
            "2020_06_26_090510_add_o_auth_provider_field",
            "2020_06_28_155519_add_user_locale_field",
            "2021_04_14_181300_create_scope_table",
            "2021_06_06_111049_add_is_dark_mode_field");

    private final DataSource dataSource;
    private final SqlDialect dialect;
    private final String prefix;

    public SchemaInstaller(DataSource dataSource, NeoSkinSettings settings) {
        this.dataSource = dataSource;
        this.dialect = SqlDialect.of(settings.getDatabase().getConnection());
        this.prefix = settings.getDatabase().getPrefix() == null ? "" : settings.getDatabase().getPrefix();
    }

    public SqlDialect dialect() {
        return dialect;
    }

    public String prefix() {
        return prefix;
    }

    /** 带前缀的表名。 */
    public String table(String name) {
        return prefix + name;
    }

    /**
     * 幂等建表。已存在的表跳过；全部建完后补齐 migrations 记录。
     *
     * @return 本次新建的表数量
     */
    public int install() {
        int created = 0;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            for (TableSpec spec : tables()) {
                if (exists(conn, spec.name)) {
                    continue;
                }
                execute(conn, spec.createSql());
                for (String indexSql : spec.indexes) {
                    execute(conn, indexSql);
                }
                created++;
                log.info("created table {}", spec.name);
            }
            recordMigrations(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to install schema", e);
        }
        return created;
    }

    /** 是否已安装（users 表存在即视为已安装）。 */
    public boolean isInstalled() {
        try (Connection conn = dataSource.getConnection()) {
            return exists(conn, table("users"));
        } catch (SQLException e) {
            return false;
        }
    }

    private void recordMigrations(Connection conn) throws SQLException {
        ensureMigrationsTable(conn);
        for (String migration : SCHEMA_MIGRATIONS) {
            recordMigration(conn, migration);
        }
    }

    /**
     * 建 {@code migrations} 表（Laravel 的 bookkeeping 表）。已存在则跳过。
     *
     * <p>公开是为了让 {@link com.neoskin.migration.DataMigrator} 在只跑数据迁移
     * （表结构已经由 PHP 版建好）时也能登记自己的执行记录。
     */
    public void ensureMigrationsTable() {
        try (Connection conn = dataSource.getConnection()) {
            ensureMigrationsTable(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to ensure migrations table", e);
        }
    }

    private void ensureMigrationsTable(Connection conn) throws SQLException {
        String migrations = table("migrations");
        if (exists(conn, migrations)) {
            return;
        }
        execute(conn, "CREATE TABLE " + q(migrations) + " ("
                + dialect.autoIncrementPk("id", false)
                + ", " + q("migration") + " " + dialect.varchar(255) + " NOT NULL"
                + ", " + q("batch") + " " + dialect.intType() + " NOT NULL"
                + ")");
    }

    /** 某个 migration 是否已经执行过（对应 Laravel {@code migrations} 表里的一行）。 */
    public boolean isMigrationRecorded(String migration) {
        String sql = "SELECT 1 FROM " + q(table("migrations"))
                + " WHERE " + q("migration") + " = ?";
        try (Connection conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, migration);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /** 登记一个已执行的 migration；重复登记是幂等的。 */
    public void recordMigration(String migration) {
        try (Connection conn = dataSource.getConnection()) {
            ensureMigrationsTable(conn);
            recordMigration(conn, migration);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to record migration " + migration, e);
        }
    }

    private void recordMigration(Connection conn, String migration) throws SQLException {
        String migrations = table("migrations");
        // 逐条「先查后插」，避免依赖各数据库对 SELECT ... WHERE 无 FROM 的不同支持
        String check = "SELECT 1 FROM " + q(migrations) + " WHERE " + q("migration") + " = ?";
        String insert = "INSERT INTO " + q(migrations) + " (" + q("migration") + ", " + q("batch") + ")"
                + " VALUES (?, 1)";
        try (var ps = conn.prepareStatement(check)) {
            ps.setString(1, migration);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    return;
                }
            }
        }
        try (var ps = conn.prepareStatement(insert)) {
            ps.setString(1, migration);
            ps.executeUpdate();
        }
    }

    private boolean exists(Connection conn, String table) {
        String sql = "SELECT 1 FROM " + q(table) + " WHERE 1 = 0";
        try (Statement st = conn.createStatement()) {
            st.executeQuery(sql).close();
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

    private String q(String identifier) {
        return dialect.quote(identifier);
    }

    // ================================================================== 表定义

    /** 一张表的 DDL 描述。 */
    private final class TableSpec {
        final String name;
        final List<String> columns = new ArrayList<>();
        final List<String> indexes = new ArrayList<>();

        TableSpec(String name) {
            this.name = name;
        }

        TableSpec col(String definition) {
            columns.add(definition);
            return this;
        }

        TableSpec idx(String... cols) {
            String indexName = name + "_" + String.join("_", cols) + "_index";
            indexes.add(dialect.index(indexName, name, cols));
            return this;
        }

        TableSpec uniq(String... cols) {
            String indexName = name + "_" + String.join("_", cols) + "_unique";
            StringBuilder sql = new StringBuilder("CREATE UNIQUE INDEX ")
                    .append(dialect.quote(indexName)).append(" ON ")
                    .append(dialect.quote(name)).append(" (");
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(dialect.quote(cols[i]));
            }
            sql.append(')');
            // SQL Server 唯一索引把多个 NULL 当重复值；可空列需要过滤索引
            //（Laravel 的 sqlsrv grammar 同样生成 WHERE <col> IS NOT NULL）。
            if (dialect.filteredUniqueIndex()) {
                List<String> filters = new ArrayList<>();
                for (String col : cols) {
                    if (isNullable(col)) {
                        filters.add(dialect.quote(col) + " IS NOT NULL");
                    }
                }
                if (!filters.isEmpty()) {
                    sql.append(" WHERE ").append(String.join(" AND ", filters));
                }
            }
            indexes.add(sql.toString());
            return this;
        }

        /** 列定义里没有 NOT NULL 即视为可空。 */
        private boolean isNullable(String column) {
            String quoted = dialect.quote(column);
            for (String definition : columns) {
                if (definition.startsWith(quoted + " ")) {
                    return !definition.contains("NOT NULL");
                }
            }

            return true;
        }

        String createSql() {
            return "CREATE TABLE " + dialect.quote(name) + " ("
                    + String.join(", ", columns) + ")" + dialect.tableSuffix();
        }
    }

    private List<TableSpec> tables() {
        List<TableSpec> list = new ArrayList<>();
        list.add(users());
        list.add(players());
        list.add(textures());
        list.add(options());
        list.add(userCloset());
        list.add(reports());
        list.add(notifications());
        list.add(jobs());
        list.add(languageLines());
        list.add(scopes());
        list.add(oauthAuthCodes());
        list.add(oauthAccessTokens());
        list.add(oauthRefreshTokens());
        list.add(oauthClients());
        list.add(oauthPersonalAccessClients());
        list.add(communications());
        return list;
    }

    /**
     * 测试/诊断用：全部建表与索引 DDL（不含 migrations 表）。
     *
     * <p>方言差异（类型名、{@code IDENTITY}、过滤唯一索引）可以在不连真实数据库的
     * 情况下静态核对；SQL Server 的真实验证在 CI 矩阵里。</p>
     */
    List<String> ddlStatements() {
        List<String> out = new ArrayList<>();
        for (TableSpec spec : tables()) {
            out.add(spec.createSql());
            out.addAll(spec.indexes);
        }
        return out;
    }

    private TableSpec users() {
        return new TableSpec(table("users"))
                .col(dialect.autoIncrementPk("uid", false))
                .col(q("email") + " " + dialect.varchar(100) + " NOT NULL")
                .col(q("nickname") + " " + dialect.varchar(50) + " NOT NULL DEFAULT ''")
                .col(q("locale") + " " + dialect.varchar(255) + " NULL")
                .col(q("score") + " " + dialect.intType() + " NOT NULL")
                .col(q("avatar") + " " + dialect.intType() + " NOT NULL DEFAULT 0")
                .col(q("password") + " " + dialect.varchar(255) + " NOT NULL")
                .col(q("ip") + " " + dialect.varchar(45) + " NOT NULL")
                .col(q("is_dark_mode") + " " + dialect.bool() + " NOT NULL DEFAULT " + dialect.boolLiteral(false))
                .col(q("permission") + " " + dialect.intType() + " NOT NULL DEFAULT 0")
                .col(q("last_sign_at") + " " + dialect.dateTime() + " NOT NULL")
                .col(q("register_at") + " " + dialect.dateTime() + " NOT NULL")
                .col(q("verified") + " " + dialect.bool() + " NOT NULL DEFAULT " + dialect.boolLiteral(false))
                .col(q("verification_token") + " " + dialect.varchar(255) + " NOT NULL DEFAULT ''")
                .col(q("remember_token") + " " + dialect.varchar(100) + " NULL");
    }

    private TableSpec players() {
        return new TableSpec(table("players"))
                .col(dialect.autoIncrementPk("pid", false))
                .col(q("uid") + " " + dialect.intType() + " NOT NULL")
                .col(q("name") + " " + dialect.varchar(50) + " NOT NULL")
                .col(q("tid_cape") + " " + dialect.intType() + " NOT NULL DEFAULT 0")
                .col(q("last_modified") + " " + dialect.dateTime() + " NOT NULL")
                .col(q("tid_skin") + " " + dialect.intType() + " NOT NULL DEFAULT -1");
    }

    private TableSpec textures() {
        return new TableSpec(table("textures"))
                .col(dialect.autoIncrementPk("tid", false))
                .col(q("name") + " " + dialect.varchar(50) + " NOT NULL")
                .col(q("type") + " " + dialect.varchar(10) + " NOT NULL")
                .col(q("hash") + " " + dialect.varchar(64) + " NOT NULL")
                .col(q("size") + " " + dialect.intType() + " NOT NULL")
                .col(q("uploader") + " " + dialect.intType() + " NOT NULL")
                .col(q("public") + " " + dialect.tinyInt() + " NOT NULL")
                .col(q("upload_at") + " " + dialect.dateTime() + " NOT NULL")
                .col(q("likes") + " " + dialect.unsignedInt() + " NOT NULL DEFAULT 0");
    }

    private TableSpec options() {
        return new TableSpec(table("options"))
                .col(dialect.autoIncrementPk("id", false))
                .col(q("option_name") + " " + dialect.varchar(50) + " NOT NULL")
                .col(q("option_value") + " " + dialect.longText() + " NOT NULL");
    }

    private TableSpec userCloset() {
        return new TableSpec(table("user_closet"))
                .col(q("user_uid") + " " + dialect.intType() + " NOT NULL")
                .col(q("texture_tid") + " " + dialect.intType() + " NOT NULL")
                .col(q("item_name") + " " + dialect.text() + " NULL");
    }

    private TableSpec reports() {
        return new TableSpec(table("reports"))
                .col(dialect.autoIncrementPk("id", false))
                .col(q("tid") + " " + dialect.intType() + " NOT NULL")
                .col(q("uploader") + " " + dialect.intType() + " NOT NULL")
                .col(q("reporter") + " " + dialect.intType() + " NOT NULL")
                .col(q("reason") + " " + dialect.longText() + " NOT NULL")
                .col(q("status") + " " + dialect.intType() + " NOT NULL")
                .col(q("report_at") + " " + dialect.dateTime() + " NOT NULL");
    }

    private TableSpec notifications() {
        return new TableSpec(table("notifications"))
                .col(q("id") + " " + dialect.varchar(36) + " NOT NULL PRIMARY KEY")
                .col(q("type") + " " + dialect.varchar(255) + " NOT NULL")
                .col(q("notifiable_type") + " " + dialect.varchar(255) + " NOT NULL")
                .col(q("notifiable_id") + " " + dialect.unsignedBigInt() + " NOT NULL")
                .col(q("data") + " " + dialect.text() + " NOT NULL")
                .col(q("read_at") + " " + dialect.timestamp() + " NULL")
                .col(q("created_at") + " " + dialect.timestamp() + " NULL")
                .col(q("updated_at") + " " + dialect.timestamp() + " NULL")
                .idx("notifiable_type", "notifiable_id");
    }

    private TableSpec jobs() {
        return new TableSpec(table("jobs"))
                .col(dialect.autoIncrementPk("id", true))
                .col(q("queue") + " " + dialect.varchar(255) + " NOT NULL")
                .col(q("payload") + " " + dialect.longText() + " NOT NULL")
                .col(q("attempts") + " " + dialect.unsignedTinyInt() + " NOT NULL")
                .col(q("reserved_at") + " " + dialect.unsignedInt() + " NULL")
                .col(q("available_at") + " " + dialect.unsignedInt() + " NOT NULL")
                .col(q("created_at") + " " + dialect.unsignedInt() + " NOT NULL")
                .idx("queue");
    }

    private TableSpec languageLines() {
        return new TableSpec(table("language_lines"))
                .col(dialect.autoIncrementPk("id", false))
                .col(q("group") + " " + dialect.varchar(255) + " NOT NULL")
                .col(q("key") + " " + dialect.varchar(255) + " NOT NULL")
                .col(q("text") + " " + dialect.text() + " NOT NULL")
                .col(q("created_at") + " " + dialect.timestamp() + " NULL")
                .col(q("updated_at") + " " + dialect.timestamp() + " NULL")
                .idx("group");
    }

    private TableSpec scopes() {
        return new TableSpec(table("scopes"))
                .col(dialect.autoIncrementPk("id", false))
                .col(q("name") + " " + dialect.varchar(255) + " NOT NULL")
                .col(q("description") + " " + dialect.varchar(255) + " NOT NULL")
                .uniq("name");
    }

    private TableSpec oauthAuthCodes() {
        return new TableSpec(table("oauth_auth_codes"))
                .col(q("id") + " " + dialect.varchar(100) + " NOT NULL PRIMARY KEY")
                .col(q("user_id") + " " + dialect.unsignedBigInt() + " NOT NULL")
                .col(q("client_id") + " " + dialect.unsignedBigInt() + " NOT NULL")
                .col(q("scopes") + " " + dialect.text() + " NULL")
                .col(q("revoked") + " " + dialect.bool() + " NOT NULL")
                .col(q("expires_at") + " " + dialect.dateTime() + " NULL")
                .idx("user_id");
    }

    private TableSpec oauthAccessTokens() {
        return new TableSpec(table("oauth_access_tokens"))
                .col(q("id") + " " + dialect.varchar(100) + " NOT NULL PRIMARY KEY")
                .col(q("user_id") + " " + dialect.unsignedBigInt() + " NULL")
                .col(q("client_id") + " " + dialect.unsignedBigInt() + " NOT NULL")
                .col(q("name") + " " + dialect.varchar(255) + " NULL")
                .col(q("scopes") + " " + dialect.text() + " NULL")
                .col(q("revoked") + " " + dialect.bool() + " NOT NULL")
                .col(q("created_at") + " " + dialect.timestamp() + " NULL")
                .col(q("updated_at") + " " + dialect.timestamp() + " NULL")
                .col(q("expires_at") + " " + dialect.dateTime() + " NULL")
                .idx("user_id");
    }

    private TableSpec oauthRefreshTokens() {
        return new TableSpec(table("oauth_refresh_tokens"))
                .col(q("id") + " " + dialect.varchar(100) + " NOT NULL PRIMARY KEY")
                .col(q("access_token_id") + " " + dialect.varchar(100) + " NOT NULL")
                .col(q("revoked") + " " + dialect.bool() + " NOT NULL")
                .col(q("expires_at") + " " + dialect.dateTime() + " NULL")
                .idx("access_token_id");
    }

    private TableSpec oauthClients() {
        return new TableSpec(table("oauth_clients"))
                .col(dialect.autoIncrementPk("id", true))
                .col(q("user_id") + " " + dialect.unsignedBigInt() + " NULL")
                .col(q("name") + " " + dialect.varchar(255) + " NOT NULL")
                .col(q("secret") + " " + dialect.varchar(100) + " NULL")
                .col(q("provider") + " " + dialect.varchar(255) + " NULL")
                .col(q("redirect") + " " + dialect.text() + " NOT NULL")
                .col(q("personal_access_client") + " " + dialect.bool() + " NOT NULL")
                .col(q("password_client") + " " + dialect.bool() + " NOT NULL")
                .col(q("revoked") + " " + dialect.bool() + " NOT NULL")
                .col(q("created_at") + " " + dialect.timestamp() + " NULL")
                .col(q("updated_at") + " " + dialect.timestamp() + " NULL")
                .idx("user_id");
    }

    private TableSpec oauthPersonalAccessClients() {
        return new TableSpec(table("oauth_personal_access_clients"))
                .col(dialect.autoIncrementPk("id", true))
                .col(q("client_id") + " " + dialect.unsignedBigInt() + " NOT NULL")
                .col(q("created_at") + " " + dialect.timestamp() + " NULL")
                .col(q("updated_at") + " " + dialect.timestamp() + " NULL");
    }

    private TableSpec communications() {
        return new TableSpec(table("communications"))
                .col(dialect.autoIncrementPk("id", true))
                .col(q("author_id") + " " + dialect.unsignedInt() + " NULL")
                .col(q("legacy_key") + " " + dialect.varchar(100) + " NULL")
                .col(q("delivery_token") + " " + dialect.varchar(32) + " NULL")
                .col(q("title") + " " + dialect.varchar(100) + " NOT NULL")
                .col(q("kind") + " " + dialect.varchar(16) + " NOT NULL DEFAULT 'announcement'")
                .col(q("status") + " " + dialect.varchar(16) + " NOT NULL DEFAULT 'draft'")
                .col(q("locale") + " " + dialect.varchar(20) + " NOT NULL DEFAULT ''")
                .col(q("editor") + " " + dialect.varchar(16) + " NOT NULL DEFAULT 'blocks'")
                .col(q("source") + " " + dialect.longText() + " NULL")
                .col(q("blocks") + " " + dialect.longText() + " NULL")
                .col(q("audience") + " " + dialect.text() + " NULL")
                .col(q("published_at") + " " + dialect.timestamp() + " NULL")
                .col(q("created_at") + " " + dialect.timestamp() + " NULL")
                .col(q("updated_at") + " " + dialect.timestamp() + " NULL")
                .uniq("legacy_key")
                .idx("status");
    }
}
