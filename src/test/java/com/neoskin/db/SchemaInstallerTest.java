package com.neoskin.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.NeoSkinSettings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 验证 {@link SchemaInstaller} 在 SQLite 上建出的 17 张表与源 migration 一致。
 *
 * <p>SQLite 是最容易在 CI 里跑起来的数据库，且它的 DDL 检查能捕获字段名/可空性/
 * 默认值的低级错误；MySQL / PostgreSQL / SQL Server 的差异集中在类型名上，
 * 由 {@link SqlDialect} 的单元测试覆盖。
 */
class SchemaInstallerTest {

    private static HikariDataSource dataSource(Path file) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(1);
        return new HikariDataSource(cfg);
    }

    private static NeoSkinSettings settings(Path file) {
        NeoSkinSettings s = new NeoSkinSettings();
        s.getDatabase().setConnection("sqlite");
        s.getDatabase().setName(file.toAbsolutePath().toString());
        return s;
    }

    @Test
    void createsAllSeventeenTables(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("test.sqlite");
        Files.createFile(file);
        try (HikariDataSource ds = dataSource(file)) {
            SchemaInstaller installer = new SchemaInstaller(ds, settings(file));
            assertEquals(16, installer.install(), "16 张业务表（migrations 表另外创建）");
            // 二次调用必须幂等
            assertEquals(0, installer.install());
            assertTrue(installer.isInstalled());

            List<String> tables = new ArrayList<>();
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            assertTrue(tables.containsAll(List.of(
                    "users", "players", "textures", "options", "user_closet", "reports",
                    "notifications", "jobs", "language_lines", "scopes",
                    "oauth_auth_codes", "oauth_access_tokens", "oauth_refresh_tokens",
                    "oauth_clients", "oauth_personal_access_clients", "communications",
                    "migrations")), "缺少表: " + tables);
        }
    }

    @Test
    void usersTableMatchesSourceMigration(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("columns.sqlite");
        Files.createFile(file);
        try (HikariDataSource ds = dataSource(file)) {
            new SchemaInstaller(ds, settings(file)).install();
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA table_info(users)")) {
                List<String> cols = new ArrayList<>();
                while (rs.next()) {
                    cols.add(rs.getString("name"));
                }
                // 顺序必须与源 migration 的最终结果一致（locale 在 nickname 之后，
                // is_dark_mode 在 ip 之后，verified / verification_token / remember_token 在末尾）
                assertEquals(List.of(
                        "uid", "email", "nickname", "locale", "score", "avatar", "password",
                        "ip", "is_dark_mode", "permission", "last_sign_at", "register_at",
                        "verified", "verification_token", "remember_token"), cols);
            }
        }
    }

    @Test
    void recordsSchemaMigrationsOnly(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("migrations.sqlite");
        Files.createFile(file);
        try (HikariDataSource ds = dataSource(file)) {
            SchemaInstaller installer = new SchemaInstaller(ds, settings(file));
            installer.install();
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM migrations")) {
                assertTrue(rs.next());
                // 源项目 27 个 migration 中，4 个写数据（import_options / max_texture_width /
                // create_communications / hash_oauth_client_secrets）留给 DataMigrator 登记，
                // 否则数据迁移会被误判为「已完成」。
                assertEquals(23, rs.getInt(1), "只登记 23 个建表 migration");
            }
            assertFalse(installer.isMigrationRecorded("2016_11_18_134542_import_options"));
            assertFalse(installer.isMigrationRecorded("2025_06_29_064944_add_max_texture_width_option"));
            assertFalse(installer.isMigrationRecorded("2026_10_24_000001_create_communications"));
            assertFalse(installer.isMigrationRecorded("2026_10_25_000001_hash_oauth_client_secrets"));
            assertTrue(installer.isMigrationRecorded("2016_11_18_133939_create_all_tables"));
        }
    }

    @Test
    void recordMigrationIsIdempotent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("idempotent.sqlite");
        Files.createFile(file);
        try (HikariDataSource ds = dataSource(file)) {
            SchemaInstaller installer = new SchemaInstaller(ds, settings(file));
            installer.install();
            installer.recordMigration("2016_11_18_134542_import_options");
            installer.recordMigration("2016_11_18_134542_import_options");
            assertTrue(installer.isMigrationRecorded("2016_11_18_134542_import_options"));
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM migrations")) {
                assertTrue(rs.next());
                assertEquals(24, rs.getInt(1), "重复登记不得产生重复行");
            }
        }
    }
}
