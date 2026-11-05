package com.neoskin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;

/**
 * 数据库矩阵引导测试（GitHub Actions 的 5 种数据库 job 各跑一份）。
 *
 * <p>与 {@link ApplicationContextTest} 的区别：容器的 {@code DB_CONNECTION}
 * 来自 CI 环境变量（mysql / mariadb / pgsql / sqlsrv），也就是「应用真的
 * 在目标数据库上完成安装建表 + 引导」。本机开发（无这些变量）自动跳过，
 * 常规矩阵覆盖仍由 {@code SchemaInstallerTest}（SQLite）与
 * {@code SqlDialect} 单测承担。</p>
 *
 * <p>SQL Server 的连接参数（加密/信任自签证书）也按 CI 注入的
 * {@code DB_ENCRYPT} / {@code DB_TRUST_SERVER_CERTIFICATE} 透传。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "DB_CONNECTION",
        matches = "mysql|mariadb|pgsql|sqlsrv")
class MatrixSpringBootTest {

    private static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("neoskin-matrix-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        ensureSqlServerDatabase();
    }

    /**
     * SQL Server / Babelfish（WiltonDB）矩阵：目标库不存在时先连 {@code master}
     * 建库。CI 的 sqlsrv job 已用 sqlcmd 建过库，这里检测后跳过；本地
     * WiltonDB 环境则免去手工建库。
     */
    private static void ensureSqlServerDatabase() {
        Map<String, String> env = System.getenv();
        if (!"sqlsrv".equals(env.getOrDefault("DB_CONNECTION", ""))) {
            return;
        }
        String database = env.getOrDefault("DB_DATABASE", "blessingskin");
        if (!database.matches("[A-Za-z0-9_]+")) {
            return;
        }
        String url = "jdbc:sqlserver://" + env.getOrDefault("DB_HOST", "127.0.0.1")
                + ':' + env.getOrDefault("DB_PORT", "1433")
                + ";databaseName=master"
                + ";encrypt=" + env.getOrDefault("DB_ENCRYPT", "true")
                + ";trustServerCertificate="
                + env.getOrDefault("DB_TRUST_SERVER_CERTIFICATE", "false");
        try (Connection conn = DriverManager.getConnection(url,
                env.getOrDefault("DB_USERNAME", ""), env.getOrDefault("DB_PASSWORD", ""))) {
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT 1 FROM sys.databases WHERE name = ?")) {
                check.setString(1, database);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return;
                    }
                }
            }
            try (Statement create = conn.createStatement()) {
                create.execute("CREATE DATABASE [" + database + "]");
            }
        } catch (SQLException e) {
            // 建库失败留给后续 schema 安装报错（权限/连接问题会在这里暴露）
        }
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private com.neoskin.db.SqlDialect dialect;

    @Autowired
    private com.neoskin.db.SchemaInstaller schemaInstaller;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Map<String, String> env = System.getenv();
        registry.add("neoskin.database.connection",
                () -> env.getOrDefault("DB_CONNECTION", "sqlite"));
        registry.add("neoskin.database.host",
                () -> env.getOrDefault("DB_HOST", "127.0.0.1"));
        registry.add("neoskin.database.port", () -> env.getOrDefault("DB_PORT", ""));
        registry.add("neoskin.database.name",
                () -> env.getOrDefault("DB_DATABASE", "blessingskin"));
        registry.add("neoskin.database.username",
                () -> env.getOrDefault("DB_USERNAME", ""));
        registry.add("neoskin.database.password",
                () -> env.getOrDefault("DB_PASSWORD", ""));
        registry.add("neoskin.database.encrypt",
                () -> env.getOrDefault("DB_ENCRYPT", "true"));
        registry.add("neoskin.database.trust-server-certificate",
                () -> env.getOrDefault("DB_TRUST_SERVER_CERTIFICATE", "false"));
        registry.add("neoskin.storage.root", () -> DIR.resolve("storage").toString());
        registry.add("neoskin.key",
                () -> "base64:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("neoskin.http.port", () -> 0);
    }

    @Test
    void bootsOnMatrixDatabase() throws Exception {
        String expected = System.getenv("DB_CONNECTION");
        assertEquals(expected, dialect.name());

        assertNotNull(dataSource);
        assertNotNull(context.getBean(com.neoskin.config.AppPaths.class));

        // 17 张表的安装结果：全新库 = 安装向导第一步（install 是幂等的）
        int created = schemaInstaller.install();
        assertTrue(created > 0 || schemaInstaller.isInstalled());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer uid;
        try {
            jdbc.update("INSERT INTO users (email, nickname, score, avatar, password, ip,"
                            + " last_sign_at, register_at)"
                            + " VALUES (?, ?, 0, 0, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    "matrix@" + expected + ".local", "matrix", "x", "127.0.0.1");
            uid = jdbc.queryForObject(
                    "SELECT uid FROM users WHERE email = ?", Integer.class,
                    "matrix@" + expected + ".local");

            // 时间戳必须是 PHP 的 Y-m-d H:i:s，而不是驱动的 Timestamp/ISO 串。
            com.neoskin.db.UserRepository users =
                    new com.neoskin.db.UserRepository(dataSource);
            java.util.Map<String, Object> row = users.findJsonById(uid);
            assertNotNull(row);
            assertTrue(String.valueOf(row.get("register_at"))
                    .matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$"));

            // 纹理 CRUD：覆盖自增主键、public 小整数、upload_at 时间戳与保留字引用。
            com.neoskin.db.TextureRepository textures =
                    new com.neoskin.db.TextureRepository(dataSource, dialect);
            String hash = java.util.UUID.randomUUID().toString().replace("-", "")
                    + java.util.UUID.randomUUID().toString().replace("-", "");
            long tid = textures.insert("matrix", "steve", hash, 1, uid, true,
                    "2026-01-02 03:04:05");
            try {
                com.neoskin.db.TextureRepository.Row texture = textures.findByTid(tid);
                assertNotNull(texture);
                assertEquals("2026-01-02 03:04:05", texture.uploadAt());
                assertTrue(texture.isPublic());
            } finally {
                textures.deleteByTid(tid);
            }
        } finally {
            jdbc.update("DELETE FROM users WHERE email = ?",
                    "matrix@" + expected + ".local");
        }
        assertNotNull(uid);
    }

    @AfterAll
    static void clearRuntime() {
        com.neoskin.service.cache.CacheRuntime.set(null);
        com.neoskin.storage.TextureStorageRuntime.set(null);
        // 同 ApplicationContextTest：容器把通知 composer 注入 PageView 静态单例
        com.neoskin.web.view.PageView.setInboxProvider(null);
        com.neoskin.oauth.OAuthRuntime.set(null);
    }
}
