package com.neoskin.web.validation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

/**
 * {@link Validator.PresenceVerifier} 的 JDBC 实现（{@code unique:table,column}）。
 */
public final class JdbcPresenceVerifier implements Validator.PresenceVerifier {
    private final DataSource dataSource;

    public JdbcPresenceVerifier(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public int getCount(String table, String column, Object value, Object excludeId, String idColumn) {
        if (value == null) return 0;
        // 白名单表名/列名（防止注入）
        if (!table.matches("[A-Za-z_]+") || !column.matches("[A-Za-z_]+")) return 0;
        boolean exclude = excludeId != null && idColumn != null
                && idColumn.matches("[A-Za-z_]+");
        try (Connection conn = dataSource.getConnection()) {
            var dialect = com.neoskin.db.Paging.dialectOf(conn);
            String sql = "SELECT COUNT(*) FROM " + dialect.quote(table) + " WHERE "
                    + dialect.quote(column) + " = ?"
                    + (exclude ? " AND " + dialect.quote(idColumn) + " <> ?" : "");
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setObject(1, value);
                if (exclude) {
                    stmt.setObject(2, excludeId);
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (SQLException e) {
            return 0;
        }
    }
}
