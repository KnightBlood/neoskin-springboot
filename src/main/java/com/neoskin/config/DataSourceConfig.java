package com.neoskin.config;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.neoskin.db.JdbcUrls;
import com.neoskin.db.SqlDialect;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 数据源配置。
 *
 * <p>不使用 Spring Boot 的 {@code spring.datasource.*} 自动配置，而是按源项目
 * {@code config/database.php} 的规则自己拼 JDBC URL（见 {@link JdbcUrls}），因为：
 * <ul>
 *   <li>{@code DATABASE_URL} 的优先级与格式与 Laravel 一致；</li>
 *   <li>{@code DB_SOCKET}、{@code MYSQL_ATTR_SSL_CA}、{@code DB_FOREIGN_KEYS}
 *       这些 Laravel 专有语义需要显式翻译；</li>
 *   <li>SQLite 的相对路径要相对应用工作目录解析，并且父目录需要自动创建。</li>
 * </ul>
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    /** 应用工作目录，用于解析 SQLite / storage 等相对路径。 */
    public static File baseDir() {
        return new File(System.getProperty("user.dir"));
    }

    @Bean
    public SqlDialect sqlDialect(NeoSkinSettings settings) {
        return SqlDialect.of(settings.getDatabase().getConnection());
    }

    @Bean
    public DataSource dataSource(NeoSkinSettings settings, SqlDialect dialect) {
        NeoSkinSettings.Database db = settings.getDatabase();
        String url = JdbcUrls.build(db, baseDir());
        log.info("database: {} -> {}", dialect.name(), sanitize(url));

        prepareSqlite(url, db);

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("neoskin");
        hikari.setJdbcUrl(url);
        hikari.setDriverClassName(driverClass(dialect));
        if (!JdbcUrls.embedsCredentials(db.getConnection())) {
            hikari.setUsername(db.getUsername());
            hikari.setPassword(db.getPassword());
        }
        // 与 Laravel 的连接行为对齐：失败立即报错，不要静默降级
        hikari.setInitializationFailTimeout(1);
        hikari.setMaximumPoolSize(10);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(10_000);
        hikari.setAutoCommit(true);
        return new HikariDataSource(hikari);
    }

    /** 让 Hibernate 使用与 JDBC URL 匹配的方言（SQLite 必须显式指定）。 */
    @Bean
    public HibernatePropertiesCustomizer hibernateDialect(SqlDialect dialect) {
        return properties -> {
            properties.put("hibernate.dialect", dialect.hibernateDialect());
            // 源项目时间戳无时区，统一按 UTC 存储/读取，避免跨时区漂移
            properties.put("hibernate.jdbc.time_zone", "UTC");
        };
    }

    private static String driverClass(SqlDialect dialect) {
        return switch (dialect.name()) {
            case "sqlite" -> "org.sqlite.JDBC";
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            case "mariadb" -> "org.mariadb.jdbc.Driver";
            case "pgsql" -> "org.postgresql.Driver";
            case "sqlsrv" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            default -> throw new IllegalStateException("unreachable");
        };
    }

    /** SQLite：自动创建数据库文件与其父目录，并设置 foreign_keys pragma。 */
    private static void prepareSqlite(String url, NeoSkinSettings.Database db) {
        String prefix = "jdbc:sqlite:";
        if (!url.startsWith(prefix)) {
            return;
        }
        String path = url.substring(prefix.length());
        if (path.contains(":memory:") || path.startsWith("file:")) {
            return;
        }
        try {
            Path p = Path.of(path);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            if (!Files.exists(p)) {
                Files.createFile(p);
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot prepare sqlite database file: " + path, e);
        }
    }

    /** 日志里隐藏密码。 */
    private static String sanitize(String url) {
        return url.replaceAll("(password=)[^;&]*", "$1***");
    }
}
