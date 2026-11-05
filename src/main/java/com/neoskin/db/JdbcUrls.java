package com.neoskin.db;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.neoskin.config.NeoSkinSettings;

/**
 * 依据 {@code .env} 里的 DB_* 变量拼装 JDBC URL。
 *
 * <p>对应源项目 {@code config/database.php} 的四个连接定义：
 * <ul>
 *   <li>{@code sqlite}：{@code DB_DATABASE} 缺省为 {@code database/database.sqlite}</li>
 *   <li>{@code mysql}：默认端口 3306，utf8mb4，支持 {@code DB_SOCKET} 与
 *       {@code MYSQL_ATTR_SSL_CA}</li>
 *   <li>{@code pgsql}：默认端口 5432，{@code search_path=public}，{@code sslmode=prefer}</li>
 *   <li>{@code sqlsrv}：默认端口 1433</li>
 * </ul>
 *
 * <p>{@code DATABASE_URL} 优先级最高，与 Laravel 的 {@code url} 配置项一致。
 */
public final class JdbcUrls {

    private JdbcUrls() {
    }

    /**
     * 生成 JDBC URL。
     *
     * @param db       配置
     * @param baseDir  应用工作目录，用于解析 SQLite 相对路径
     */
    public static String build(NeoSkinSettings.Database db, File baseDir) {
        SqlDialect dialect = SqlDialect.of(db.getConnection());
        String url = db.getUrl();
        if (url != null && !url.isBlank()) {
            return normalizeUrl(url, dialect);
        }

        String port = (db.getPort() == null || db.getPort().isBlank())
                ? dialect.defaultPort()
                : db.getPort().trim();

        return switch (dialect.name()) {
            case "sqlite" -> buildSqlite(db, baseDir);
            case "mysql" -> buildMySql(db, port, false);
            case "mariadb" -> buildMySql(db, port, true);
            case "pgsql" -> buildPostgres(db, port);
            case "sqlsrv" -> buildSqlServer(db, port);
            default -> throw new IllegalStateException("unreachable");
        };
    }

    private static String normalizeUrl(String url, SqlDialect dialect) {
        // Laravel 的 DATABASE_URL 使用 scheme:// 形式（mysql://user:pass@host/db），
        // 需要转成 JDBC 形式。若已经是 jdbc: 开头则原样返回。
        if (url.startsWith("jdbc:")) {
            return url;
        }
        String scheme = url.substring(0, url.indexOf("://"));
        String rest = url.substring(url.indexOf("://") + 3);
        return switch (scheme) {
            case "sqlite", "sqlite3" -> "jdbc:sqlite:" + rest;
            case "mysql" -> "jdbc:mysql://" + rest;
            case "mariadb" -> "jdbc:mariadb://" + rest;
            case "postgres", "postgresql" -> "jdbc:postgresql://" + rest;
            case "sqlserver", "mssql" -> "jdbc:sqlserver://" + rest;
            default -> throw new IllegalArgumentException(
                    "Unsupported DATABASE_URL scheme: " + scheme);
        };
    }

    private static String buildSqlite(NeoSkinSettings.Database db, File baseDir) {
        String database = db.getName();
        if (database == null || database.isBlank()) {
            database = new File(baseDir, "database/database.sqlite").getPath();
        }
        // Laravel 对 sqlite 的 DB_DATABASE 支持 ":memory:"
        if (!":memory:".equals(database)) {
            File f = new File(database);
            if (!f.isAbsolute()) {
                f = new File(baseDir, database);
            }
            database = f.getAbsolutePath();
        }
        return "jdbc:sqlite:" + database;
    }

    private static String buildMySql(NeoSkinSettings.Database db, String port, boolean mariadb) {
        StringBuilder sb = new StringBuilder(mariadb ? "jdbc:mariadb://" : "jdbc:mysql://");
        String socket = db.getSocket();
        if (socket != null && !socket.isBlank()) {
            // unix socket 连接：JDBC 用 host=localhost + socket 参数
            sb.append("localhost:").append(port).append('/').append(db.getName());
            sb.append("?localSocket=").append(encode(socket));
        } else {
            sb.append(db.getHost()).append(':').append(port).append('/').append(db.getName());
        }
        if (!mariadb) {
            sb.append(sb.indexOf("?") >= 0 ? '&' : '?');
            boolean useSsl = !isBlank(db.getSslCa());
            sb.append("useUnicode=true&characterEncoding=utf8")
              .append("&connectionCollation=utf8mb4_unicode_ci")
              .append("&useSSL=").append(useSsl);
            if (useSsl) {
                sb.append("&trustCertificateKeyStoreUrl=").append(encode(db.getSslCa()));
            } else {
                // caching_sha2_password 在非 SSL 通道上需要 RSA 公钥交换
                sb.append('&').append("allowPublicKeyRetrieval=true");
            }
        }
        return sb.toString();
    }

    private static String buildPostgres(NeoSkinSettings.Database db, String port) {
        Map<String, String> params = new LinkedHashMap<>();
        // 与 config/database.php 一致：search_path=public, sslmode=prefer
        params.put("currentSchema", "public");
        params.put("sslmode", "prefer");
        // 与 PDO 的隐式转换对齐：否则字符串参数写入 timestamp 列会报
        // `column ... is of type timestamp ... expression is of type text`。
        params.put("stringtype", "unspecified");
        params.put("ApplicationName", "blessing_skin");
        return "jdbc:postgresql://" + db.getHost() + ':' + port + '/' + db.getName()
                + '?' + query(params);
    }

    private static String buildSqlServer(NeoSkinSettings.Database db, String port) {
        StringBuilder sb = new StringBuilder("jdbc:sqlserver://")
                .append(db.getHost()).append(':').append(port)
                .append(";databaseName=").append(db.getName());
        if (!isBlank(db.getUsername())) {
            sb.append(";user=").append(db.getUsername());
        }
        if (db.getPassword() != null) {
            sb.append(";password=").append(db.getPassword());
        }
        sb.append(";encrypt=").append(db.isEncrypt());
        sb.append(";trustServerCertificate=").append(db.isTrustServerCertificate());
        sb.append(";characterEncoding=UTF-8");
        return sb.toString();
    }

    private static String query(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(encode(e.getValue()));
        }
        return sb.toString();
    }

    private static String encode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8).equals(s)
                ? s.replace(" ", "%20")
                : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** SQL Server 的凭据写在 URL 里，其余驱动通过 DataSource 属性传入。 */
    public static boolean embedsCredentials(String connection) {
        return SqlDialect.of(connection).name().equals("sqlsrv");
    }
}
