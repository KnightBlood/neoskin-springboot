package com.neoskin.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 已有 {@link Connection} 时的方言桥。
 *
 * <p>分页/行数限制是 SQL 差异最大的地方（{@code LIMIT} vs {@code OFFSET ... FETCH}），
 * 而数据访问层大量仓库只持有 {@code DataSource}、没有注入 {@link SqlDialect}。
 * 这里按 JDBC 元数据（{@code DatabaseMetaData.getDatabaseProductName()}）识别产品，
 * 再复用 {@link SqlDialect} 的片段生成与参数绑定，避免给 70 多处构造点加参数。</p>
 *
 * <p>识别规则与 {@link SqlDialect#of(String)} 的连接名一一对应：
 * MySQL / MariaDB / PostgreSQL / SQL Server / SQLite。</p>
 */
public final class Paging {

    private Paging() {
    }

    /** 按连接的产品名选择方言（未知产品按 SQLite 处理）。 */
    public static SqlDialect dialectOf(Connection conn) throws SQLException {
        String product = conn.getMetaData().getDatabaseProductName();

        if (product == null) {
            return SqlDialect.of("sqlite");
        }
        String p = product.toLowerCase();
        if (p.contains("sql server")) {
            return SqlDialect.of("sqlsrv");
        }
        if (p.contains("mariadb")) {
            return SqlDialect.of("mariadb");
        }
        if (p.contains("mysql")) {
            return SqlDialect.of("mysql");
        }
        if (p.contains("postgresql")) {
            return SqlDialect.of("pgsql");
        }
        if (p.contains("sqlite")) {
            return SqlDialect.of("sqlite");
        }

        return SqlDialect.of("sqlite");
    }

    /** 分页子句（含 2 个占位符），见 {@link SqlDialect#pagingClause()}。 */
    public static String clause(Connection conn) throws SQLException {
        return dialectOf(conn).pagingClause();
    }

    /** 绑定分页参数（按方言决定 limit/offset 顺序），返回下一个索引。 */
    public static int bind(Connection conn, PreparedStatement ps, int index, long limit,
            long offset) throws SQLException {
        return dialectOf(conn).bindPaging(ps, index, limit, offset);
    }
}
